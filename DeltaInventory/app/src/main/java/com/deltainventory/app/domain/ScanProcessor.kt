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
import com.deltainventory.app.vision.RegionDetector
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
        const val HIGH_CONF = 0.55f
        const val LOW_CONF = 0.35f
    }

    /** 从 detection 归一化坐标裁出对应 cell（供人工确认候选再匹配） */
    private fun cropCell(bitmap: Bitmap, d: IconScanEngine.CellDetection): Bitmap {
        val cx = (d.centerPixX * bitmap.width).toInt().coerceIn(0, bitmap.width-1)
        val cy = (d.centerPixY * bitmap.height).toInt().coerceIn(0, bitmap.height-1)
        val s = minOf(32, bitmap.width, bitmap.height)
        return Bitmap.createBitmap(bitmap, (cx-s/2).coerceAtLeast(0), (cy-s/2).coerceAtLeast(0), s, s)
    }

    private val matcher = IconMatcher.get(context)
    var matchThreshold = HIGH_CONF
    var hashMaxDist = 20
    fun setHashDist(v: Int) { matcher.maxHashDist = v }
    private val iconScanner = IconScanEngine(matcher)
    private var binder: CaptureBinder? = null
    private var processorJob: kotlinx.coroutines.Job? = null
    private var lastBitmap: Bitmap? = null
    private var frameCounter = 0L
    private var lastFrameForOcr: Bitmap? = null
    private var dynamicBagRegion = floatArrayOf(0f, 0.25f, 1f, 0.5f) // 默认，检测后覆盖

    /** 阶段1结果：原始识别清单（itemId + 置信度，尚未确认） */
    data class RawItem(val itemId: String, val confidence: Float, val region: String,
                       val gx: Int, val gy: Int)
    private val rawItems = mutableListOf<RawItem>()

    /** 阶段2结果：比对数据库后的确认物资 */
    data class PendingItem(val cellId: String, val candidates: List<String>,
                           val region: String, val gx: Int, val gy: Int,
                           val bestScore: Float, val chosenId: String)
    private val _pendingItems = mutableListOf<PendingItem>()
    val pendingItems: List<PendingItem> get() = _pendingItems

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
        rawItems.clear(); _confirmed.clear(); _pendingItems.clear(); frameCounter = 0
        bind()
    }

    /** 阶段2：结束识别并比对全量数据库 */
    suspend fun finishAndVerify(): List<ConfirmedItem> {
        stopFrameLoop()
        _confirmed.clear()
        // 1) 每格去重：同一格多帧识别→取最高置信度的 1 条（不按帧数累计数量）
        val cells = rawItems.groupBy { "${it.itemId}@${it.region}@${it.gx},${it.gy}" }
            .map { (_, list) -> list.maxByOrNull { it.confidence }!! }
            .toMutableList()

        // 2) 多格合并：使用纯逻辑 ClusterMerger（相邻同 ID 格子按物品尺寸合并）
        val cellsForMerge = cells.map { ClusterMerger.Cell(it.itemId, it.region, it.gx, it.gy, it.confidence) }
        val merged = ClusterMerger.merge(cellsForMerge) { id -> matcher.findById(id)?.let { it.w to it.h } ?: (1 to 1) }

        // 3) 按 itemId+区域 汇总为确认物资；数量=独立实例数（同类型多件堆叠）
        val grouped = merged.groupBy { "${it.itemId}@${it.region}" }
        for ((_, list) in grouped) {
            val first = list.first()
            val m = matcher.findById(first.itemId) ?: continue
            val avgConf = list.map { it.confidence }.average().toFloat()
            // A2 数量：从最后帧对应格子 OCR 读取角标；失败回退到实例数
            var qty = list.size
            val frame = lastFrameForOcr
            if (frame != null && frame.width > 0 && frame.height > 0) {
                qty = GridOcr.readQuantity(frame)
            }
            _confirmed.add(ConfirmedItem(m.id, m.name, m.w, m.h, m.price, m.cat,
                first.region, avgConf, qty))
        }
        Log.i(TAG, "比对完成: ${_confirmed.size} 种物资 (合并 ${cells.size} 格 → ${merged.size} 实例)")
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

    /** 人工确认待确认项：选择候选物品ID → 转为已确认物资 */
    fun confirmPending(cellId: String, chosenId: String): Boolean {
        val p = _pendingItems.find { it.cellId == cellId } ?: return false
        _pendingItems.remove(p)
        val m = matcher.findById(chosenId) ?: return false
        rawItems.add(RawItem(chosenId, 1.0f, p.region, p.gx, p.gy))
        return true
    }
    fun discardPending(cellId: String) { _pendingItems.removeAll { it.cellId == cellId } }
    fun pendingNames(): List<Pair<String, List<String>>> = _pendingItems.map { p ->
        p.cellId to p.candidates.map { matcher.findById(it)?.name ?: it }
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
        lastFrameForOcr?.recycle(); lastFrameForOcr = bitmap.copy(Bitmap.Config.ARGB_8888, false)

        // 1) OCR 识别格数（每 N 帧做一次，省开销）
        if (frameCounter % 10 == 1L) {
            GridOcr.recognize(bitmap)?.let {
                _gridInfo = it
                Log.i(TAG, "OCR 格数: 背包 ${it.bagWidth}x${it.bagHeight} 保险箱 ${it.safeWidth}x${it.safeHeight}")
            }
            dynamicBagRegion = RegionDetector.detect(bitmap).normalized
        }

        // 2) 图标匹配扫描（背包区域 + 地面）
        val cols = _gridInfo.bagWidth.coerceAtLeast(1)
        val rows = _gridInfo.bagHeight.coerceAtLeast(1)
        val detections = iconScanner.scan(bitmap, cols, rows, dynamicBagRegion, LOW_CONF)
        for (d in detections) {
            if (d.confidence < matchThreshold) {
                val cands = matcher.matchTop(cropCell(bitmap, d), LOW_CONF, 3).map { it.item.id }
                _pendingItems.add(PendingItem("bag_${d.gridX}_${d.gridY}", cands, "bag",
                    d.gridX, d.gridY, d.confidence, d.itemId))
            } else rawItems.add(RawItem(d.itemId, d.confidence, "bag", d.gridX, d.gridY))
        }
        // 地面区域
        val groundDet = iconScanner.scan(bitmap, 6, 4, floatArrayOf(0f, 0.6f, 1f, 0.4f), LOW_CONF)
        for (d in groundDet) {
            if (d.confidence < matchThreshold) {
                val cands = matcher.matchTop(cropCell(bitmap, d), LOW_CONF, 3).map { it.item.id }
                _pendingItems.add(PendingItem("ground_${d.gridX}_${d.gridY}", cands, "ground",
                    d.gridX, d.gridY, d.confidence, d.itemId))
            } else rawItems.add(RawItem(d.itemId, d.confidence, "ground", d.gridX, d.gridY))
        }
        // 保险箱区域：画面右上小网格（安全箱通常独立区域）
        val sw = _gridInfo.safeWidth.coerceAtLeast(1)
        val sh = _gridInfo.safeHeight.coerceAtLeast(1)
        val safeDet = iconScanner.scan(bitmap, sw, sh, floatArrayOf(0.7f, 0.05f, 0.28f, 0.2f), LOW_CONF)
        for (d in safeDet) {
            if (d.confidence < matchThreshold) {
                val cands = matcher.matchTop(cropCell(bitmap, d), LOW_CONF, 3).map { it.item.id }
                _pendingItems.add(PendingItem("safe_${d.gridX}_${d.gridY}", cands, "safe",
                    d.gridX, d.gridY, d.confidence, d.itemId))
            } else rawItems.add(RawItem(d.itemId, d.confidence, "safe", d.gridX, d.gridY))
        }

        viewModel.noteFrame(detections.size + groundDet.size + safeDet.size, false) // 更新识别统计
        bitmap.recycle()
    }
}
