package com.deltainventory.app.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deltainventory.app.data.*
import com.deltainventory.app.vision.VisionEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/** 盘点会话状态与业务逻辑 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx = app.applicationContext
    private val db = DatabaseProvider.get(app)
    private var tracker = CoreBridge.Tracker()
    var processor: ScanProcessor? = null
    /** 由 Activity 注入：发起屏幕采集授权 */
    var requestCaptureAuth: (() -> Unit)? = null
    /** 由 Activity 注入：停止屏幕采集 */
    var stopCapture: (() -> Unit)? = null
    private val _layoutResult = MutableStateFlow<ScanProcessor.LayoutResult?>(null)
    val layoutResult: StateFlow<ScanProcessor.LayoutResult?> = _layoutResult.asStateFlow()
    private val _confirmedItems = MutableStateFlow<List<ScanProcessor.ConfirmedItem>>(emptyList())
    val confirmedItems: StateFlow<List<ScanProcessor.ConfirmedItem>> = _confirmedItems.asStateFlow()
    private val _gridInfo = MutableStateFlow("未识别")
    val gridInfoText: StateFlow<String> = _gridInfo.asStateFlow()
    private val _pendingConfirm = MutableStateFlow<List<Pair<String, List<String>>>>(emptyList())
    val pendingConfirm: StateFlow<List<Pair<String, List<String>>>> = _pendingConfirm.asStateFlow()
    private val _phase = MutableStateFlow("idle") // idle/scanning/verified/laid_out
    val phase: StateFlow<String> = _phase.asStateFlow()
    private var sessionId: String? = null
    private var frameIndex = 0L

    private val _instances = MutableStateFlow<List<CoreBridge.Instance>>(emptyList())
    val instances: StateFlow<List<CoreBridge.Instance>> = _instances.asStateFlow()
    private val _pending = MutableStateFlow<List<CoreBridge.Instance>>(emptyList())
    val pending: StateFlow<List<CoreBridge.Instance>> = _pending.asStateFlow()
    private val _status = MutableStateFlow("idle")  // idle/running/paused/stopped
    val status: StateFlow<String> = _status.asStateFlow()
    private val _progress = MutableStateFlow(0L)
    val progress: StateFlow<Long> = _progress.asStateFlow()
    private val _recognitionStats = MutableStateFlow(RecognitionStats())
    val recognitionStats: StateFlow<RecognitionStats> = _recognitionStats.asStateFlow()
    private val _valuation = MutableStateFlow<ValuationView?>(null)
    val valuation: StateFlow<ValuationView?> = _valuation.asStateFlow()
    private val _packResult = MutableStateFlow<CoreBridge.PackResult?>(null)
    val packResult: StateFlow<CoreBridge.PackResult?> = _packResult.asStateFlow()

    data class RecognitionStats(val frames: Long = 0, val created: Long = 0, val matched: Long = 0,
                                val pending: Long = 0, val suppressed: Long = 0)
    data class ValuationView(val bagCents: Long, val groundCents: Long, val totalCents: Long,
                             val missing: Int, val stale: Int, val updatedAt: Long)

    fun startSession() {
        sessionId = "scan_${UUID.randomUUID()}"
        frameIndex = 0
        trackerReset()
        viewModelScope.launch {
            db.sessionDao().insert(ScanSessionEntity(sessionId!!, System.currentTimeMillis()))
        }
        _status.value = "running"
    }

    fun pause() { if (_status.value == "running") _status.value = "paused" }
    fun resume() { if (_status.value == "paused") _status.value = "running" }

    fun stopSession() {
        sessionId?.let { sid ->
            viewModelScope.launch {
                db.sessionDao().end(sid, System.currentTimeMillis(), "completed")
                tracker.active().forEach { inst ->
                    db.instanceDao().upsert(inst.toEntity(sid))
                }
            }
        }
        _status.value = "stopped"
    }

    /** 接收一帧观测（由 CaptureService 的帧经 VisionEngine 处理后调用） */
    fun onFrame(observations: List<CoreBridge.Observation>) {
        if (_status.value != "running") return
        frameIndex++
        _progress.value = frameIndex
        val results = tracker.processFrame(frameIndex, observations)
        val s = _recognitionStats.value
        _recognitionStats.value = s.copy(
            frames = frameIndex,
            created = s.created + results.count { it.action.startsWith("Created") },
            matched = s.matched + results.count { it.action == "Matched" },
            pending = tracker.pending().size.toLong()
        )
        refresh()
        sessionId?.let { sid ->
            viewModelScope.launch {
                db.frameDao().insert(FrameRecordEntity(sessionId = sid, frameIndex = frameIndex, capturedAt = System.currentTimeMillis()))
                results.forEach { r ->
                    db.eventDao().insert(RecognitionEventEntity(
                        sessionId = sid, frameIndex = frameIndex, eventType = r.action,
                        instanceId = r.matchedId ?: r.createdId ?: "", itemTypeId = "",
                        confidence = if (r.needsReview) 0.5f else 0.9f))
                }
            }
        }
    }

    private fun refresh() {
        _instances.value = tracker.active()
        _pending.value = tracker.pending()
    }

    fun confirm(id: String, type: String? = null, qty: Int? = null) {
        tracker.confirm(id, type, qty)
        refresh()
        sessionId?.let { sid -> viewModelScope.launch {
            val inst = tracker.active().firstOrNull { it.instanceId == id }
            inst?.let { db.instanceDao().confirm(id, it.itemTypeId, it.quantity, it.x, it.y) }
        } }
    }

    fun discard(id: String) {
        tracker.discard(id); refresh()
        sessionId?.let { sid -> viewModelScope.launch { db.instanceDao().discard(id) } }
    }

    fun merge(a: String, b: String) { if (tracker.merge(a, b)) refresh() }
    fun split(id: String, n: Int): String? { val r = tracker.split(id, n); refresh(); return r }

    /** 价值统计：使用离线物品表 + 本地价格 */
    fun computeValuation(nowMs: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            val prices = mutableMapOf<String, PriceRecord>()
            tracker.active().forEach { inst ->
                db.priceDao().latest(inst.itemTypeId)?.let {
                    prices[inst.itemTypeId] = PriceRecord(it.itemId, it.valueCents, it.source, it.updatedAt, it.validDays)
                }
            }
            // 物品基础价值来自导入的 item-database.json（由 DatabaseScreen 导入）
            val types = mutableMapOf<String, ItemType>()
            db.itemDao().all().first().forEach {
                types[it.itemId] = ItemType(it.itemId, it.name, it.width, it.height, it.baseValueCents, it.category)
            }
            val report = valueInstances(tracker.active(), types, prices, nowMs)
            _valuation.value = ValuationView(report.bagValueCents, report.groundValueCents,
                report.totalValueCents, report.missingPriceTypes, report.stalePriceTypes,
                prices.values.maxOfOrNull { it.updatedAtMs } ?: 0L)
        }
    }

    /** 二维背包优化 */
    fun optimize(gridW: Int, gridH: Int) {
        viewModelScope.launch {
            val types = db.itemDao().all().first().associateBy { it.itemId }
            val items = tracker.active().filter { it.containerId == "bag" }.map { inst ->
                val t = types[inst.itemTypeId]
                CoreBridge.PackItem(inst.itemTypeId, t?.width ?: 1, t?.height ?: 1,
                    (t?.baseValueCents ?: 0L) * inst.quantity, 1, true)
            }
            val result = CoreBridge.optimize(gridW, gridH, items)
            _packResult.value = result
        }
    }

    fun getDetail(id: String): CoreBridge.Instance? = tracker.active().firstOrNull { it.instanceId == id }

    val history = db.sessionDao().all()

    // 可调识别参数
    var matchThreshold = 0.55f
        private set
    var hashMaxDist = 20
        private set
    fun setMatchThreshold(v: Float) { matchThreshold = v; processor?.matchThreshold = v }
    fun setHashDist(v: Int) { hashMaxDist = v; processor?.hashMaxDist = v; processor?.setHashDist(v) }

    /** 从 assets 导入全量物品库到 Room */
    fun importDatabase(onDone: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val json = appCtx.assets.open("item-database.json").use { it.readBytes().toString(Charsets.UTF_8) }
                val arr = org.json.JSONObject(json).getJSONArray("items")
                val entities = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ItemEntity(o.getString("itemId"), o.getString("name"), o.optString("shortName").ifEmpty { null },
                        o.getInt("width"), o.getInt("height"), o.optString("category"),
                        o.opt("baseValueCents")?.let { if (it is Number) it.toLong() else null }, o.optString("iconPath").ifEmpty { null })
                }
                db.itemDao().upsertAll(entities)
                // 同时导入价格快照（DFPB v6 真实价格）
                val priceRows = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val v = o.optLong("displayValueCents", 0L)
                    if (v <= 0) null else ItemPriceEntity(
                        itemId = o.getString("itemId"), valueCents = v,
                        source = "DFPB_v6", updatedAt = 1763308800000L, // 2026-11-17
                        version = 6, validDays = 30
                    )
                }
                priceRows.forEach { db.priceDao().insert(it) }
                onDone("导入 ${entities.size} 件物品、${priceRows.size} 条价格成功")
            } catch (e: Exception) { onDone("导入失败: ${e.message}") }
        }
    }

    fun backupDatabase(destPath: String, onDone: (String) -> Unit) {
        try { DatabaseProvider.backup(appCtx, java.io.File(destPath)); onDone("备份成功") }
        catch (e: Exception) { onDone("备份失败: ${e.message}") }
    }

    fun restoreDatabase(srcPath: String, onDone: (String) -> Unit) {
        try { DatabaseProvider.restore(appCtx, java.io.File(srcPath)); onDone("恢复成功，请重启应用") }
        catch (e: Exception) { onDone("恢复失败: ${e.message}") }
    }

    /** 由 ScanProcessor 每帧调用，更新识别统计 */
    fun noteFrame(created: Int, skipped: Boolean) {
        val st = _recognitionStats.value
        _recognitionStats.value = st.copy(
            frames = st.frames + 1,
            created = st.created + created,
            pending = processor?.pendingItems?.size?.toLong() ?: st.pending
        )
        _progress.value = st.frames + 1
    }

    /** 三步流程：1 开始识别全部物资 */
    fun startRecognition() {
        if (requestCaptureAuth != null) {
            requestCaptureAuth!!.invoke()   // 授权成功后 Activity 调 onCaptureAuthorized()
        } else {
            processor?.startRecognition()
            _phase.value = "scanning"; _status.value = "running"
        }
    }
    /** 授权成功后由 Activity 调用 */
    fun onCaptureAuthorized() {
        processor?.startRecognition()
        _phase.value = "scanning"; _status.value = "running"
    }

    /** 三步流程：2 结束并比对数据库 */
    fun finishAndVerify() {
        stopCapture?.invoke()
        viewModelScope.launch {
            var list = processor?.finishAndVerify() ?: emptyList()
            // A3 价格走 Room 数据库：用最新价格与更新时间/来源覆盖快照价
            list = list.map { c ->
                val p = db.priceDao().latest(c.id)
                if (p != null) c.copy(price = p.valueCents) else c
            }
            _confirmedItems.value = list
            _pendingConfirm.value = processor?.pendingNames() ?: emptyList()
            // 持久化会话与识别实例
            val sid = "scan_${System.currentTimeMillis()}"
            sessionId = sid
            viewModelScope.launch {
                db.sessionDao().insert(ScanSessionEntity(sid, System.currentTimeMillis()))
                list.forEach { c ->
                    db.instanceDao().upsert(ItemInstanceEntity(
                        instanceId = "${sid}_${c.id}_${c.region}", sessionId = sid,
                        itemTypeId = c.id, containerId = c.region, regionId = c.region,
                        posX = 0, posY = 0, viewportOffsetY = 0, phash = 0, colorHist = "",
                        firstSeenFrame = 0, lastSeenFrame = _progress.value,
                        confidence = c.confidence, trackingStatus = "Confirmed", quantity = c.quantity,
                        humanConfirmed = false
                    ))
                }
            }
            val g = processor?.gridInfo
            _gridInfo.value = if (g != null && g.bagWidth > 0) "背包 ${g.bagWidth}x${g.bagHeight} · 保险箱 ${g.safeWidth}x${g.safeHeight}" else "未识别到格数(默认3x3)"
            _phase.value = "verified"
            _status.value = "stopped"
        }
    }

    fun confirmPending(cellId: String, chosenId: String) {
        processor?.confirmPending(cellId, chosenId)
        _pendingConfirm.value = processor?.pendingNames() ?: emptyList()
    }
    fun discardPending(cellId: String) {
        processor?.discardPending(cellId)
        _pendingConfirm.value = processor?.pendingNames() ?: emptyList()
    }

    /** 三步流程：3 输出完整摆放策略 */
    fun buildLayout() {
        viewModelScope.launch {
            val result = processor?.buildLayout()
            _layoutResult.value = result
            _phase.value = "laid_out"
            sessionId?.let { sid ->
                if (result != null) viewModelScope.launch {
                    db.optimizationDao().insert(OptimizationResultEntity(
                        sessionId = sid, algorithm = result.bag.algorithm,
                        gridWidth = result.bag.gridW, gridHeight = result.bag.gridH,
                        resultJson = result.bag.placed.map { "${it.id}@${it.x},${it.y}" }.joinToString(";"),
                        totalValueCents = result.bag.totalValueCents + result.safe.totalValueCents,
                        placedCount = result.bag.placed.size + result.safe.placed.size,
                        unplacedCount = result.bag.unplaced.size,
                        elapsedMs = result.bag.elapsedMs + result.safe.elapsedMs
                    ))
                }
            }
        }
    }

    private fun trackerReset() { tracker = CoreBridge.Tracker(); refresh() }
    fun resetAll() { trackerReset(); _instances.value = emptyList(); _pending.value = emptyList()
        _recognitionStats.value = RecognitionStats(); _progress.value = 0; _valuation.value = null; _packResult.value = null }
}

private fun CoreBridge.Instance.toEntity(sid: String) = ItemInstanceEntity(
    instanceId, sid, itemTypeId, containerId, regionId, x, y, 0, phash, "",
    firstFrame, lastFrame, confidence, status, quantity, humanConfirmed
)
