package ceui.pixiv.db.synonym

/**
 * 同义词词典匹配引擎（issue #904），纯逻辑无副作用。
 *
 * 匹配规则：
 * - 同义词标签与作品标签【原文】或【译文】整体比较，不按空格拆分
 *   （issue：英文标签如 "high school girl" 必须整体匹配，不能拆成 #high #school #girl）
 * - 英文忽略大小写（issue 作者："合并还是不合并随便吧" —— 合并对用户更友好）
 * - 目标标签名自身也参与匹配（作品直接带有 "EVA" 标签时，目标标签 EVA 视为命中）
 * - 备注不参与匹配
 */
object SynonymMatcher {

    /** 单个同义词的匹配结果（是否命中由它落在 matchedSynonyms / unmatchedSynonyms 哪个列表表达） */
    data class MatchItem(
        val synonym: SynonymTagEntity,
        /** 命中的作品标签在作品标签列表中的下标（用于按作品标签顺序排序）；未命中为 [Int.MAX_VALUE] */
        val workTagIndex: Int,
    )

    /** 单个目标标签的匹配结果 */
    data class TargetResult(
        val target: SynonymTargetEntity,
        /** 任一同义词命中、或目标标签名自身命中作品标签，即为 true */
        val matched: Boolean,
        /** 命中的同义词，按作品标签显示顺序排列（issue 排序规则） */
        val matchedSynonyms: List<MatchItem>,
        /** 未命中的同义词，按词典内顺序排列 */
        val unmatchedSynonyms: List<MatchItem>,
    )

    /**
     * @param workTags 作品自身标签 (原文 to 译文)，保持作品内显示顺序
     * @param dictionary 词典全量（[SynonymDao.getAllWithSynonyms]）
     * @return 每个目标标签一个结果，顺序与词典一致
     */
    fun match(
        workTags: List<Pair<String, String?>>,
        dictionary: List<TargetWithSynonyms>,
    ): List<TargetResult> {
        // 作品标签查找表：normalize(原文/译文) -> 该标签在作品中的下标（重复时取最先出现的）
        val workIndex = HashMap<String, Int>()
        workTags.forEachIndexed { idx, (name, translated) ->
            normalize(name)?.let { workIndex.putIfAbsent(it, idx) }
            normalize(translated)?.let { workIndex.putIfAbsent(it, idx) }
        }

        return dictionary.map { entry ->
            val matched = ArrayList<MatchItem>()
            val unmatched = ArrayList<MatchItem>()
            entry.synonyms.forEach { syn ->
                val idx = normalize(syn.name)?.let { workIndex[it] }
                if (idx != null) {
                    matched.add(MatchItem(syn, idx))
                } else {
                    unmatched.add(MatchItem(syn, Int.MAX_VALUE))
                }
            }
            matched.sortBy { it.workTagIndex }
            val selfHit = normalize(entry.target.name)?.let { workIndex.containsKey(it) } ?: false
            TargetResult(
                target = entry.target,
                matched = selfHit || matched.isNotEmpty(),
                matchedSynonyms = matched,
                unmatchedSynonyms = unmatched,
            )
        }
    }

    /**
     * 只有标签名、没有译文的场景（按标签收藏页只拿得到 tagNames）。
     *
     * @return 命中的目标标签名列表，顺序与词典一致
     */
    fun matchedTargetNames(
        workTagNames: List<String>,
        dictionary: List<TargetWithSynonyms>,
    ): List<String> {
        return match(workTagNames.map { it to null }, dictionary)
            .filter { it.matched }
            .map { it.target.name }
    }

    /**
     * 搜索扩展：把搜索关键词按空白（含全角空格）拆词，对每个词在词典中找同义词变体。
     *
     * 单个词的命中规则与 [match] 一致：归一化后等于某目标名或其任一同义词名（备注不参与）。
     * 命中 → 该词变体组 = [原词, 命中目标名, 各命中目标的全部同义词...]（跨命中目标合并，
     * 按归一化形式去重保留首个原形，剔除与原词相同者）；未命中 → 组 = [原词]。
     *
     * @return 每个词一个变体组，顺序与拆词结果一致；关键词空白（拆不出词）返回空列表。
     * 调用方对各组做笛卡尔积得到扩展搜索组合（见 SynonymSearchExpansion）。
     */
    fun keywordVariantGroups(keyword: String, dictionary: List<TargetWithSynonyms>): List<List<String>> {
        val words = keyword.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        // 归一化词 → 命中它的全部目标条目（目标名或任一同义词名等于该词）
        val normalizedWord = words.map { normalize(it) }
        return words.mapIndexed { index, word ->
            val key = normalizedWord[index] ?: return@mapIndexed listOf(word)
            val hitEntries = dictionary.filter { entry ->
                normalize(entry.target.name) == key ||
                        entry.synonyms.any { normalize(it.name) == key }
            }
            if (hitEntries.isEmpty()) return@mapIndexed listOf(word)
            val seen = HashSet<String>()
            seen.add(key)
            val variants = ArrayList<String>()
            hitEntries.forEach { entry ->
                (listOf(entry.target.name) + entry.synonyms.map { it.name }).forEach { candidate ->
                    val n = normalize(candidate) ?: return@forEach
                    if (seen.add(n)) variants.add(candidate)
                }
            }
            if (variants.isEmpty()) listOf(word) else listOf(word) + variants
        }
    }

    /** trim + 小写归一。空白返回 null（不参与匹配） */
    private fun normalize(raw: String?): String? {
        val s = raw?.trim()?.lowercase() ?: return null
        return s.ifEmpty { null }
    }

    private val WHITESPACE_REGEX = Regex("[\\s　]+")
}
