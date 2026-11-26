package com.deltainventory.app.vision

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图标匹配扫描引擎：把游戏画面按网格切成小图，逐个用 IconMatcher 匹配真实物品图标。
 * 替代未训练 ONNX 模型，输出每个格子的物品识别结果。
 */
class IconScanEngine(private val matcher: IconMatcher) {

    data class CellDetection(
        val gridX: Int, val gridY: Int,
        val itemId: String, val itemName: String,
        val width: Int, val height: Int,
        val price: Long, val category: String,
        val confidence: Float,
        val centerPixX: Float, val centerPixY: Float
    )

    /**
     * 扫描画面。
     * @param bitmap 工作分辨率画面
     * @param gridCols/gridRows 背包网格数（从 OCR 得到）
     * @param region 扫描区域在画面中的归一化矩形 [x,y,w,h]
     */
    suspend fun scan(
        bitmap: Bitmap,
        gridCols: Int, gridRows: Int,
        region: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
        minScore: Float = 0.55f
    ): List<CellDetection> = withContext(Dispatchers.Default) {
        val results = mutableListOf<CellDetection>()
        val rx = (region[0] * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val ry = (region[1] * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val rw = (region[2] * bitmap.width).toInt().coerceAtLeast(1)
        val rh = (region[3] * bitmap.height).toInt().coerceAtLeast(1)
        val cw = (rw / gridCols).coerceAtLeast(8)
        val ch = (rh / gridRows).coerceAtLeast(8)

        for (gy in 0 until gridRows) {
            for (gx in 0 until gridCols) {
                val x = (rx + gx * cw).coerceAtMost(bitmap.width - 1)
                val y = (ry + gy * ch).coerceAtMost(bitmap.height - 1)
                val w = cw.coerceAtMost(bitmap.width - x)
                val h = ch.coerceAtMost(bitmap.height - y)
                if (w < 8 || h < 8) continue
                val m = matcher.matchRegion(bitmap, x, y, w, h, minScore)
                if (m != null) {
                    results.add(CellDetection(
                        gridX = gx, gridY = gy,
                        itemId = m.item.id, itemName = m.item.name,
                        width = m.item.w, height = m.item.h,
                        price = m.item.price, category = m.item.cat,
                        confidence = m.score,
                        centerPixX = (x + w / 2f) / bitmap.width,
                        centerPixY = (y + h / 2f) / bitmap.height
                    ))
                }
            }
        }
        results
    }
}
