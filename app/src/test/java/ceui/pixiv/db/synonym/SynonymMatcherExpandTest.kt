package ceui.pixiv.db.synonym

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SynonymMatcher.keywordVariantGroups]（搜索扩展变体组）纯逻辑单测。
 */
class SynonymMatcherExpandTest {

    private fun entry(targetName: String, vararg synonyms: String): TargetWithSynonyms {
        val target = SynonymTargetEntity(id = targetName.hashCode().toLong(), name = targetName)
        return TargetWithSynonyms(
            target = target,
            synonyms = synonyms.map {
                SynonymTagEntity(id = (targetName + it).hashCode().toLong(), targetId = target.id, name = it)
            },
        )
    }

    private val dict = listOf(
        entry("EVA", "エヴァ", "EVANGELION"),
        entry("アスカ", "明日香"),
    )

    @Test
    fun `空白关键词返回空列表`() {
        assertEquals(emptyList<List<String>>(), SynonymMatcher.keywordVariantGroups("", dict))
        assertEquals(emptyList<List<String>>(), SynonymMatcher.keywordVariantGroups("   ", dict))
        assertEquals(emptyList<List<String>>(), SynonymMatcher.keywordVariantGroups("　", dict))
    }

    @Test
    fun `未命中时每组只有原词`() {
        val result = SynonymMatcher.keywordVariantGroups("原神", dict)
        assertEquals(listOf(listOf("原神")), result)
    }

    @Test
    fun `命中目标名时展开全部同义词且不含自身`() {
        val result = SynonymMatcher.keywordVariantGroups("EVA", dict)
        assertEquals(listOf(listOf("EVA", "エヴァ", "EVANGELION")), result)
    }

    @Test
    fun `命中同义词名时目标名与兄弟同义词都展开`() {
        val result = SynonymMatcher.keywordVariantGroups("エヴァ", dict)
        // 目标名排第一，其后按词典内顺序
        assertEquals(listOf(listOf("エヴァ", "EVA", "EVANGELION")), result)
    }

    @Test
    fun `大小写不敏感命中`() {
        assertEquals(listOf(listOf("eva", "エヴァ", "EVANGELION")), SynonymMatcher.keywordVariantGroups("eva", dict))
        // 组首保留用户输入原词（原样小写），词典内同名同义词（EVANGELION）归一后与关键词相同被剔除
        assertEquals(listOf(listOf("evangelion", "EVA", "エヴァ")), SynonymMatcher.keywordVariantGroups("evangelion", dict))
    }

    @Test
    fun `多词查询按词分组且支持全角空格`() {
        val expected = listOf(
            listOf("エヴァ", "EVA", "EVANGELION"),
            listOf("アスカ", "明日香"),
        )
        assertEquals(expected, SynonymMatcher.keywordVariantGroups("エヴァ アスカ", dict))
        assertEquals(expected, SynonymMatcher.keywordVariantGroups(" エヴァ　アスカ ", dict))
    }

    @Test
    fun `一个词命中多个目标时合并全部变体并去重`() {
        val multiDict = listOf(
            entry("東方", "touhou"),
            entry("TouhouProject", "touhou"),
        )
        // 两目标都因同义词 touhou 命中：目标名按词典顺序展开，重复的 touhou 只保留原词一份
        assertEquals(
            listOf(listOf("touhou", "東方", "TouhouProject")),
            SynonymMatcher.keywordVariantGroups("touhou", multiDict),
        )
    }

    @Test
    fun `跨目标重复变体按归一化去重保留首个原形`() {
        val multiDict = listOf(
            entry("A1", "dup", "x"),
            entry("A2", "DUP"),
        )
        // "dup" 命中两目标；DUP 与 dup 归一相同，只保留先出现的 dup
        assertEquals(
            listOf(listOf("dup", "A1", "x", "A2")),
            SynonymMatcher.keywordVariantGroups("dup", multiDict),
        )
    }

    @Test
    fun `备注不参与命中`() {
        val remarkDict = listOf(
            TargetWithSynonyms(
                target = SynonymTargetEntity(id = 1L, name = "ゾロ"),
                synonyms = listOf(
                    SynonymTagEntity(id = 2L, targetId = 1L, name = "罗罗诺亚", remark = "索隆"),
                ),
            ),
        )
        assertEquals(listOf(listOf("索隆")), SynonymMatcher.keywordVariantGroups("索隆", remarkDict))
    }

    @Test
    fun `与目标名仅大小写不同的变体不重复进入组`() {
        // 搜 eva：目标 EVA 归一后等于关键词自身，被剔除，组内只剩真正的同义词
        val result = SynonymMatcher.keywordVariantGroups("eva", listOf(entry("EVA", "エヴァ")))
        assertEquals(listOf(listOf("eva", "エヴァ")), result)
    }
}
