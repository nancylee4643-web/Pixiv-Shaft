package ceui.pixiv.ui.collection

import ceui.lisa.models.TagsBean
import ceui.pixiv.db.taggroup.GroupWithChildren
import ceui.pixiv.db.taggroup.TagGroupChildEntity
import ceui.pixiv.db.taggroup.TagGroupEntity
import ceui.pixiv.feeds.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildGroupedDisplay]（收藏标签筛选列表的分组折叠管线）纯逻辑单测。
 */
class BookedTagGroupDisplayTest {

    private fun tag(name: String, count: Int, translated: String? = null): TagsBean =
        TagsBean().apply {
            this.name = name
            this.count = count
            translated_name = translated
        }

    /** 与 BookedTagFeedSource.buildDisplayItems 同构:`[未分類, 全部, ...真实标签]`。 */
    private fun fullList(vararg tags: TagsBean): List<FeedItem> {
        val items = ArrayList<FeedItem>(tags.size + 2)
        items.add(BookedTagFeedItem(tag("未分類", -1)))
        items.add(BookedTagFeedItem(tag("", -1)))
        tags.forEach { items.add(BookedTagFeedItem(it)) }
        return items
    }

    private fun group(parent: String, vararg children: String): GroupWithChildren {
        val entity = TagGroupEntity(id = parent.hashCode().toLong(), name = parent)
        return GroupWithChildren(
            group = entity,
            children = children.map {
                TagGroupChildEntity(id = it.hashCode().toLong(), groupId = entity.id, name = it)
            },
        )
    }

    /** 人读快照:普通标签原名;组行 G(父;子数;开合);子行 C(名)。 */
    private fun names(items: List<FeedItem>): List<String> = items.map {
        when (it) {
            is BookedTagFeedItem -> it.tag.name
            is BookedTagGroupItem ->
                "G(${it.parent.name};${it.children.size};${if (it.expanded) "open" else "closed"})"
            is BookedTagChildItem -> "C(${it.tag.name})"
            else -> "?"
        }
    }

    @Test
    fun `无映射且无搜索时原样返回全量`() {
        val full = fullList(tag("原神", 100), tag("宵", 30))
        val result = buildGroupedDisplay(full, emptyList(), emptySet(), "")
        assertSame(full, result)
    }

    @Test
    fun `无映射时搜索走原平铺过滤`() {
        val full = fullList(tag("原神", 100), tag("宵", 30), tag("Genshin", 5))
        val result = buildGroupedDisplay(full, emptyList(), emptySet(), "genshin")
        assertEquals(listOf("Genshin"), names(result))
    }

    @Test
    fun `父标签在列表时子标签折叠收纳且组行占父位置`() {
        val full = fullList(tag("原神", 100), tag("宵", 30), tag("神里綾華", 20), tag("東方", 50))
        val result = buildGroupedDisplay(full, listOf(group("原神", "宵", "神里綾華")), emptySet(), "")
        assertEquals(listOf("未分類", "", "G(原神;2;closed)", "東方"), names(result))
    }

    @Test
    fun `展开父标签时插入缩进子行`() {
        val full = fullList(tag("原神", 100), tag("宵", 30), tag("神里綾華", 20), tag("東方", 50))
        val result = buildGroupedDisplay(
            full, listOf(group("原神", "宵", "神里綾華")), setOf("原神"), "",
        )
        assertEquals(
            listOf("未分類", "", "G(原神;2;open)", "C(宵)", "C(神里綾華)", "東方"),
            names(result),
        )
    }

    @Test
    fun `父标签不在收藏标签里时在首个子标签处插合成父行`() {
        val full = fullList(tag("宵", 30), tag("東方", 50))
        val result = buildGroupedDisplay(full, listOf(group("原神", "宵", "神里綾華")), emptySet(), "")
        assertEquals(listOf("未分類", "", "G(原神;1;closed)", "東方"), names(result))
        val groupItem = result.first { it is BookedTagGroupItem } as BookedTagGroupItem
        // 合成父行:用配置里的父名,计数留空(count=-1)
        assertEquals(-1, groupItem.parent.count)
        assertEquals("原神", groupItem.parent.name)
    }

    @Test
    fun `父标签在但子标签全不在时退化为普通标签行`() {
        val full = fullList(tag("原神", 100), tag("東方", 50))
        val result = buildGroupedDisplay(full, listOf(group("原神", "宵")), emptySet(), "")
        assertEquals(listOf("未分類", "", "原神", "東方"), names(result))
    }

    @Test
    fun `子标签先于父标签出现时组行仍在父标签位置`() {
        val full = fullList(tag("宵", 30), tag("原神", 100))
        val result = buildGroupedDisplay(full, listOf(group("原神", "宵")), emptySet(), "")
        assertEquals(listOf("未分類", "", "G(原神;1;closed)"), names(result))
    }

    @Test
    fun `搜索父标签命中时展开全部子行并隐藏虚拟行`() {
        val full = fullList(tag("原神", 100), tag("宵", 30), tag("神里綾華", 20), tag("東方", 50))
        val result = buildGroupedDisplay(full, listOf(group("原神", "宵", "神里綾華")), emptySet(), "原")
        assertEquals(listOf("G(原神;2;open)", "C(宵)", "C(神里綾華)"), names(result))
    }

    @Test
    fun `搜索仅子标签命中时只列命中的子行`() {
        val full = fullList(tag("原神", 100), tag("宵", 30), tag("神里綾華", 20), tag("東方", 50))
        val result = buildGroupedDisplay(full, listOf(group("原神", "宵", "神里綾華")), emptySet(), "宵")
        assertEquals(listOf("G(原神;1;open)", "C(宵)"), names(result))
    }

    @Test
    fun `搜索整组无命中时整组隐藏`() {
        val full = fullList(tag("原神", 100), tag("宵", 30))
        val result = buildGroupedDisplay(full, listOf(group("原神", "宵")), emptySet(), "zzz")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `搜索未分组标签命中独立显示`() {
        val full = fullList(tag("原神", 100), tag("宵", 30), tag("東方", 50))
        val result = buildGroupedDisplay(full, listOf(group("原神", "宵")), emptySet(), "東")
        assertEquals(listOf("東方"), names(result))
    }

    @Test
    fun `分组名与标签名大小写不敏感归一`() {
        val full = fullList(tag("Genshin", 100), tag("Yoimiya", 30))
        val result = buildGroupedDisplay(
            full, listOf(group("genshin", "yoimiya")), setOf("genshin"), "",
        )
        assertEquals(listOf("未分類", "", "G(Genshin;1;open)", "C(Yoimiya)"), names(result))
    }

    @Test
    fun `搜索子标签仅子命中且父不在列表时合成父行`() {
        val full = fullList(tag("宵", 30), tag("東方", 50))
        val result = buildGroupedDisplay(full, listOf(group("原神", "宵")), emptySet(), "宵")
        assertEquals(listOf("G(原神;1;open)", "C(宵)"), names(result))
    }
}
