package com.deltainventory.app.domain

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import com.deltainventory.app.capture.CaptureBinder
import com.deltainventory.app.capture.CaptureService
import com.deltainventory.app.capture.ScreenInfo
import com.deltainventory.app.vision.VisionEngine
import kotlinx.coroutines.*

/**
 * 采集→识别→追踪 主链路处理器（性能优化版）。
 * - 采集图已按屏幕比例缩到工作分辨率（长边640），全链路小图处理。
 * - Bitmap 池复用，避免每帧分配全尺寸副本。
 * - 变化检测在降采样图上做，未变化帧跳过推理。
 * - 坐标从工作图归一化 → 屏幕像素 → 内容格子（自适应不同屏幕）。
 */
class ScanProcessor(
    private val context: Context,
    private val viewModel: ScanViewModel,
    private val itemTypes: List<String>,
    private val gridCellPx: Int = 64
) {
    companion object {
        const val TAG = "ScanProcessor"
        const val CHANGE_THRESHOLD = 0.015f
        const val POOL_SIZE = 3
    }

    private var binder: CaptureBinder? = null
    private var processorJob: Job? = null
    /** Bitmap 对象池：复用上一帧比较图，避免频繁分配 */
    private val bitmapPool = ArrayDeque<Bitmap>(POOL_SIZE)
    private var lastBitmap: Bitmap? = null
    private var screenInfo = ScreenInfo(0, 0, 0, 0)
    private var workSize = android.util.Size(0, 0)
    private val vision = VisionEngine.get(context)
    private var frameCounter = 0L
    private var skippedFrames = 0L
    private var processedFrames = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? CaptureBinder
            Log.i(TAG, "CaptureService 已连接")
            startProcessing()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "CaptureService 断开")
            stopProcessing()
        }
    }

    fun bind() {
        context.bindService(android.content.Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() { stopProcessing(); runCatching { context.unbindService(connection) } }

    /** 性能统计（供 UI/日志） */
    fun stats(): String = "processed=$processedFrames skipped=$skippedFrames total=${processedFrames + skippedFrames}"

    private fun startProcessing() {
        val b = binder ?: return
        processorJob?.cancel()
        processorJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            b.frames.collect { bitmap -> processFrame(bitmap) }
        }
    }

    private fun stopProcessing() {
        processorJob?.cancel(); processorJob = null
        lastBitmap?.let { recycleToPool(it) }; lastBitmap = null
        bitmapPool.forEach { it.recycle() }; bitmapPool.clear()
    }

    private suspend fun processFrame(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        val b = binder
        screenInfo = b?.screenInfo ?: screenInfo
        workSize = b?.screenInfo?.let { android.util.Size(it.width, it.height) } ?: workSize
        // 实际工作图尺寸来自 bitmap 本身
        val ww = bitmap.width; val wh = bitmap.height
        frameCounter++

        // 1) 变化检测：用工作图直接比较（已降采样，开销小）
        val prev = lastBitmap
        if (prev != null && prev.width == ww && prev.height == wh) {
            val change = vision.changeScore(prev, bitmap)
            if (change < CHANGE_THRESHOLD) {
                skippedFrames++; bitmap.recycle(); return@withContext
            }
        }
        processedFrames++

        // 2) 复用池保存当前帧用于下一帧比较
        lastBitmap?.let { recycleToPool(it) }
        lastBitmap = obtainBitmap(bitmap)

        // 3) ONNX 推理（输入已是工作分辨率，preprocess 缩放次数少）
        val detections = vision.detect(bitmap, itemTypes)

        // 4) 坐标换算：检测框在工作图归一化 → 屏幕像素 → 内容格子
        // 工作图与屏幕同比例，cx/ww * screenWidth 即屏幕像素 x
        val sw = screenInfo.width.coerceAtLeast(1)
        val sh = screenInfo.height.coerceAtLeast(1)
        val observations = detections.mapIndexed { idx, d ->
            val cxPix = d.bbox[0] / ww * sw
            val cyPix = d.bbox[1] / wh * sh
            val gx = (cxPix / gridCellPx).toInt()
            val gy = (cyPix / gridCellPx).toInt()
            CoreBridge.Observation(
                observationId = "obs_${frameCounter}_$idx",
                itemTypeId = d.itemTypeId,
                containerId = "ground",
                regionId = regionOf(cyPix),
                x = gx, y = gy,
                phash = d.phash,
                frameIndex = frameCounter,
                confidence = d.confidence,
                quantity = 1
            )
        }
        viewModel.onFrame(observations)
        bitmap.recycle()
    }

    private fun regionOf(screenY: Float): String = when {
        screenY < screenInfo.height * 0.25f -> "bag"
        screenY < screenInfo.height * 0.6f -> "ground_center"
        else -> "ground_bottom"
    }

    /** 从池中取 Bitmap 或新建，复制 src */
    private fun obtainBitmap(src: Bitmap): Bitmap {
        val bmp = if (bitmapPool.isNotEmpty()) bitmapPool.removeLast() else null
        return if (bmp != null && bmp.width == src.width && bmp.height == src.height) {
            bmp.eraseColor(android.graphics.Color.TRANSPARENT)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawBitmap(src, 0f, 0f, null)
            bmp
        } else {
            bmp?.recycle()
            src.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    private fun recycleToPool(bmp: Bitmap) {
        if (bitmapPool.size < POOL_SIZE && !bmp.isRecycled) bitmapPool.addLast(bmp)
        else bmp.recycle()
    }
}
