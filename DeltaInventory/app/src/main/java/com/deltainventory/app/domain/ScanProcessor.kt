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
 * 采集→识别→追踪 主链路处理器。
 * 订阅 CaptureService 的 Bitmap 帧流，做变化检测、ONNX 推理、坐标换算，
 * 生成 Observation 交给 ScanViewModel.onFrame。
 *
 * 屏幕自适应：推理输入固定 640x640，但检测框坐标从归一化换算回当前屏幕像素，
 * 再按格子映射为内容坐标；屏幕尺寸变化时通过 ScreenInfo 动态换算。
 */
class ScanProcessor(
    private val context: Context,
    private val viewModel: ScanViewModel,
    private val itemTypes: List<String>,     // class_id -> itemTypeId 映射
    private val gridCellPx: Int = 64          // 每格像素（可配置）
) {

    private var binder: CaptureBinder? = null
    private var processorJob: Job? = null
    private var lastBitmap: Bitmap? = null
    private var screenInfo = ScreenInfo(0, 0, 0, 0)
    private val vision = VisionEngine.get(context)
    private var frameCounter = 0L

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
        val intent = android.content.Intent(context, CaptureService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        stopProcessing()
        runCatching { context.unbindService(connection) }
    }

    private fun startProcessing() {
        val b = binder ?: return
        processorJob?.cancel()
        processorJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            b.frames.collect { bitmap -> processFrame(bitmap) }
        }
    }

    private fun stopProcessing() {
        processorJob?.cancel()
        processorJob = null
        lastBitmap?.recycle(); lastBitmap = null
    }

    private suspend fun processFrame(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        screenInfo = binder?.screenInfo ?: screenInfo
        frameCounter++

        // 1) 变化检测：未变化帧跳过推理（省电省CPU）
        val prev = lastBitmap
        if (prev != null && prev.width == bitmap.width && prev.height == bitmap.height) {
            val change = vision.changeScore(prev, bitmap)
            if (change < CHANGE_THRESHOLD) { bitmap.recycle(); return@withContext }
        }
        prev?.recycle()
        lastBitmap = copyBitmap(bitmap) // 保留一份用于下一帧比较

        // 2) ONNX 推理
        val detections = vision.detect(bitmap, itemTypes)

        // 3) 检测框坐标：归一化 -> 屏幕像素 -> 内容格子坐标（自适应屏幕）
        val observations = detections.mapIndexed { idx, d ->
            val cxPix = d.bbox[0] * screenInfo.width
            val cyPix = d.bbox[1] * screenInfo.height
            val gx = (cxPix / gridCellPx).toInt()
            val gy = (cyPix / gridCellPx).toInt()
            CoreBridge.Observation(
                observationId = "obs_${frameCounter}_$idx",
                itemTypeId = d.itemTypeId,
                containerId = "ground",                 // 默认地面；区域判定可由 bbox 所在 UI 区域细化
                regionId = regionOf(cxPix, cyPix),
                x = gx, y = gy,
                phash = d.phash,
                frameIndex = frameCounter,
                confidence = d.confidence,
                quantity = 1
            )
        }

        // 4) 交给账本追踪
        viewModel.onFrame(observations)
        bitmap.recycle()
    }

    /** 根据屏幕坐标划分内容区域（不同区域独立坐标系） */
    private fun regionOf(px: Float, py: Float): String = when {
        py < screenInfo.height * 0.25f -> "bag"
        py < screenInfo.height * 0.6f -> "ground_center"
        else -> "ground_bottom"
    }

    private fun copyBitmap(src: Bitmap): Bitmap = src.copy(Bitmap.Config.ARGB_8888, false)

    companion object {
        const val TAG = "ScanProcessor"
        const val CHANGE_THRESHOLD = 0.02f
    }
}
