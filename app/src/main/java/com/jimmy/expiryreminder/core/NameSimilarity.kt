package com.jimmy.expiryreminder.core

/**
 * 物品名称相似度：新增物品时提示「这条可能已经录过」，避免重复录入。
 *
 * 纯计算，不读写存储、不缓存任何东西。中文物品名以短词为主（牛奶、维生素C、护照），
 * 单靠编辑距离会把「面膜」和「口罩」判成一半相似，所以三种信号取最大值：
 *  - 包含关系最可信：牛奶 → 伊利牛奶；
 *  - 字符二元组 Dice 系数：维生素C / 维生素B 这类「同款不同规格」；
 *  - 编辑距离（乘 0.9 压权重）：修掉个别错别字，但不至于因为换一个字就报警。
 */
object NameSimilarity {

    /** 少于这么多字符不检索：「药」「盐」这种会命中一大片，纯噪音。 */
    const val MIN_QUERY_LENGTH = 2

    /** 入选阈值：低于它不打扰用户。 */
    const val MIN_SCORE = 0.5

    /** 列表最多给几条；再多用户也懒得看。 */
    const val DEFAULT_LIMIT = 12

    /** 一条命中。[sameName] = 归一化后完全同名，弹窗里单独标出来。 */
    data class Match(
        val item: ExpiryItem,
        val score: Double,
        val sameName: Boolean,
    )

    // ---------------------------------------------------------------- 对外

    /**
     * 归一化：去空白、全角转半角、小写、丢掉标点符号。
     * 「维生素 Ｃ」与「维生素c」归一化后完全一致，这类差异不该算成两个物品。
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val half = when (ch.code) {
                in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar() // 全角 ASCII 段
                0x3000 -> ' '                                     // 全角空格
                else -> ch
            }
            if (half.isLetterOrDigit()) sb.append(half.lowercaseChar())
        }
        return sb.toString()
    }

    /** 两个原始名称的相似度（0..1），内部先归一化。 */
    fun score(a: String, b: String): Double = scoreNormalized(normalize(a), normalize(b))

    /**
     * 在已有物品里找和 [keyword] 相似的，按相似度降序、同分按到期日升序。
     *
     * 已归档物品不参与（用完/删掉的旧记录不必提醒）；[excludeId] 用于编辑模式下排除自己。
     * 查询串归一化后不足 [MIN_QUERY_LENGTH] 直接返回空——宁可不提示，也不刷一屏噪音。
     */
    fun findSimilar(
        items: List<ExpiryItem>,
        keyword: String,
        minScore: Double = MIN_SCORE,
        limit: Int = DEFAULT_LIMIT,
        excludeId: String? = null,
    ): List<Match> {
        val query = normalize(keyword)
        if (query.length < MIN_QUERY_LENGTH) return emptyList()
        return items.asSequence()
            .filter { !it.archived && it.id != excludeId && it.name.isNotBlank() }
            .map { item ->
                val name = normalize(item.name)
                Match(item = item, score = scoreNormalized(query, name), sameName = name == query)
            }
            .filter { it.score >= minScore }
            .sortedWith(
                compareByDescending<Match> { it.score }
                    .thenBy { it.item.expireAt.ifEmpty { "9999-12-31" } },
            )
            .take(limit)
            .toList()
    }

    // ---------------------------------------------------------------- 打分

    private fun scoreNormalized(x: String, y: String): Double {
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val containment = containmentScore(x, y)
        val dice = diceScore(x, y)
        val edit = editSimilarity(x, y) * 0.9
        return maxOf(containment, dice, edit)
    }

    /** 「牛奶」被「伊利牛奶」包含：0.85 起，短串越长占比越高，最多 1.0。 */
    private fun containmentScore(x: String, y: String): Double {
        val short = if (x.length <= y.length) x else y
        val long = if (x.length <= y.length) y else x
        if (short.length < 2 || long.length == short.length) return 0.0
        if (!long.contains(short)) return 0.0
        return 0.85 + 0.15 * short.length / long.length
    }

    /** 字符二元组 Dice 系数（多重集）：2*交集 / 总个数。 */
    private fun diceScore(x: String, y: String): Double {
        if (x.length < 2 || y.length < 2) return 0.0
        val grams = mutableListOf<String>()
        for (i in 0 until y.length - 1) grams.add(y.substring(i, i + 2))
        var common = 0
        for (i in 0 until x.length - 1) {
            val gram = x.substring(i, i + 2)
            if (grams.remove(gram)) common++
        }
        return 2.0 * common / ((x.length - 1) + (y.length - 1))
    }

    /** 1 - 编辑距离 / 较长串长度。 */
    private fun editSimilarity(x: String, y: String): Double {
        val longest = maxOf(x.length, y.length)
        if (longest == 0) return 1.0
        return 1.0 - editDistance(x, y).toDouble() / longest
    }

    /** Levenshtein 距离，滚动数组两行。 */
    private fun editDistance(x: String, y: String): Int {
        var prev = IntArray(y.length + 1) { it }
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[y.length]
    }
}
