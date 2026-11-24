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
    private val db = DatabaseProvider.get(app)
    private var tracker = CoreBridge.Tracker()
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

    private fun trackerReset() { tracker = CoreBridge.Tracker(); refresh() }
    fun resetAll() { trackerReset(); _instances.value = emptyList(); _pending.value = emptyList()
        _recognitionStats.value = RecognitionStats(); _progress.value = 0; _valuation.value = null; _packResult.value = null }
}

private fun CoreBridge.Instance.toEntity(sid: String) = ItemInstanceEntity(
    instanceId, sid, itemTypeId, containerId, regionId, x, y, 0, phash, "",
    firstFrame, lastFrame, confidence, status, quantity, humanConfirmed
)
