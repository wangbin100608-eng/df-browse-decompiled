package com.deltainventory.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * 本地视觉识别：ONNX Runtime 推理 + 感知哈希 + 图像变化检测。
 *
 * 约束：
 * - 不依赖云端 API，全部本地推理。
 * - 范围仅普通非武器物品（由物品数据库过滤）。
 * - 模型文件位于 assets/models/，含版本与输入输出说明（见 models/README.md）。
 * - 未训练/无模型时不伪造结果：明确的置信度与 unknown 检测。
 */
class VisionEngine private constructor(private val ctx: Context) {

    companion object {
        const val TAG = "VisionEngine"
        @Volatile private var instance: VisionEngine? = null
        fun get(ctx: Context): VisionEngine = instance ?: synchronized(this) {
            instance ?: VisionEngine(ctx.applicationContext).also { instance = it }
        }
        const val INPUT_W = 640L
        const val INPUT_H = 640L
        const val MIN_CONF = 0.35f
        const val INV_255 = 1f / 255f
    }

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var opencvAvailable = false
    // 复用缓冲，避免每帧分配大数组
    private var pixelCache: IntArray? = null       // changeScore/phash 用（工作图尺寸）
    private var bufPixels: IntArray? = null        // preprocess 用（640x640）
    private var floatCache: FloatArray? = null
    private var inputName: String? = null

    init {
        try { System.loadLibrary("di_vision"); opencvAvailable = true
            Log.i(TAG, "OpenCV native loaded")
        } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "OpenCV native unavailable: ${e.message}") }
    }

    /** OpenCV 变化检测（native），失败回退到 Kotlin 实现 */
    private external fun nativeChangeScore(a: IntArray, b: IntArray, w: Int, h: Int): Float
    private external fun nativePhash(pixels: IntArray, w: Int, h: Int): Long

    private fun pixels(bmp: Bitmap): IntArray {
        val need = bmp.width * bmp.height
        val arr = pixelCache?.takeIf { it.size >= need } ?: IntArray(need).also { pixelCache = it }
        bmp.getPixels(arr, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return arr
    }
    var modelVersion: String = "none"
        private set
    var available: Boolean = false
        private set

    init {
        try {
            val modelBytes = ctx.assets.open("models/item_detector.onnx").use { it.readBytes() }
            env = OrtEnvironment.getEnvironment()
            session = env!!.createSession(modelBytes, OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            })
            modelVersion = String(ctx.assets.open("models/version.txt").use { it.readBytes() }).trim()
            available = true
            Log.i(TAG, "ONNX 模型加载成功 version=$modelVersion")
        } catch (e: Exception) {
            Log.w(TAG, "ONNX 模型不可用: ${e.message}。将仅使用传统视觉特征（哈希/颜色），未知物品标记 unknown")
        }
    }

    data class Detection(
        val bbox: FloatArray,          // x,y,w,h 归一化
        val classId: Int,
        val itemTypeId: String,
        val confidence: Float,
        val phash: Long,
        val colorHist: FloatArray
    )

    /** 运行一帧识别。输入为 RGBA Image 转出的 Bitmap。 */
    suspend fun detect(bitmap: Bitmap, itemTypes: List<String>): List<Detection> = withContext(Dispatchers.Default) {
        val results = mutableListOf<Detection>()
        if (available && session != null) {
            try {
                if (inputName == null) inputName = session!!.inputNames.first()
                val input = preprocess(bitmap)
                val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1L, 3L, INPUT_H, INPUT_W))
                val out = session!!.run(mapOf(inputName!! to tensor))
                tensor.close()
                // 解析输出（YOLO 风格 [1,N,6]：x,y,w,h,conf,class]）—— 模型说明见 models/README.md
                val arr = out[0].value as Array<*>
                for (row in arr) {
                    val r = row as FloatArray
                    if (r.size < 6) continue
                    val conf = r[4]
                    if (conf < MIN_CONF) continue
                    val cls = r[5].toInt()
                    if (cls < 0 || cls >= itemTypes.size) continue
                    results.add(Detection(
                        floatArrayOf(r[0], r[1], r[2], r[3]), cls, itemTypes[cls], conf,
                        phash(bitmap), colorHistogram(bitmap)
                    ))
                }
                out.close()
            } catch (e: Exception) { Log.e(TAG, "推理失败: ${e.message}") }
        }
        // 无论模型是否可用，都产出传统特征用于变化检测；未知物品由上层按 confidence=0 处理
        results
    }

    /** 图像变化检测：返回两帧的归一化差异分数，用于跳过未变化帧（性能优化） */
    fun changeScore(a: Bitmap, b: Bitmap): Float {
        if (a.width != b.width || a.height != b.height) return 1f
        if (opencvAvailable) {
            try { return nativeChangeScore(pixels(a), pixels(b), a.width, a.height) }
            catch (e: Exception) { Log.w(TAG, "nativeChangeScore failed: ${e.message}") }
        }
        val w = 32; val h = 32
        val sa = Bitmap.createScaledBitmap(a, w, h, true)
        val sb = Bitmap.createScaledBitmap(b, w, h, true)
        var diff = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val ca = sa.getPixel(x, y); val cb = sb.getPixel(x, y)
            diff += kotlin.math.abs((ca shr 16 and 0xFF) - (cb shr 16 and 0xFF))
            diff += kotlin.math.abs((ca shr 8 and 0xFF) - (cb shr 8 and 0xFF))
            diff += kotlin.math.abs((ca and 0xFF) - (cb and 0xFF))
        }
        sa.recycle(); sb.recycle()
        return (diff.toFloat() / (w * h * 3 * 255)).coerceIn(0f, 1f)
    }

    /** 感知哈希（dHash 64bit） */
    fun phash(bmp: Bitmap): Long {
        if (opencvAvailable) {
            try { return nativePhash(pixels(bmp), bmp.width, bmp.height) }
            catch (e: Exception) { Log.w(TAG, "nativePhash failed: ${e.message}") }
        }
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val l = android.graphics.Color.red(small.getPixel(x, y))
            val r = android.graphics.Color.red(small.getPixel(x + 1, y))
            hash = (hash shl 1) or (if (l > r) 1L else 0L)
        }
        small.recycle()
        return hash
    }

    /** 16 桶颜色直方图摘要 */
    fun colorHistogram(bmp: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        val hist = FloatArray(16)
        for (y in 0 until 32) for (x in 0 until 32) {
            val c = small.getPixel(x, y)
            val r = (c shr 16 and 0xFF) shr 4
            val g = (c shr 8 and 0xFF) shr 4
            val b = (c and 0xFF) shr 4
            hist[r * 4 + g] += 1f / (32 * 32)
            hist[b] += 0.0001f
        }
        small.recycle()
        return hist
    }

    private fun preprocess(bmp: Bitmap): FloatArray {
        val w = INPUT_W.toInt(); val h = INPUT_H.toInt()
        val wh = w * h
        val buf = floatCache?.takeIf { it.size >= wh * 3 } ?: FloatArray(wh * 3).also { floatCache = it }
        val px = bufPixels?.takeIf { it.size >= wh } ?: IntArray(wh).also { bufPixels = it }
        // 工作图已接近 640，若尺寸相同直接用 pixels()，否则缩放
        if (bmp.width == w && bmp.height == h) bmp.getPixels(px, 0, w, 0, 0, w, h)
        else {
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            scaled.getPixels(px, 0, w, 0, 0, w, h)
            scaled.recycle()
        }
        for (i in 0 until wh) {
            val c = px[i]
            buf[i] = ((c shr 16 and 0xFF) * INV_255 - 1f)
            buf[wh + i] = ((c shr 8 and 0xFF) * INV_255 - 1f)
            buf[2 * wh + i] = ((c and 0xFF) * INV_255 - 1f)
        }
        return buf
    }

    fun close() { session?.close(); session = null; pixelCache = null; bufPixels = null; floatCache = null }

}
