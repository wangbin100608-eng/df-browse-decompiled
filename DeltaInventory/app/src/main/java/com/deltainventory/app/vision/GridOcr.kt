package com.deltainventory.app.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 从游戏画面识别背包/保险箱格数（本地 ML Kit OCR，离线）。
 * 识别形如 "10x6" / "3×3" / "背包 10 6" 的文字。
 */
object GridOcr {

    data class GridInfo(val bagWidth: Int, val bagHeight: Int,
                        val safeWidth: Int, val safeHeight: Int)

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap): GridInfo? = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text.replace("\\s+".toRegex(), " ")
                cont.resume(parseGrid(text))
            }
            .addOnFailureListener { cont.resume(null) }
    }

    /** 从单格图片读取数量角标（1-99），无数字返回 1 */
    suspend fun readQuantity(bitmap: Bitmap): Int = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { r ->
                val nums = Regex("\\d{1,2}").findAll(r.text).map { it.value.toInt() }
                    .filter { it in 1..99 }.toList()
                cont.resume(nums.firstOrNull() ?: 1)
            }
            .addOnFailureListener { cont.resume(1) }
    }

    /** 纯函数：从文本提取数量（可单测） */
    fun parseQuantity(text: String): Int {
        val nums = Regex("\\d{1,2}").findAll(text).map { it.value.toInt() }.filter { it in 1..99 }.toList()
        return nums.firstOrNull() ?: 1
    }

    /** 解析 OCR 文本中的格数 */
    fun parseGrid(text: String): GridInfo? {
        val t = text.lowercase()
        // 匹配 NxM 或 N×M
        val regex = Regex("(\\d{1,2})\\s*[x×]\\s*(\\d{1,2})")
        val matches = regex.findAll(t).map { m ->
            m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }.toList().filter { it.first in 1..20 && it.second in 1..20 }

        if (matches.isEmpty()) return null

        // 找安全箱：3x3 / 2x2（通常较小且紧邻"安全/保险"关键字）
        val safe = if (t.contains("安全") || t.contains("保险") || t.contains("safe")) {
            matches.find { it.first <= 4 && it.second <= 4 }
        } else null

        // 背包：最大的网格
        val bag = matches.filter { it != safe }.maxByOrNull { it.first * it.second }
            ?: matches.first()

        return GridInfo(bag.first, bag.second,
            safe?.first ?: 3, safe?.second ?: 3)
    }
}
