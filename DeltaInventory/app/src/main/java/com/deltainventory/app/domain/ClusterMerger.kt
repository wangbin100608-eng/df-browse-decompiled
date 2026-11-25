package com.deltainventory.app.domain

/**
 * 纯逻辑：跨格物品合并与数量判定（不依赖 Android，可 JVM 单测）。
 * 输入每格识别结果（已按格去重），按物品已知尺寸把相邻同 ID 格子合并为一个实例。
 */
object ClusterMerger {
    data class Cell(val itemId: String, val region: String, val gx: Int, val gy: Int, val confidence: Float)
    data class Merged(val itemId: String, val region: String, val gridCount: Int, val confidence: Float)

    /**
     * @param cells 每格一条（同格多帧已去重）
     * @param sizeOf itemId -> (w,h)
     * @return 合并后的实例列表
     */
    fun merge(cells: List<Cell>, sizeOf: (String) -> Pair<Int, Int>): List<Merged> {
        val consumed = HashSet<Int>()
        val result = mutableListOf<Merged>()
        for (i in cells.indices) {
            if (i in consumed) continue
            val cell = cells[i]
            val (w, h) = sizeOf(cell.itemId)
            val needed = (w * h).coerceAtLeast(1)
            if (needed == 1) {
                result.add(Merged(cell.itemId, cell.region, 1, cell.confidence)); consumed.add(i); continue
            }
            val cluster = mutableListOf(cell)
            val clusterIdx = mutableListOf(i)
            consumed.add(i)
            var p = 0
            while (p < clusterIdx.size && cluster.size < needed) {
                val cur = cells[clusterIdx[p++]]
                for (j in cells.indices) {
                    if (j in consumed || cells[j].itemId != cell.itemId || cells[j].region != cell.region) continue
                    val dist = kotlin.math.abs(cells[j].gx - cur.gx) + kotlin.math.abs(cells[j].gy - cur.gy)
                    if (dist == 1 && cluster.size < needed) {
                        cluster.add(cells[j]); clusterIdx.add(j); consumed.add(j)
                    }
                }
            }
            result.add(Merged(cell.itemId, cell.region, cluster.size, cluster.maxOf { it.confidence }))
        }
        return result
    }

    /** 置信度分流：>=high 确认；low..high 待确认；<low 丢弃 */
    enum class Route { CONFIRM, PENDING, DROP }
    fun route(confidence: Float, low: Float, high: Float): Route = when {
        confidence >= high -> Route.CONFIRM
        confidence >= low -> Route.PENDING
        else -> Route.DROP
    }
}
