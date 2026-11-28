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
        // 特征库的 ph 是 64-bit dHash 的「无符号」十进制表示（0..2^64-1）。
        // 必须按无符号解析成位模式相同的 long，才能与 phash()/matchRegion 返回的
        // 有符号 long 正确异或；用有符号 toLong()/parseLong 会在 ph > Long.MAX_VALUE 时
        // 抛 NumberFormatException，进而（旧逻辑）中断整个库加载。
        var loaded = 0
        var skipped = 0
        try {
            val json = ctx.assets.open("item-features.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONObject(json).getJSONArray("features")
            for (i in 0 until arr.length()) {
                // 单条容错：任何一条数据异常只跳过该条，不让整库失效。
                try {
                    val o = arr.getJSONObject(i)
                    val h = FloatArray(16)
                    val ja = o.getJSONArray("hist")
                    for (k in 0 until 16) h[k] = ja.getDouble(k).toFloat()
                    val f = ItemFeature(
                        o.getString("id"), o.getString("name"), o.getInt("w"), o.getInt("h"),
                        o.getString("cat"), o.getLong("price"),
                        java.lang.Long.parseUnsignedLong(o.getString("ph")), h
                    )
                    features.add(f); byId[f.id] = f
                    val key = (f.phash shr 48).toInt()
                    buckets.getOrPut(key) { mutableListOf() }.add(f)
                    loaded++
                } catch (e: Exception) {
                    skipped++
                    android.util.Log.w("IconMatcher", "跳过异常特征条目 #$i: ${e.message}")
                }
            }
            android.util.Log.i("IconMatcher", "特征库加载完成: loaded=$loaded skipped=$skipped")
        } catch (e: Exception) {
            android.util.Log.e("IconMatcher", "特征库加载失败: ${e.message}")
        }
    }

    companion object {
        @Volatile private var instance: IconMatcher? = null
        fun get(ctx: Context): IconMatcher = instance ?: synchronized(this) {
            instance ?: IconMatcher(ctx.applicationContext).also { instance = it }
        }
        const val MAX_HASH_DIST = 20   // 放宽以容忍游戏截图干扰
    }

    /**
     * 计算输入图的 dHash（9x8 灰度）。
     * 必须与特征库生成脚本逐位一致：步长采样到 9x8，灰度=(R*77+G*150+B*29)>>8，
     * 再对每行相邻像素做 64 次 (a>b) 比较。之前用 createScaledBitmap(双线性)+Blue
     * 通道与特征库(LANCZOS/灰度)不一致，导致识别近似随机。
     */
    fun phash(bmp: Bitmap): Long = StepHash.phash(bmp, 0, 0, bmp.width, bmp.height)

    /** 步长采样 dHash：委托 StepHash，确保与特征库生成端及 APK 完全一致（含预乘语义） */
    private fun stepPhash(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): Long =
        StepHash.phash(bmp, x, y, w, h)

    /** 中心裁剪 70% 后计算 dHash，去除格子边框/高亮干扰（步长采样/灰度，与特征库一致） */
    fun phashCenter(bmp: Bitmap): Long {
        val w = (bmp.width * 0.7f).toInt().coerceAtLeast(9)
        val h = (bmp.height * 0.7f).toInt().coerceAtLeast(8)
        val x = (bmp.width - w) / 2; val y = (bmp.height - h) / 2
        return stepPhash(bmp, x, y, w, h)
    }

    /**
     * 亮度归一化的颜色直方图：步长采样 32x32，按与特征库一致的公式分桶。
     * 生成端：avgR/avgG 为样本均值，ri=clamp((r-avgR+256)//64,0,3)，gi 同理，hist[ri*4+gi]+=1/1024。
     */
    fun colorHist(bmp: Bitmap): FloatArray = StepHash.hist(bmp, 0, 0, bmp.width, bmp.height)

    private fun stepHist(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): FloatArray =
        StepHash.hist(bmp, x, y, w, h)

    /**
     * 匹配：先按汉明距离过滤候选，再用直方图距离打分。
     * score 0~1，越大越相似。低于 minScore 返回 null（未知物品）。
     */
    fun findById(id: String): ItemFeature? = byId[id]

    fun match(bmp: Bitmap, minScore: Float = 0.55f): Match? = matchTop(bmp, minScore, 1).firstOrNull()

    /** 直接从大图的矩形区域匹配，避免每格 createBitmap（性能优化） */
    fun matchRegion(src: Bitmap, x: Int, y: Int, w: Int, h: Int, minScore: Float = 0.55f): Match? {
        if (features.isEmpty() || w < 4 || h < 4) return null
        // 与特征库一致的步长采样 dHash / 直方图（不再用 createScaledBitmap 双线性+Blue）
        val hh = stepPhash(src, x, y, w, h)
        val hist = stepHist(src, x, y, w, h)
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
