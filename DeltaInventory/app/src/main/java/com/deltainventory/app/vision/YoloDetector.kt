package com.deltainventory.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * YOLOv8 检测器（迁移学习模型 detector_yolov8n.onnx）。
 * 输入 images [1,3,640,640] RGB 归一化到 [0,1]
 * 输出 output0 [1,20,8400]：前4行 cx,cy,w,h（像素坐标），后16行为类别分数
 * 做置信度过滤 + NMS，输出归一化 bbox。
 * 与 VisionEngine 独立，不破坏原有 dHash 骨架链路。
 */
class YoloDetector private constructor(private val ctx: Context) {
    data class Det(val cls: Int, val label: String, val conf: Float,
                   val x: Float, val y: Float, val w: Float, val h: Float) // 归一化 xywh

    companion object {
        const val TAG = "YoloDetector"
        const val SZ = 640
        const val CONF = 0.25f
        const val NMS = 0.45f
        @Volatile private var inst: YoloDetector? = null
        fun get(ctx: Context): YoloDetector = inst ?: synchronized(this) {
            inst ?: YoloDetector(ctx.applicationContext).also { inst = it }
        }
    }

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    var available = false; private set
    var version = "none"; private set

    init {
        try {
            val bytes = ctx.assets.open("models/detector_yolov8n.onnx").use { it.readBytes() }
            env = OrtEnvironment.getEnvironment()
            session = env!!.createSession(bytes, OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            })
            version = try { String(ctx.assets.open("models/detector_version.txt").use { it.readBytes() }).trim() } catch (_: Exception) { "yolov8n-v1" }
            available = true
            Log.i(TAG, "YOLO 模型加载成功 version=$version")
        } catch (e: Exception) {
            Log.w(TAG, "YOLO 模型不可用: ${e.message}")
        }
    }

    /** 归一化到 [0,1] RGB（YOLOv8 导出要求） */
    private fun preprocess(bmp: Bitmap): FloatArray {
        val wh = SZ * SZ
        val buf = FloatArray(wh * 3)
        val px = IntArray(wh)
        val scaled = Bitmap.createScaledBitmap(bmp, SZ, SZ, true)
        scaled.getPixels(px, 0, SZ, 0, 0, SZ, SZ)
        scaled.recycle()
        for (i in 0 until wh) {
            val c = px[i]
            buf[i] = (c shr 16 and 0xFF) / 255f
            buf[wh + i] = (c shr 8 and 0xFF) / 255f
            buf[2 * wh + i] = (c and 0xFF) / 255f
        }
        return buf
    }

    suspend fun detect(bitmap: Bitmap, labels: List<String>): List<Det> = withContext(Dispatchers.Default) {
        if (!available || session == null) return@withContext emptyList()
        try {
            val input = preprocess(bitmap)
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1L,3L,SZ.toLong(),SZ.toLong()))
            val out = session!!.run(mapOf(session!!.inputNames.first() to tensor))
            tensor.close()
            // 输出 [1,20,8400] -> out[0].value as Array<FloatArray> (20 x 8400)
            val raw = (out[0].value as Array<*>)[0] as Array<FloatArray>
            val rows = raw.size              // 20
            val n = raw[0].size              // 8400
            val ncls = rows - 4
            val cands = mutableListOf<Det>()
            for (i in 0 until n) {
                var best = 0; var bs = 0f
                for (c in 0 until ncls) {
                    val s = raw[4 + c][i]
                    if (s > bs) { bs = s; best = c }
                }
                if (bs < CONF || best >= labels.size) continue
                val cx = raw[0][i]; val cy = raw[1][i]; val w = raw[2][i]; val h = raw[3][i]
                cands.add(Det(best, labels[best], bs,
                    (cx - w/2f)/SZ, (cy - h/2f)/SZ, w/SZ, h/SZ))
            }
            out.close()
            return@withContext nms(cands)
        } catch (e: Exception) {
            Log.e(TAG, "YOLO 推理失败: ${e.message}")
            return@withContext emptyList()
        }
    }

    private fun nms(dets: List<Det>): List<Det> {
        val sorted = dets.sortedByDescending { it.conf }.toMutableList()
        val keep = mutableListOf<Det>()
        while (sorted.isNotEmpty()) {
            val a = sorted.removeAt(0); keep.add(a)
            sorted.removeAll { b -> if (b.cls != a.cls) false else iou(a,b) > NMS }
        }
        return keep
    }
    private fun iou(a: Det, b: Det): Float {
        val ix = maxOf(0f, minOf(a.x+a.w,b.x+b.w) - maxOf(a.x,b.x))
        val iy = maxOf(0f, minOf(a.y+a.h,b.y+b.h) - maxOf(a.y,b.y))
        val inter = ix*iy; val uni = a.w*a.h + b.w*b.h - inter
        return if (uni<=0f) 0f else inter/uni
    }
}
