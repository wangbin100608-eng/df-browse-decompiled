package com.deltainventory.app.domain

import com.deltainventory.app.data.ItemEntity
import com.deltainventory.app.data.ItemPriceEntity

/** Kotlin 侧价值类型（与 Rust 对应） */
data class PriceRecord(val itemTypeId: String, val valueCents: Long, val source: String,
                       val updatedAtMs: Long, val validDays: Int)
data class ItemType(val id: String, val name: String, val width: Int, val height: Int,
                    val baseValueCents: Long?, val category: String)
enum class PriceFreshness { Fresh, Stale, Missing }
data class ValuationLine(val instanceId: String, val itemTypeId: String, val name: String,
                         val quantity: Int, val unitValueCents: Long, val lineValueCents: Long,
                         val containerId: String, val freshness: PriceFreshness)
data class ValuationReport(val lines: List<ValuationLine>, val bagValueCents: Long,
                           val groundValueCents: Long, val otherValueCents: Long,
                           val totalValueCents: Long, val missingPriceTypes: Int,
                           val stalePriceTypes: Int)

/** 价值统计（与 Rust valuation.rs 语义一致） */
fun valueInstances(instances: List<CoreBridge.Instance>, types: Map<String, ItemType>,
                   prices: Map<String, PriceRecord>, nowMs: Long): ValuationReport {
    val lines = mutableListOf<ValuationLine>()
    var bag = 0L; var ground = 0L; var other = 0L; var missing = 0; var stale = 0
    for (inst in instances) {
        if (inst.status != "Confirmed" && !inst.humanConfirmed) continue
        val t = types[inst.itemTypeId]
        val (unit, fresh) = when (val p = prices[inst.itemTypeId]) {
            null -> { missing++; (t?.baseValueCents ?: 0L) to PriceFreshness.Missing }
            else -> {
                val ageDays = ((nowMs - p.updatedAtMs).coerceAtLeast(0) / 86_400_000)
                if (ageDays > p.validDays) { stale++; p.valueCents to PriceFreshness.Stale }
                else p.valueCents to PriceFreshness.Fresh
            }
        }
        val line = unit * inst.quantity
        lines.add(ValuationLine(inst.instanceId, inst.itemTypeId, t?.name ?: "unknown",
            inst.quantity, unit, line, inst.containerId, fresh))
        when (inst.containerId) {
            "bag" -> bag += line
            "ground" -> ground += line
            else -> other += line
        }
    }
    return ValuationReport(lines, bag, ground, other, bag + ground + other, missing, stale)
}
