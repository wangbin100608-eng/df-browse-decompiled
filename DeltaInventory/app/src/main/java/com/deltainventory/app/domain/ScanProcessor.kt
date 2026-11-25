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
import com.deltainventory.app.vision.*
import kotlinx.coroutines.launch

/**
 * 三步流程处理器：
 * 1) 开始识别：采集画面 → OCR识别背包/保险箱格数 → 图标匹配全部物资 → 累积清单
 * 2) 结束比对：用 itemId 比对全量数据库，确认名称/格数/价格
 * 3) 输出摆放策略：背包+保险箱+地面统一二维装箱
 */
class ScanProcessor(
    private val context: Context,
    private val viewModel: ScanViewModel
) {
    companion object {
        const val TAG = "ScanProcessor"
        const val CHANGE_THRESHOLD = 0.015f
    }

    private val matcher = IconMatcher.get(context)
    private val iconScanner = IconScanEngine(matcher)
    private var binder: CaptureBinder? = null
    private var processorJob: kotlinx.coroutines.Job? = null
    private var lastBitmap: Bitmap? = null
    private var frameCounter = 0L

    /** 阶段1结果：原始识别清单（itemId + 置信度，尚未确认） */
    data class RawItem(val itemId: String, val confidence: Float, val region: String,
                       val gx: Int, val gy: Int)
    private val rawItems = mutableListOf<RawItem>()

    /** 阶段2结果：比对数据库后的确认物资 */
    data class ConfirmedItem(val id: String, val name: String, val w: Int, val h: Int,
                             val price: Long, val category: String, val region: String,
                             val confidence: Float, val quantity: Int)
    private val _confirmed = mutableListOf<ConfirmedItem>()
    val confirmed: List<ConfirmedItem> get() = _confirmed

    private var _gridInfo = GridOcr.GridInfo(0, 0, 0, 0)
    val gridInfo get() = _gridInfo

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? CaptureBinder
            startFrameLoop()
        }
        override fun onServiceDisconnected(name: ComponentName?) { stopFrameLoop() }
    }

    fun bind() = context.bindService(android.content.Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
    fun unbind() { stopFrameLoop(); runCatching { context.unbindService(connection) } }

    /** 阶段1：开始识别（清空上次结果，绑定服务开始采集） */
    fun startRecognition() {
        rawItems.clear(); _confirmed.clear(); frameCounter = 0
        bind()
    }

    /** 阶段2：结束识别并比对全量数据库 */
    suspend fun finishAndVerify(): List<ConfirmedItem> {
        stopFrameLoop()
        _confirmed.clear()
        // 按 itemId 分组去重（同一物品多帧出现→数量+1，不同位置保留）
        val groups = rawItems.groupBy { "${it.itemId}@${it.region}@${it.gx},${it.gy}" }
        for ((_, list) in groups) {
            val first = list.first()
            // 从全量物品库确认（IconMatcher 已内置 name/size/price，直接取）
            val m = matcher.findById(first.itemId)
            if (m != null) {
                _confirmed.add(ConfirmedItem(m.id, m.name, m.w, m.h, m.price, m.cat,
                    first.region, first.confidence, list.size))
            }
        }
        Log.i(TAG, "比对完成: ${_confirmed.size} 种物资")
        return _confirmed
    }

    /** 阶段3：输出摆放策略（背包+保险箱） */
    suspend fun buildLayout(): LayoutResult {
        val grid = _gridInfo
        val bagItems = _confirmed.filter { it.region == "bag" }.map {
            CoreBridge.PackItem(it.id, it.w, it.h, it.price, it.quantity, true)
        }
        val safeItems = _confirmed.filter { it.region == "safe" }.map {
            CoreBridge.PackItem(it.id, it.w, it.h, it.price, it.quantity, true)
        }
        val bagResult = CoreBridge.optimize(grid.bagWidth.coerceAtLeast(1), grid.bagHeight.coerceAtLeast(1), bagItems)
        val safeResult = CoreBridge.optimize(grid.safeWidth.coerceAtLeast(1), grid.safeHeight.coerceAtLeast(1), safeItems)
        return LayoutResult(bagResult, safeResult,
            _confirmed.filter { it.region == "ground" })
    }

    data class LayoutResult(val bag: CoreBridge.PackResult, val safe: CoreBridge.PackResult,
                            val ground: List<ConfirmedItem>)

    // ---------- 帧处理 ----------
    private fun startFrameLoop() {
        val b = binder ?: return
        processorJob?.cancel()
        processorJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            b.frames.collect { processFrame(it) }
        }
    }
    private fun stopFrameLoop() { processorJob?.cancel(); processorJob = null
        lastBitmap?.recycle(); lastBitmap = null }

    private suspend fun processFrame(bitmap: Bitmap) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        frameCounter++
        val prev = lastBitmap
        if (prev != null && prev.width == bitmap.width && prev.height == bitmap.height) {
            if (matcher.phash(prev) == matcher.phash(bitmap)) { bitmap.recycle(); return@withContext }
        }
        prev?.recycle(); lastBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

        // 1) OCR 识别格数（每 N 帧做一次，省开销）
        if (frameCounter % 10 == 1L) {
            GridOcr.recognize(bitmap)?.let {
                _gridInfo = it
                Log.i(TAG, "OCR 格数: 背包 ${it.bagWidth}x${it.bagHeight} 保险箱 ${it.safeWidth}x${it.safeHeight}")
            }
        }

        // 2) 图标匹配扫描（背包区域 + 地面）
        val cols = _gridInfo.bagWidth.coerceAtLeast(1)
        val rows = _gridInfo.bagHeight.coerceAtLeast(1)
        val detections = iconScanner.scan(bitmap, cols, rows, floatArrayOf(0f, 0.25f, 1f, 0.5f))
        for (d in detections) {
            rawItems.add(RawItem(d.itemId, d.confidence, "bag", d.gridX, d.gridY))
        }
        // 地面区域
        val groundDet = iconScanner.scan(bitmap, 6, 4, floatArrayOf(0f, 0.6f, 1f, 0.4f))
        for (d in groundDet) rawItems.add(RawItem(d.itemId, d.confidence, "ground", d.gridX, d.gridY))

        viewModel.onFrame(emptyList()) // 更新帧计数
        bitmap.recycle()
    }
}
