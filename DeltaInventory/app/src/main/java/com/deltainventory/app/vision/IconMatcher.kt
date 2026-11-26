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
    private val byId = HashMap<String, ItemFeature>()
    /** pHash -> 候选列表，按汉明距离过滤时先用 bucket 加速（pHash 分桶：高16位） */
    private val buckets = HashMap<Int, MutableList<ItemFeature>>()
    val count get() = features.size
    /** 可运行时调整的最大汉明距离（设置页） */
    var maxHashDist: Int = MAX_HASH_DIST
    private var px72 = IntArray(72)
    private var px1024 = IntArray(1024)

    init {
        try {
            val json = ctx.assets.open("item-features.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONObject(json).getJSONArray("features")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val h = FloatArray(16)
                val ja = o.getJSONArray("hist")
                for (k in 0 until 16) h[k] = ja.getDouble(k).toFloat()
                val f = ItemFeature(
                    o.getString("id"), o.getString("name"), o.getInt("w"), o.getInt("h"),
                    o.getString("cat"), o.getLong("price"), o.getString("ph").toLong(), h
                )
                features.add(f); byId[f.id] = f
                val key = (f.phash shr 48).toInt()
                buckets.getOrPut(key) { mutableListOf() }.add(f)
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
        small.getPixels(px72, 0, 9, 0, 0, 9, 8)
        small.recycle()
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            h = (h shl 1) or (if (px72[y * 9 + x] and 0xFF > px72[y * 9 + x + 1] and 0xFF) 1L else 0L)
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
        small.getPixels(px72, 0, 9, 0, 0, 9, 8); small.recycle()
        var hash = 0L
        for (yy in 0 until 8) for (xx in 0 until 8)
            hash = (hash shl 1) or (if ((px72[yy*9+xx] and 0xFF) > (px72[yy*9+xx+1] and 0xFF)) 1L else 0L)
        return hash
    }

    /** 亮度归一化的颜色直方图：减去平均亮度，抗光照变化 */
    fun colorHist(bmp: Bitmap): FloatArray {
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 32, 32, true)
        small.getPixels(px1024, 0, 32, 0, 0, 32, 32)
        small.recycle()
        val hist = FloatArray(16)
        var sumR = 0; var sumG = 0
        for (c in px1024) { sumR += (c shr 16 and 0xFF); sumG += (c shr 8 and 0xFF) }
        val avgR = sumR / 1024; val avgG = sumG / 1024
        for (c in px1024) {
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
    fun findById(id: String): ItemFeature? = byId[id]

    fun match(bmp: Bitmap, minScore: Float = 0.55f): Match? = matchTop(bmp, minScore, 1).firstOrNull()

    /** 直接从大图的矩形区域匹配，避免每格 createBitmap（性能优化） */
    fun matchRegion(src: Bitmap, x: Int, y: Int, w: Int, h: Int, minScore: Float = 0.55f): Match? {
        if (features.isEmpty() || w < 4 || h < 4) return null
        // 缩放区域到 9x8 / 32x32 时直接用 Matrix 源矩形
        val small9 = Bitmap.createBitmap(9, 8, Bitmap.Config.ARGB_8888)
        val c9 = android.graphics.Canvas(small9)
        c9.drawBitmap(src, android.graphics.Rect(x, y, x+w, y+h),
            android.graphics.Rect(0, 0, 9, 8), null)
        small9.getPixels(px72, 0, 9, 0, 0, 9, 8); small9.recycle()
        var hh = 0L
        for (yy in 0 until 8) for (xx in 0 until 8)
            hh = (hh shl 1) or (if ((px72[yy*9+xx] and 0xFF) > (px72[yy*9+xx+1] and 0xFF)) 1L else 0L)

        val small32 = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val c32 = android.graphics.Canvas(small32)
        c32.drawBitmap(src, android.graphics.Rect(x, y, x+w, y+h),
            android.graphics.Rect(0, 0, 32, 32), null)
        small32.getPixels(px1024, 0, 32, 0, 0, 32, 32); small32.recycle()
        var sumR=0; var sumG=0
        for (c in px1024) { sumR += (c shr 16 and 0xFF); sumG += (c shr 8 and 0xFF) }
        val avgR=sumR/1024; val avgG=sumG/1024
        val hist=FloatArray(16)
        for (c in px1024) {
            val r=(((c shr 16 and 0xFF)-avgR+256)/64).coerceIn(0,3)
            val g=(((c shr 8 and 0xFF)-avgG+256)/64).coerceIn(0,3)
            hist[r*4+g]+=1f/1024
        }
        val hi=(hh shr 48).toInt()
        val cand=HashSet<ItemFeature>()
        for (dk in -1..1) buckets[hi+dk]?.let { cand.addAll(it) }
        var best: ItemFeature?=null; var bs=-1f
        for (f in cand) {
            val dist=java.lang.Long.bitCount(hh xor f.phash)
            if (dist>maxHashDist) continue
            var hd=0f; for(i in 0 until 16) hd+=kotlin.math.abs(hist[i]-f.hist[i])
            val score=(1f-dist.toFloat()/maxHashDist)*0.65f+(1f-hd.coerceAtMost(1f))*0.35f
            if(score>bs){bs=score;best=f}
        }
        return best?.takeIf { bs>=minScore }?.let { Match(it, bs) }
    }

    /** 返回 topN 候选（供低置信度人工确认），按 score 降序 */
    fun matchTop(bmp: Bitmap, minScore: Float = 0.35f, topN: Int = 3): List<Match> {
        if (features.isEmpty()) return emptyList()
        val h = phash(bmp)   // 与特征库 Python 整图 dHash 一致
        val hist = colorHist(bmp)
        val scored = mutableListOf<Match>()
        val hi = (h shr 48).toInt()
        // 高16位相同 bucket + 相邻 bucket（汉明距离<=20 时高16位差不会太大）
        val candidates = HashSet<ItemFeature>()
        for (dk in -1..1) buckets[hi + dk]?.let { candidates.addAll(it) }
        for (f in candidates) {
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
