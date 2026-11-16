package com.jimmy.expiryreminder.core

import com.jimmy.expiryreminder.ocr.DateExtractor
import com.jimmy.expiryreminder.ocr.DateRole
import com.jimmy.expiryreminder.ocr.OcrLine

/** 一句话语音快速录入的解析结果（纯文本，不含任何联网/云端能力）。 */
data class QuickRecord(
    val name: String,
    val expireAt: String,
    val note: String,
)

/**
 * 把本机离线语音识别出来的一句话拆成「名称 / 到期日 / 存放位置」。
 *
 * 只复用已有的离线日期抽取逻辑，不做语义理解、不调用任何在线模型。
 * 例：「牛奶 2027年2月10日到期 放在冰箱上层」
 *   → name「牛奶」、expireAt「2027-02-10」、note「放在冰箱上层」。
 */
object VoiceText {

    private val LOCATION = Regex("(?:放在|放到|位置在|位置是|储存在|存放于|放)([^，。,；;]+)")
    private val PUNCT = Regex("[，。,；;：:！!？?]+")
    private val DATE_WORDS = Regex(
        "(有效期至|有效至|有效期|保质期至|保质期|此日期前食用最佳|生产日期|制造日期|到期|过期|EXP|BBE|USE BY)",
        RegexOption.IGNORE_CASE,
    )

    fun parseQuickRecord(spoken: String): QuickRecord {
        val text = spoken.trim()
        if (text.isEmpty()) return QuickRecord("", "", "")

        val candidates = runCatching {
            DateExtractor.extract(listOf(OcrLine(text)), 0)
        }.getOrDefault(emptyList())
        val bestDate = candidates.sortedWith(
            compareByDescending<com.jimmy.expiryreminder.ocr.DateCandidate> {
                if (it.role == DateRole.expire) 1 else 0
            }.thenByDescending { it.score },
        ).firstOrNull()
        val expireAt = bestDate?.isoDate?.takeIf { Dates.isValidISODate(it) } ?: ""

        var rest = text
        if (bestDate != null && bestDate.raw.isNotBlank()) {
            rest = rest.replace(bestDate.raw, " ")
        }
        val location = LOCATION.find(rest)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (location.isNotEmpty()) rest = LOCATION.replaceFirst(rest, " ")

        val name = rest.replace(DATE_WORDS, " ").replace(PUNCT, " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
            .take(20)
            .ifBlank { text.take(20) }

        return QuickRecord(
            name = name,
            expireAt = expireAt,
            note = if (location.isNotEmpty()) "放在$location" else "",
        )
    }
}
