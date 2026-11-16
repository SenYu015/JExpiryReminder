package com.jimmy.expiryreminder.core

/**
 * 物品名称相似度：给自动同步判断「像不像同一件东西」用。
 *
 * 纯函数、不依赖 Android，全部可单测。判为重名/相似的条目不会自动入库，
 * 而是挂到「待合并冲突」由用户决定，所以阈值宁可偏松一点。
 */
object Similarity {

    /** 低于这个分数视为不同物品；冲突只是提醒，不自动改数据，误报成本低。 */
    const val THRESHOLD = 0.65

    /** 全角 → 半角、去空白与常见标点、统一小写；括号里的规格描述也去掉。 */
    fun normalizeName(raw: String): String {
        val sb = StringBuilder()
        var depth = 0
        for (ch in raw) {
            val c = when {
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                ch == '\u3000' -> ' '
                else -> ch
            }
            when (c) {
                '（', '(', '【', '[', '〔' -> depth++
                '）', ')', '】', ']', '〕' -> if (depth > 0) depth--
                else -> if (depth == 0 && !c.isWhitespace() && !isPunct(c)) {
                    sb.append(c.lowercaseChar())
                }
            }
        }
        return sb.toString()
    }

    private fun isPunct(c: Char): Boolean =
        c in "·.,，、;；:：!！?？-_-—/\\|'\"“”‘’*#~+"

    /**
     * 字符二元组 Dice 系数，兼容中文短名称。
     * 完全相同 = 1.0；单字名称退化为字符相等判断。
     */
    fun score(a: String, b: String): Double {
        val left = normalizeName(a)
        val right = normalizeName(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        val l = bigrams(left)
        val r = bigrams(right)
        if (l.isEmpty() || r.isEmpty()) return 0.0
        val hit = l.intersect(r).size
        return 2.0 * hit / (l.size + r.size)
    }

    private fun bigrams(text: String): Set<String> {
        if (text.length < 2) return setOf(text)
        val out = HashSet<String>()
        for (i in 0 until text.length - 1) out.add(text.substring(i, i + 2))
        return out
    }

    /** 名称（归一后）+ 到期日：完全相同即视为「同一件物品」的候选。 */
    fun fingerprint(item: ExpiryItem): String =
        normalizeName(item.name) + "|" + item.expireAt.trim()

    fun sameFingerprint(a: ExpiryItem, b: ExpiryItem): Boolean =
        fingerprint(a) == fingerprint(b)
}
