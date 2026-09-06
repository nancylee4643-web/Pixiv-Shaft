package ceui.pixiv.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SynonymSearchExpansion]（lane 笛卡尔积组合 + 复合游标编解码）纯逻辑单测。
 */
class SynonymSearchExpansionTest {

    // ---------- buildLaneQueries ----------

    @Test
    fun `全部组无变体时只有原始查询一条`() {
        val result = SynonymSearchExpansion.buildLaneQueries(listOf(listOf("A"), listOf("B")))
        assertEquals(listOf("A B"), result)
    }

    @Test
    fun `单组多变体按替换数升序排列`() {
        val result = SynonymSearchExpansion.buildLaneQueries(listOf(listOf("A", "A1", "A2")))
        assertEquals(listOf("A", "A1", "A2"), result)
    }

    @Test
    fun `两词两组笛卡尔积全覆盖且原始查询恒为第零路`() {
        val groups = listOf(listOf("A", "A1", "A2"), listOf("B", "B1"))
        val result = SynonymSearchExpansion.buildLaneQueries(groups)
        // 第零路 = 全原词；第一层替换按组序展开（第 0 组变体在前，再第 1 组变体）
        assertEquals(
            listOf("A B", "A1 B", "A2 B", "A B1", "A1 B1", "A2 B1"),
            result,
        )
    }

    @Test
    fun `默认上限九路两词各两同义词恰好全覆盖`() {
        // (1+2)×(1+2)=9 ≤ MAX_LANES
        val groups = listOf(listOf("A", "A1", "A2"), listOf("B", "B1", "B2"))
        val result = SynonymSearchExpansion.buildLaneQueries(groups)
        assertEquals(9, result.size)
        assertEquals("A B", result.first())
        assertEquals(SynonymSearchExpansion.MAX_LANES, 9)
    }

    @Test
    fun `超上限时截断且保留原始查询与单替换优先`() {
        val groups = listOf(listOf("A", "A1", "A2"), listOf("B", "B1", "B2"))
        val result = SynonymSearchExpansion.buildLaneQueries(groups, maxLanes = 3)
        assertEquals(listOf("A B", "A1 B", "A2 B"), result)
    }

    @Test
    fun `无变体的组不参与替换`() {
        val groups = listOf(listOf("A"), listOf("B", "B1"), listOf("C"))
        val result = SynonymSearchExpansion.buildLaneQueries(groups)
        assertEquals(listOf("A B C", "A B1 C"), result)
    }

    @Test
    fun `不同组合拼出相同查询时去重`() {
        // 第二组只有与第一组同名变体：全部组合都拼成 "X X"，只保留一条
        val groups = listOf(listOf("X"), listOf("X", "X"))
        val result = SynonymSearchExpansion.buildLaneQueries(groups)
        assertEquals(listOf("X X"), result)
    }

    @Test
    fun `空组视为非法输入返回空列表`() {
        assertEquals(emptyList<String>(), SynonymSearchExpansion.buildLaneQueries(emptyList()))
        assertEquals(emptyList<String>(), SynonymSearchExpansion.buildLaneQueries(listOf(listOf("A"), emptyList())))
    }

    // ---------- cursor codec ----------

    @Test
    fun `编码全到底返回null`() {
        assertNull(SynonymSearchExpansion.encodeCursor(emptyList()))
        assertNull(SynonymSearchExpansion.encodeCursor(listOf(null, null)))
        assertNull(SynonymSearchExpansion.encodeCursor(listOf("", null)))
    }

    @Test
    fun `编码混合url时到底路为空串`() {
        val cursor = SynonymSearchExpansion.encodeCursor(listOf("u1", null, ""))
        assertEquals(SynonymSearchExpansion.CURSOR_PREFIX + "u1\n\n", cursor)
    }

    @Test
    fun `解码无前缀游标返回null`() {
        assertNull(SynonymSearchExpansion.decodeCursor("https://app-api.pixiv.net/v1/search/illust?offset=30"))
        assertNull(SynonymSearchExpansion.decodeCursor(""))
    }

    @Test
    fun `编解码往返一致`() {
        val urls = listOf("u1", "", "u3")
        val cursor = SynonymSearchExpansion.encodeCursor(urls)
        assertEquals(urls, SynonymSearchExpansion.decodeCursor(cursor!!))
    }
}
