package com.deltainventory.app.vision

import android.graphics.Bitmap

/**
 * 从游戏画面动态定位背包/地面/保险箱区域（替代硬编码比例）。
 * 原理：对画面做行列边缘密度投影，网格区域有规律的强弱交替（格子边框），
 * 边缘密度显著高于背景。用滑动窗口找边缘密度最高的连续带，即为网格区域。
 *
 * 纯 Kotlin 实现，核心投影逻辑提取为纯函数便于单测。
 */
object RegionDetector {

    data class Region(val x: Int, val y: Int, val w: Int, val h: Int,
                      val normalized: FloatArray)

    /**
     * 核心纯函数：从边缘密度数组找最显著的连续带。
     * @param density 每行（或每列）的边缘密度
     * @param minLen 最小带长度
     * @return [start, end) 索引对
     */
    fun findBand(density: FloatArray, minLen: Int): IntArray {
        if (density.isEmpty()) return intArrayOf(0, 0)
        val avg = density.average().toFloat()
        var bestStart = 0; var bestEnd = minOf(minLen, density.size); var bestScore = -1f
        for (start in 0..(density.size - minLen)) {
            var sum = 0f
            val end = minOf(start + minLen, density.size)
            for (i in start until end) sum += density[i]
            if (sum > bestScore) { bestScore = sum; bestStart = start; bestEnd = end }
        }
        // 从 best 带向两侧扩展（密度高于均值的连续段）
        var s = bestStart; while (s > 0 && density[s-1] > avg * 0.8f) s--
        var e = bestEnd; while (e < density.size && density[e] > avg * 0.8f) e++
        return intArrayOf(s, e)
    }

    /** 计算每行边缘密度（相邻像素灰度差之和 / 宽度） */
    fun rowEdgeDensity(bmp: Bitmap, step: Int = 4): FloatArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val density = FloatArray(h)
        for (y in 0 until h) {
            var sum = 0
            var count = 0
            for (x in step until w step step) {
                val c1 = px[y*w+x]; val c2 = px[y*w+x-step]
                sum += kotlin.math.abs((c1 and 0xFF) - (c2 and 0xFF))
                count++
            }
            density[y] = if (count > 0) sum.toFloat()/count else 0f
        }
        return density
    }

    /**
     * 检测画面中的主要网格区域（背包通常占中上部大块）。
     */
    fun detect(bmp: Bitmap): Region {
        val h = bmp.height; val w = bmp.width
        val rowD = rowEdgeDensity(bmp)
        val band = findBand(rowD, minOf(h/6, 40).coerceAtLeast(4))
        val y0 = band[0]; val y1 = band[1].coerceAtMost(h)
        return Region(0, y0, w, (y1 - y0).coerceAtLeast(1),
            floatArrayOf(0f, y0.toFloat()/h, 1f, (y1-y0).toFloat()/h))
    }
}
