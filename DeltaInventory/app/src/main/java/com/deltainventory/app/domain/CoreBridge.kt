package com.deltainventory.app.domain

import android.util.Log
import com.deltainventory.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Rust 核心 JNI 桥接。
 * Native 库由 rust-core 交叉编译为 libdi_core.so。
 * 若 native 不可用，降级为 Kotlin 纯实现（保证功能可用，非模拟数据）。
 */
object CoreBridge {
    private const val TAG = "CoreBridge"
    private var loaded = false

    init {
        try {
            System.loadLibrary("di_core")
            loaded = true
            Log.i(TAG, "native di_core loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "native unavailable, using Kotlin fallback: ${e.message}")
        }
    }

    external fun nativeVersion(): String
    external fun nativeOptimize(gridW: Int, gridH: Int, itemsJson: String): String

    /** 视觉观测 */
    data class Observation(
        val observationId: String,
        val itemTypeId: String,
        val containerId: String,
        val regionId: String,
        val x: Int, val y: Int,
        val phash: Long,
        val frameIndex: Long,
        val confidence: Float,
        val quantity: Int = 1
    )

    /** 实例账本条目 */
    data class Instance(
        val instanceId: String, val itemTypeId: String, val containerId: String,
        val regionId: String, val x: Int, val y: Int,
        val phash: Long, val firstFrame: Long, val lastFrame: Long,
        val confidence: Float, val status: String, val quantity: Int,
        val humanConfirmed: Boolean = false
    )

    /**
     * 跨帧追踪：Kotlin 实现，语义与 Rust tracker 一致。
     * 严格匹配：类型+区域+容器+位置+视觉签名+时间连续。
     */
    class Tracker(private val config: Config = Config()) {
        data class Config(
            val maxPositionDeltaCells: Int = 1,
            val maxPhashDistance: Int = 8,
            val hiddenAfterFrames: Long = 3,
            val reviewThreshold: Float = 0.75f
        )
        data class Result(
            val matchedId: String?, val createdId: String?,
            val needsReview: Boolean, val action: String
        )

        private val ledger = LinkedHashMap<String, Instance>()
        private var seq = 0L

        fun processFrame(frame: Long, observations: List<Observation>): List<Result> {
            val claimed = HashSet<String>()
            val results = mutableListOf<Result>()
            for (obs in observations) {
                val candidates = ledger.values
                    .filter { it.instanceId !in claimed && it.status != "Discarded" && possible(it, obs, frame) }
                    .sortedBy { hamming(it.phash, obs.phash) * 10 +
                            (kotlin.math.abs(it.x - obs.x) + kotlin.math.abs(it.y - obs.y)) }

                if (candidates.isNotEmpty()) {
                    val best = candidates.first()
                    val updated = best.copy(
                        lastFrame = frame,
                        confidence = (best.confidence * 0.7f + obs.confidence * 0.3f).coerceAtMost(1f),
                        x = obs.x, y = obs.y,
                        quantity = if (obs.quantity > best.quantity || best.humanConfirmed) obs.quantity.coerceAtLeast(1) else best.quantity,
                        status = if (best.status == "TemporarilyHidden")
                            (if (obs.confidence >= config.reviewThreshold) "Confirmed" else "PendingReview")
                        else best.status
                    )
                    ledger[best.instanceId] = updated
                    claimed.add(best.instanceId)
                    results.add(Result(best.instanceId, null, updated.status == "PendingReview", "Matched"))
                } else {
                    seq++
                    val id = "inst_${String.format("%06d", seq)}"
                    val status = if (obs.confidence >= config.reviewThreshold) "Confirmed" else "PendingReview"
                    ledger[id] = Instance(id, obs.itemTypeId, obs.containerId, obs.regionId, obs.x, obs.y,
                        obs.phash, frame, frame, obs.confidence, status, obs.quantity.coerceAtLeast(1))
                    claimed.add(id)
                    results.add(Result(null, id, status == "PendingReview",
                        if (status == "PendingReview") "CreatedPendingReview" else "Created"))
                }
            }
            // 帧末：暂时不可见
            for ((id, inst) in ledger) {
                if (id !in claimed && inst.status != "Discarded" && frame - inst.lastFrame >= config.hiddenAfterFrames) {
                    ledger[id] = inst.copy(status = "TemporarilyHidden")
                }
            }
            return results
        }

        private fun possible(i: Instance, o: Observation, frame: Long): Boolean {
            if (i.itemTypeId != o.itemTypeId || i.regionId != o.regionId || i.containerId != o.containerId) return false
            if (kotlin.math.abs(i.x - o.x) > config.maxPositionDeltaCells) return false
            if (kotlin.math.abs(i.y - o.y) > config.maxPositionDeltaCells) return false
            if (hamming(i.phash, o.phash) > config.maxPhashDistance) return false
            return frame >= i.lastFrame
        }

        fun active() = ledger.values.filter { it.status != "Discarded" }
        fun pending() = ledger.values.filter { it.status == "PendingReview" }

        fun confirm(id: String, type: String? = null, qty: Int? = null): Boolean {
            val i = ledger[id] ?: return false
            ledger[id] = i.copy(itemTypeId = type ?: i.itemTypeId, quantity = qty ?: i.quantity,
                confidence = 1f, status = "Confirmed", humanConfirmed = true)
            return true
        }

        fun discard(id: String) { ledger[id]?.let { ledger[id] = it.copy(status = "Discarded") } }

        fun merge(a: String, b: String): Boolean {
            val ia = ledger[a] ?: return false
            val ib = ledger[b] ?: return false
            if (ia.itemTypeId != ib.itemTypeId || ia.containerId != ib.containerId) return false
            ledger[a] = ia.copy(quantity = ia.quantity + ib.quantity, confidence = 1f,
                status = "Confirmed", humanConfirmed = true,
                firstFrame = minOf(ia.firstFrame, ib.firstFrame), lastFrame = maxOf(ia.lastFrame, ib.lastFrame))
            ledger.remove(b)
            return true
        }

        fun split(id: String, n: Int): String? {
            val src = ledger[id] ?: return null
            if (n <= 0 || n >= src.quantity) return null
            ledger[id] = src.copy(quantity = src.quantity - n)
            val newId = "inst_${String.format("%06d", ++seq)}"
            ledger[newId] = src.copy(instanceId = newId, quantity = n, x = src.x + 1,
                confidence = 1f, humanConfirmed = true, status = "Confirmed")
            return newId
        }
    }

    /** Rust 装箱优化；native 不可用时用 Kotlin MaxRects 实现 */
    data class PackItem(val id: String, val width: Int, val height: Int,
                        val valueCents: Long, val quantity: Int, val rotatable: Boolean)
    data class Placed(val id: String, val x: Int, val y: Int, val w: Int, val h: Int,
                      val rotated: Boolean, val valueCents: Long)
    data class PackResult(val algorithm: String, val placed: List<Placed>,
                          val unplaced: Map<String, Int>, val totalValueCents: Long,
                          val occupiedCells: Int, val freeCells: Int, val elapsedMs: Long)

    suspend fun optimize(gridW: Int, gridH: Int, items: List<PackItem>): PackResult = withContext(Dispatchers.Default) {
        if (loaded) {
            try {
                val json = StringBuilder("[")
                items.forEachIndexed { i, it ->
                    if (i > 0) json.append(",")
                    json.append("""{"id":"${it.id}","width":${it.width},"height":${it.height},"value_cents":${it.valueCents},"quantity":${it.quantity},"rotatable":${it.rotatable}}""")
                }
                json.append("]")
                val r = JSONObject(nativeOptimize(gridW, gridH, json.toString()))
                // 解析 native 返回（与 PackResult 一致）
                val placed = mutableListOf<Placed>()
                val pa = r.getJSONArray("placed")
                for (i in 0 until pa.length()) {
                    val p = pa.getJSONObject(i)
                    placed.add(Placed(p.getString("id"), p.getInt("x"), p.getInt("y"), p.getInt("w"), p.getInt("h"),
                        p.getBoolean("rotated"), p.getLong("value_cents")))
                }
                return@withContext PackResult(r.getString("algorithm"), placed, emptyMap(),
                    r.getLong("total_value_cents"), r.getInt("occupied_cells"), r.getInt("free_cells"), r.getLong("elapsed_ms"))
            } catch (e: Exception) { Log.w(TAG, "native optimize failed: ${e.message}") }
        }
        maxRectsKotlin(gridW, gridH, items)
    }

    private fun maxRectsKotlin(gw: Int, gh: Int, items: List<PackItem>): PackResult {
        val start = System.currentTimeMillis()
        data class F(var x: Int, var y: Int, var w: Int, var h: Int)
        var free = mutableListOf(F(0, 0, gw, gh))
        val placed = mutableListOf<Placed>()
        val unplaced = mutableMapOf<String, Int>()
        val sorted = items.sortedByDescending { it.valueCents.toDouble() / (it.width * it.height).coerceAtLeast(1) }
        for (item in sorted) repeat(item.quantity) {
            var best: Int? = null; var bestShort = Int.MAX_VALUE; var bestRot = false
            for ((idx, f) in free.withIndex()) {
                val orientations = if (item.rotatable && item.width != item.height)
                    listOf(item.width to item.height to false, item.height to item.width to true)
                else listOf(item.width to item.height to false)
                for ((wh, rot) in orientations) {
                    val (w, h) = wh
                    if (f.w >= w && f.h >= h) {
                        val short = minOf(f.w - w, f.h - h)
                        if (short < bestShort) { bestShort = short; best = idx; bestRot = rot }
                    }
                }
            }
            if (best == null) { unplaced[item.id] = (unplaced[item.id] ?: 0) + 1; return@repeat }
            val f = free[best!!]
            val (w, h) = if (bestRot) item.height to item.width else item.width to item.height
            placed.add(Placed(item.id, f.x, f.y, w, h, bestRot, item.valueCents))
            val used = F(f.x, f.y, w, h)
            val next = mutableListOf<F>()
            for (fr in free) {
                val ix = maxOf(fr.x, used.x); val iy = maxOf(fr.y, used.y)
                val ax = minOf(fr.x + fr.w, used.x + used.w); val ay = minOf(fr.y + fr.h, used.y + used.h)
                if (ax <= ix || ay <= iy) { next.add(fr); continue }
                if (used.y > fr.y) next.add(F(fr.x, fr.y, fr.w, used.y - fr.y))
                if (used.y + used.h < fr.y + fr.h) next.add(F(fr.x, used.y + used.h, fr.w, fr.y + fr.h - (used.y + used.h)))
                if (used.x > fr.x) next.add(F(fr.x, fr.y, used.x - fr.x, fr.h))
                if (used.x + used.w < fr.x + fr.w) next.add(F(used.x + used.w, fr.y, fr.x + fr.w - (used.x + used.w), fr.h))
            }
            free = next
        }
        val occupied = placed.sumOf { it.w * it.h }
        return PackResult("maxrects_kotlin", placed, unplaced, placed.sumOf { it.valueCents }, occupied,
            gw * gh - occupied, System.currentTimeMillis() - start)
    }

    private fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
