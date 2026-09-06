package ceui.pixiv.ui.search

/**
 * 同义词扩大搜索的组合与游标编解码（纯逻辑，单测见 SynonymSearchExpansionTest）。
 *
 * Pixiv 搜索多词是 AND 语义且没有 OR 语法——「按同义词扩大」= 对每个词的同义词变体组
 * （[ceui.pixiv.db.synonym.SynonymMatcher.keywordVariantGroups]）做笛卡尔积，逐组合发一条
 * 搜索请求，客户端合并去重。组合数随变体数指数增长，所以：
 * - 按替换数升序枚举（BFS）：原始查询 → 单替换 → 双替换……，截断到 [MAX_LANES]；
 *   两词各 ≤2 个同义词可全覆盖（3×3=9），更多组合按优先级丢弃，原始查询恒为第 0 路。
 */
internal object SynonymSearchExpansion {

    /** 每代搜索最多并发路数（含主路），防请求风暴。 */
    const val MAX_LANES = 9

    /** 复合游标前缀——区分多路游标与单路裸 next_url。URL 不含换行，"\n" 连接安全。 */
    const val CURSOR_PREFIX = "synonym_lanes:"

    /**
     * 变体组 → lane 查询串列表（每 lane = 各组各取一词、空格连接）。
     *
     * @param groups 每个词一组：`[原词, 变体...]`；组只有原词 = 该词无扩展
     * @return lane 0 恒为全原词的原始查询；同替换数内按组序/变体序稳定排列；
     *         查询串相同的车道去重；全部组无变体 → 只返回原始查询一条。
     *         任一组为空视为输入非法，返回空列表（调用方回退单路路径）。
     */
    fun buildLaneQueries(groups: List<List<String>>, maxLanes: Int = MAX_LANES): List<String> {
        if (groups.isEmpty() || groups.any { it.isEmpty() }) return emptyList()

        // BFS 按替换数分层：combo[i] = 第 i 组取的下标（0 = 原词）。队列天然保证
        // 第 k 层（k 次替换）全部出队后才到第 k+1 层；seen 去掉经不同路径到达的同一组合。
        val start = List(groups.size) { 0 }
        val seen = HashSet<List<Int>>()
        val queue = ArrayDeque<List<Int>>()
        seen.add(start)
        queue.add(start)
        val seenQueries = HashSet<String>()
        val result = ArrayList<String>()
        while (queue.isNotEmpty() && result.size < maxLanes) {
            val combo = queue.removeFirst()
            val query = groups.mapIndexed { i, g -> g[combo[i]] }.joinToString(" ")
            // 不同组合可能拼出相同查询（如两组含同名词）——重复查询没有意义，丢弃
            if (seenQueries.add(query)) {
                result.add(query)
            }
            for (i in groups.indices) {
                if (combo[i] != 0) continue
                for (v in 1 until groups[i].size) {
                    val next = combo.toMutableList().also { it[i] = v }
                    if (seen.add(next)) {
                        queue.add(next)
                    }
                }
            }
        }
        return result
    }

    /**
     * 多路 next_url 编码进一个 String 游标；null/空白 = 该路到底。
     * 全部路到底返回 null（列表正常触底）。
     */
    fun encodeCursor(laneUrls: List<String?>): String? {
        val parts = laneUrls.map { it?.takeIf { url -> url.isNotEmpty() } ?: "" }
        if (parts.all { it.isEmpty() }) return null
        return CURSOR_PREFIX + parts.joinToString("\n")
    }

    /**
     * 解码复合游标为各路 url 列表（"" = 该路到底）。
     * 无 [CURSOR_PREFIX] 前缀（单路裸 next_url）返回 null，调用方走原单路路径。
     */
    fun decodeCursor(cursor: String): List<String>? {
        if (!cursor.startsWith(CURSOR_PREFIX)) return null
        return cursor.removePrefix(CURSOR_PREFIX).split("\n")
    }
}
