package com.deltainventory.app

import org.junit.Test
import org.junit.Assert.*
import com.deltainventory.app.domain.ClusterMerger
import com.deltainventory.app.vision.GridOcr

class FlowLogicTest {

    private fun cell(id: String, r: String, x: Int, y: Int, c: Float = 0.9f) =
        ClusterMerger.Cell(id, r, x, y, c)

    @Test fun singleCellItem() {
        val m = ClusterMerger.merge(listOf(cell("A","bag",1,1))) { 1 to 1 }
        assertEquals(1, m.size); assertEquals(1, m[0].gridCount)
    }

    @Test fun twoByTwoItemMergesFourCells() {
        val cells = listOf(cell("A","bag",1,1), cell("A","bag",2,1),
                           cell("A","bag",1,2), cell("A","bag",2,2))
        val m = ClusterMerger.merge(cells) { 2 to 2 }
        assertEquals(1, m.size); assertEquals(4, m[0].gridCount)
    }

    @Test fun twoSeparateOneCellItemsStaySeparate() {
        val cells = listOf(cell("A","bag",1,1), cell("A","bag",5,5))
        val m = ClusterMerger.merge(cells) { 1 to 1 }
        assertEquals(2, m.size)
    }

    @Test fun sameIdDifferentRegionNotMerged() {
        val cells = listOf(cell("A","bag",1,1), cell("A","ground",2,1))
        val m = ClusterMerger.merge(cells) { 2 to 2 }
        assertEquals("不同区域不得合并", 2, m.size)
    }

    @Test fun incompleteClusterKeepsActualCount() {
        val cells = listOf(cell("A","bag",1,1), cell("A","bag",2,1)) // 只有2格但物品需4格
        val m = ClusterMerger.merge(cells) { 2 to 2 }
        assertEquals(1, m.size); assertEquals(2, m[0].gridCount)
    }

    @Test fun confidenceRouting() {
        assertEquals(ClusterMerger.Route.CONFIRM, ClusterMerger.route(0.8f, 0.35f, 0.55f))
        assertEquals(ClusterMerger.Route.PENDING, ClusterMerger.route(0.45f, 0.35f, 0.55f))
        assertEquals(ClusterMerger.Route.DROP, ClusterMerger.route(0.2f, 0.35f, 0.55f))
    }

    @Test fun ocrParsesGridNotation() {
        val g = GridOcr.parseGrid("背包 10x6 安全箱 3x3")
        assertNotNull(g); assertEquals(10, g!!.bagWidth); assertEquals(6, g.bagHeight)
        assertEquals(3, g.safeWidth); assertEquals(3, g.safeHeight)
    }

    @Test fun ocrParsesChineseMultiplicationSign() {
        val g = GridOcr.parseGrid("BACKPACK 8×5")
        assertNotNull(g); assertEquals(8, g!!.bagWidth); assertEquals(5, g.bagHeight)
        assertEquals(3, g.safeWidth) // 默认
    }

    @Test fun ocrNoMatchReturnsNull() {
        assertNull(GridOcr.parseGrid("没有格数信息"))
    }
}
