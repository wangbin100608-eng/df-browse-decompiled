package com.deltainventory.app.vision

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject

/**
 * 基于真实物品图标特征的本地识别器（替代未训练 ONNX 模型）。
 * 特征：64bit dHash + 16桶颜色直方图，与 item-features.json（1663 件真实图标）比对。
 * 全本地，无云端。
 */
class IconMatcher private constructor(ctx: Context) {
    data class ItemFeature(
        val id: String, val name: String, val w: Int, val h: Int,
        val cat: String, val price: Long, val phash: Long, val hist: FloatArray
    )
    data class Match(val item: ItemFeature, val score: Float)

    private val features = mutableListOf<ItemFeature>()
    val count get() = features.size
    /** 可运行时调整的最大汉明距离（设置页） */
    var maxHashDist: Int = MAX_HASH_DIST

    init {
        try {
            val json = ctx.assets.open("item-features.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONObject(json).getJSONArray("features")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val h = FloatArray(16)
                val ja = o.getJSONArray("hist")
                for (k in 0 until 16) h[k] = ja.getDouble(k).toFloat()
                features.add(ItemFeature(
                    o.getString("id"), o.getString("name"), o.getInt("w"), o.getInt("h"),
                    o.getString("cat"), o.getLong("price"), o.getString("ph").toLong(), h
                ))
            }
        } catch (e: Exception) { android.util.Log.e("IconMatcher", "特征库加载失败: ${e.message}") }
    }

    companion object {
        @Volatile private var instance: IconMatcher? = null
        fun get(ctx: Context): IconMatcher = instance ?: synchronized(this) {
            instance ?: IconMatcher(ctx.applicationContext).also { instance = it }
        }
        const val MAX_HASH_DIST = 20   // 放宽以容忍游戏截图干扰
    }

    /** 计算输入图的 dHash（9x8 灰度） */
    fun phash(bmp: Bitmap): Long {
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 9, 8, true)
        val px = IntArray(72); small.getPixels(px, 0, 9, 0, 0, 9, 8)
        small.recycle()
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            h = (h shl 1) or (if (px[y * 9 + x] and 0xFF > px[y * 9 + x + 1] and 0xFF) 1L else 0L)
        }
        return h
    }

    /** 中心裁剪 70% 后计算 dHash，去除格子边框/高亮干扰 */
    fun phashCenter(bmp: Bitmap): Long {
        val w = (bmp.width * 0.7f).toInt().coerceAtLeast(9)
        val h = (bmp.height * 0.7f).toInt().coerceAtLeast(8)
        val x = (bmp.width - w) / 2; val y = (bmp.height - h) / 2
        val crop = android.graphics.Bitmap.createBitmap(bmp, x, y, w, h)
        val small = android.graphics.Bitmap.createScaledBitmap(crop, 9, 8, true)
        crop.recycle()
        val px = IntArray(72); small.getPixels(px, 0, 9, 0, 0, 9, 8); small.recycle()
        var hash = 0L
        for (yy in 0 until 8) for (xx in 0 until 8)
            hash = (hash shl 1) or (if ((px[yy*9+xx] and 0xFF) > (px[yy*9+xx+1] and 0xFF)) 1L else 0L)
        return hash
    }

    /** 亮度归一化的颜色直方图：减去平均亮度，抗光照变化 */
    fun colorHist(bmp: Bitmap): FloatArray {
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 32, 32, true)
        val px = IntArray(1024); small.getPixels(px, 0, 32, 0, 0, 32, 32)
        small.recycle()
        val hist = FloatArray(16)
        var sumR = 0; var sumG = 0
        for (c in px) { sumR += (c shr 16 and 0xFF); sumG += (c shr 8 and 0xFF) }
        val avgR = sumR / 1024; val avgG = sumG / 1024
        for (c in px) {
            val r = (((c shr 16 and 0xFF) - avgR + 256) / 64).coerceIn(0, 3)
            val g = (((c shr 8 and 0xFF) - avgG + 256) / 64).coerceIn(0, 3)
            hist[r * 4 + g] += 1f / 1024
        }
        return hist
    }

    /**
     * 匹配：先按汉明距离过滤候选，再用直方图距离打分。
     * score 0~1，越大越相似。低于 minScore 返回 null（未知物品）。
     */
    fun findById(id: String): ItemFeature? = features.find { it.id == id }

    fun match(bmp: Bitmap, minScore: Float = 0.55f): Match? = matchTop(bmp, minScore, 1).firstOrNull()

    /** 返回 topN 候选（供低置信度人工确认），按 score 降序 */
    fun matchTop(bmp: Bitmap, minScore: Float = 0.35f, topN: Int = 3): List<Match> {
        if (features.isEmpty()) return emptyList()
        val h = phash(bmp)   // 与特征库 Python 整图 dHash 一致
        val hist = colorHist(bmp)
        val scored = mutableListOf<Match>()
        for (f in features) {
            val dist = java.lang.Long.bitCount(h xor f.phash)
            if (dist > maxHashDist) continue
            var hd = 0f
            for (i in 0 until 16) hd += kotlin.math.abs(hist[i] - f.hist[i])
            val score = (1f - dist.toFloat() / maxHashDist) * 0.65f + (1f - hd.coerceAtMost(1f)) * 0.35f
            if (score >= minScore) scored.add(Match(f, score))
        }
        return scored.sortedByDescending { it.score }.take(topN)
    }
}
