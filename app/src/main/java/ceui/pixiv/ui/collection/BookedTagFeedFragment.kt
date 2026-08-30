package ceui.pixiv.ui.collection

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.CancellationException
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentBookedTagFeedBinding
import ceui.lisa.databinding.RecyBookTagBinding
import ceui.lisa.databinding.RecyBookTagChildBinding
import ceui.lisa.databinding.RecyBookTagGroupBinding
import ceui.lisa.models.TagsBean
import ceui.lisa.http.Retro
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.db.taggroup.GroupWithChildren
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.taggroup.TagGroupOperate
import ceui.pixiv.utils.ppppx
import com.blankj.utilcode.util.BarUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

/**
 * 「按标签筛选」——按收藏标签浏览收藏(feeds 框架版,替代 legacy [ceui.lisa.fragments.FragmentBookedTag]
 * + BookedTagAdapter + NetListFragment)。入口在 [TemplateActivity] `EXTRA_FRAGMENT="按标签筛选"`。
 *
 * 复刻 legacy 全部行为:
 * 1. 首屏顶部两个虚拟行 `[未分類, 全部]`(count=-1),由数据源拼在真实标签前(见 [BookedTagFeedSource])。
 * 2. 客户端搜索:搜索框 debounce 200ms(清空 0ms),按 name/translated_name 大小写不敏感 contains
 *    过滤;搜索时隐藏虚拟行(count==-1),空结果走框架空态;搜索时禁用下拉刷新。
 * 3. 预加载全部:legacy 是搜索时才逐页拉全;收藏标签是有界集合,这里更简单——首屏 load() 一次拉全
 *    ([BookedTagFeedSource]),搜索直接命中内存全量。因此 legacy 的 searchPreloadProgress 进度条
 *    在此常隐藏(首屏拉全由框架 loading 圈反馈),布局保留其 id 只为结构对齐。
 * 4. 行点击:发 [Params.FILTER_NOVEL]/[Params.FILTER_ILLUST] 广播(CONTENT=tag.name,
 *    STAR_TYPE=starType),然后 finish()。
 * 5. toolbar 菜单「同义词词典」「标签分组」仅在 [Shaft] 设置开启时显示。
 *
 * 标签分组(父标签收纳子标签,如 作品名→角色名):配置存 Room([ceui.pixiv.db.taggroup]),
 * 经 LiveData observe 进 [tagGroups];展示管线 [rebuildDisplay] → [buildGroupedDisplay] 把平铺
 * 列表改组为「父标签行(默认折叠)+ 缩进子标签行」。父标签行单击仍按父标签筛选,右侧
 * 计数+箭头热区展开/收起;子标签行单击按子标签筛选。开关关闭或无映射时输出与纯平铺逐字节一致。
 *
 * 长按(分组开关开启时才响应):普通标签行 → 归类到父标签选择器(已有父标签或新建,
 * 见 [TagGroupOperate.showAssignToParentPicker]);名字命中父标签的行和父标签分组行 → 管理
 * 菜单(同管理页);子标签行 → 移动到其他父标签 / 移出分组。归类写库后 Room LiveData 自动
 * 回流 rebuildDisplay,行当场折进父标签。
 *
 * 参数:`type`([Params.DATA_TYPE],0 插画/1 小说)、`starType`([Params.STAR_TYPE],公开/私人收藏)。
 */
class BookedTagFeedFragment : FeedFragment(R.layout.fragment_booked_tag_feed) {

    private val binding by viewBinding(FragmentBookedTagFeedBinding::bind)

    // 渲染器 / 行点击广播要用(渲染器由视图作用域持有,捕获 Fragment 安全——零捕获约定只约束
    // 被 VM 长期持有的 FeedSource lambda)。lazy 首次访问在 super.onViewCreated 装配渲染器时,
    // 此时已 attach,arguments 就绪。
    private val type: Int by lazy { requireArguments().getInt(Params.DATA_TYPE, 0) }
    private val starType: String? by lazy { requireArguments().getString(Params.STAR_TYPE) }

    override val feedViewModel by feedViewModels<String> {
        // 零捕获:arguments 先读进局部 val,数据源只持有两个基本类型参数(sourceProvider lambda
        // 本身仅在建 VM 时跑一次、不被 VM 保留,这里引用 this 是安全的短命捕获)。
        val args = requireArguments()
        val type = args.getInt(Params.DATA_TYPE, 0)
        val starType = args.getString(Params.STAR_TYPE)
        BookedTagFeedSource(type, starType)
    }

    /** 全量已在首屏一次拉全,无翻页;关掉滚到底自动追加(nextCursor 本就是 null,双保险)。 */
    override val loadMoreEnabled: Boolean = false

    /** 过滤真源:每代真实数据落地时刷新为 `[虚拟行 + 真实标签]` 全量;搜索/分组从它算。 */
    private var fullList: List<FeedItem> = emptyList()

    /** 已捕获过的整代代号;只有 refresh 成功(网络首屏)才自增 refreshGeneration,自身 mutateItems 不会。 */
    private var capturedGeneration: Int = 0

    /** 标签分组映射(父标签→子标签),Room LiveData 驱动,管理页改动后自动回流刷新。 */
    private var tagGroups: List<GroupWithChildren> = emptyList()

    /** 已展开的父标签名(normalize 后),内存态即可——点标签后本页就 finish,无需跨会话记忆。 */
    private val expandedParents = mutableSetOf<String>()

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingFilter: Runnable? = null
    private var currentQuery: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.apply {
            updatePadding(top = BarUtils.getStatusBarHeight())
            setNavigationOnClickListener { activity?.finish() }
        }
        binding.toolbarTitle.text = getString(R.string.string_244)

        // 同义词词典管理入口(issue #904):总开关打开时才显示。
        if (Shaft.sSettings.isSynonymDictEnabled) {
            binding.toolbar.menu.add(getString(R.string.synonym_dict_title))
                .setOnMenuItemClickListener {
                    startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "同义词词典")
                    })
                    true
                }
        }

        // 标签分组管理入口:开关打开时才显示(同上)。
        if (Shaft.sSettings.isTagGroupEnabled) {
            binding.toolbar.menu.add(getString(R.string.tag_group_title))
                .setOnMenuItemClickListener {
                    startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "标签分组")
                    })
                    true
                }
        }

        setUpSearch()

        // 标签分组映射:任何变动(含从管理页返回)自动重算展示。
        AppDatabase.getAppDatabase(requireContext()).tagGroupDao().getAllWithChildrenLive()
            .observe(viewLifecycleOwner) { groups ->
                tagGroups = groups.orEmpty()
                rebuildDisplay()
            }

        // 配置变更后 VM 可能保留着「上次搜索的过滤子集」(refreshGeneration 已推进)。先把 capturedGeneration
        // 对齐当前值,让下面的 collector 不会把这份陈旧子集误当 fullList 捕获(否则清空搜索只剩子集、全量
        // 再也拿不回);真正的 fullList 由下面 config-change 的 refresh() 拉回的新一代重建。首次创建时 VM
        // 代号还是 0(首拉在飞),不受影响。
        capturedGeneration = feedViewModel.uiState.value.refreshGeneration

        // 捕获每代真实数据(重建 fullList);与基类的渲染 collector 并行,各订各的。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { state -> onFeedState(state) }
            }
        }

        // 视图重建(旋转等)时强制重载,理由是 fullList 这份过滤真源只活在 Fragment 里,配置变更后
        // 会丢:此时 VM 被保留、其 items 可能停在「上一次搜索的过滤子集」上,新实例若直接把它当
        // fullList 捕获就再也翻不回全量。refresh() 重新一次性拉全 + onFeedState 清空搜索框,得到干净
        // 全量态——正是 legacy 旋转即重新 onFirstLoaded 的行为(此处刻意让出 feeds「配置变更不重载」
        // 这一优化,换 fullList 镜像的正确性)。首次创建 savedInstanceState 为 null,不重复触发。
        if (savedInstanceState != null) {
            feedViewModel.refresh()
        }
    }

    /** 设置页关掉分组开关再回本页(resume)时重算一次;平时是无害 no-op 重提交。 */
    override fun onResume() {
        super.onResume()
        if (view != null) rebuildDisplay()
    }

    override fun onListReady(listView: RecyclerView) {
        // 对齐 legacy initRecyclerView 的 LinearItemDecoration(dp2px(16))。
        listView.addItemDecoration(LinearItemDecoration(16.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(bookedTagRenderer(), bookedTagGroupRenderer(), bookedTagChildRenderer())
    }

    /** 行点击共通:按标签名发筛选广播 + finish。「全部」虚拟行靠 CONTENT=""(空串)表达不过滤。 */
    private fun sendFilterAndFinish(tag: TagsBean) {
        val intent = Intent(
            if (type == 1) Params.FILTER_NOVEL else Params.FILTER_ILLUST,
        ).apply {
            putExtra(Params.CONTENT, tag.name)
            putExtra(Params.STAR_TYPE, starType)
        }
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)
        activity?.finish()
    }

    /** 标签名文案:「#name/译名」或「#name」(虚拟「全部」的 name 空由调用方处理)。 */
    private fun tagLabel(tag: TagsBean): String = when {
        !tag.translated_name.isNullOrEmpty() ->
            String.format("#%s/%s", tag.name, tag.translated_name)
        else -> String.format("#%s", tag.name)
    }

    /**
     * 复刻 BookedTagAdapter.bindData(isMuted=false):
     * name 空→「#全部」;有译名→「#name/译名」;否则→「#name」。count==-1(虚拟行)计数留空,
     * 否则显示「N个作品」。行点击发筛选广播 + finish。
     */
    private fun bookedTagRenderer() = feedRenderer<BookedTagFeedItem, RecyBookTagBinding>(
        inflate = RecyBookTagBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClickListener { sendFilterAndFinish(cell.item.tag) }
            cell.binding.root.setOnLongClickListener { onRealTagLongPress(cell.item.tag) }
        },
    ) { cell ->
        val tag = cell.item.tag
        val b = cell.binding
        when {
            tag.name.isNullOrEmpty() -> b.starSize.setText(R.string.string_155)
            else -> b.starSize.text = tagLabel(tag)
        }
        bindCount(b.illustCount, tag.count)
    }

    /** 父标签分组行:行体单击=按父标签筛选(与普通标签一致);计数+箭头热区=展开/收起。 */
    private fun bookedTagGroupRenderer() = feedRenderer<BookedTagGroupItem, RecyBookTagGroupBinding>(
        inflate = RecyBookTagGroupBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClickListener { sendFilterAndFinish(cell.item.parent) }
            cell.binding.root.setOnLongClickListener { onGroupRowLongPress(cell.item.parent) }
            cell.binding.toggleZone.setOnClickListener {
                toggleGroupExpanded(cell.item.parent.name)
            }
        },
    ) { cell ->
        val item = cell.item
        val b = cell.binding
        b.starSize.text = tagLabel(item.parent)
        bindCount(b.illustCount, item.parent.count)
        b.childCount.text = getString(R.string.tag_group_child_count, item.children.size)
        // 折叠指向右「›」,展开旋转 90° 朝下「⌄」;直接定位不给动画——行增删本身有 item 动画
        b.expandChevron.rotation = if (item.expanded) 90f else 0f
    }

    /** 子标签行(缩进):单击=按该子标签筛选,行为与普通标签行一致;长按=移动/移出分组。 */
    private fun bookedTagChildRenderer() = feedRenderer<BookedTagChildItem, RecyBookTagChildBinding>(
        inflate = RecyBookTagChildBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClickListener { sendFilterAndFinish(cell.item.tag) }
            cell.binding.root.setOnLongClickListener { onChildTagLongPress(cell.item.tag) }
        },
    ) { cell ->
        val tag = cell.item.tag
        val b = cell.binding
        b.starSize.text = tagLabel(tag)
        bindCount(b.illustCount, tag.count)
    }

    private fun bindCount(view: TextView, count: Int) {
        view.text = if (count == -1) "" else getString(R.string.string_156, count)
    }

    private fun toggleGroupExpanded(parentName: String) {
        val key = parentName.trim().lowercase(Locale.getDefault())
        if (!expandedParents.remove(key)) expandedParents.add(key)
        rebuildDisplay()
    }

    // ── 长按归类(分组开关关闭时不响应) ────────────────────────────────────

    /** 归一化键,与 buildGroupedDisplay 的 norm 一致:trim + 小写 */
    private fun normTagKey(name: String?): String =
        name.orEmpty().trim().lowercase(Locale.getDefault())

    /**
     * 长按普通标签行:虚拟行(未分類/全部)不响应;名字命中父标签 → 管理菜单;
     * 否则 → 归类到父标签选择器。
     */
    private fun onRealTagLongPress(tag: TagsBean): Boolean {
        if (!Shaft.sSettings.isTagGroupEnabled || tag.count == -1) return true
        val name = tag.name?.trim().orEmpty()
        if (name.isEmpty()) return true
        tagGroups.firstOrNull { normTagKey(it.group.name) == normTagKey(name) }?.let { group ->
            TagGroupOperate.showGroupMenu(requireContext(), group.group, group.children.size)
            return true
        }
        TagGroupOperate.showAssignToParentPicker(requireContext(), name, tagGroups)
        return true
    }

    /** 长按父标签分组行:管理菜单(添加子标签/重命名/删除/新建父标签),与管理页一致。 */
    private fun onGroupRowLongPress(parent: TagsBean): Boolean {
        if (!Shaft.sSettings.isTagGroupEnabled) return true
        tagGroups.firstOrNull { normTagKey(it.group.name) == normTagKey(parent.name) }?.let { group ->
            TagGroupOperate.showGroupMenu(requireContext(), group.group, group.children.size)
        }
        return true
    }

    /** 长按子标签行:移动到其他父标签 / 移出分组;映射已消失时退化为归类选择器。 */
    private fun onChildTagLongPress(tag: TagsBean): Boolean {
        if (!Shaft.sSettings.isTagGroupEnabled) return true
        val name = tag.name?.trim().orEmpty()
        if (name.isEmpty()) return true
        val key = normTagKey(name)
        val owner = tagGroups.firstOrNull { g -> g.children.any { normTagKey(it.name) == key } }
        val child = owner?.children?.firstOrNull { normTagKey(it.name) == key }
        if (owner != null && child != null) {
            TagGroupOperate.showChildAssignMenu(requireContext(), child, tagGroups)
        } else {
            TagGroupOperate.showAssignToParentPicker(requireContext(), name, tagGroups)
        }
        return true
    }

    // ── 客户端搜索 + 标签分组展示管线 ────────────────────────────────────────────

    private fun setUpSearch() {
        binding.searchClear.setOnClickListener { binding.searchInput.setText("") }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                binding.searchClear.isVisible = q.isNotEmpty()
                currentQuery = q
                pendingFilter?.let { debounceHandler.removeCallbacks(it) }
                val runnable = Runnable { rebuildDisplay() }
                pendingFilter = runnable
                // 清空立即生效(用户明确意图),否则 debounce 200ms。
                debounceHandler.postDelayed(runnable, if (q.isEmpty()) 0L else 200L)
            }
        })
    }

    /**
     * 统一的展示重算(搜索 + 分组共用一条管线),结果经 mutateItems 提交,DiffUtil 派发最小更新。
     * 空搜索恢复下拉刷新;有搜索禁用(对齐 legacy setEnableRefresh(false))。
     */
    private fun rebuildDisplay() {
        // handler 回调可能落在视图销毁之后:onDestroyView 已 removeCallbacks,这里再兜一层。
        if (view == null) return
        val q = currentQuery.trim().lowercase(Locale.getDefault())
        setRefreshEnabled(q.isEmpty() && refreshEnabled)
        val groups = if (Shaft.sSettings.isTagGroupEnabled) tagGroups else emptyList()
        val display = buildGroupedDisplay(fullList, groups, expandedParents, q)
        feedViewModel.mutateItems { display }
    }

    private fun onFeedState(state: FeedUiState) {
        // 只有 refresh 成功的整代提交才推进 refreshGeneration(mutateItems/loadMore 都不会),
        // 借此把「新一代真实数据落地」和「自身过滤 mutate」区分开,避免过滤后把 fullList 打回空。
        if (state.refreshGeneration != capturedGeneration) {
            capturedGeneration = state.refreshGeneration
            fullList = state.items
            // 基类 collector 已把这份平铺列表提交给 adapter;此处按当前分组/搜索态重算一次
            // (分组 LiveData 的首次发射早于首屏数据落地,不重算的话首屏仍是平铺)。
            rebuildDisplay()
            // 对齐 legacy onFirstLoaded:刷新会顺手清掉搜索态(仅在框里有字时清,免得空清触发多余回调)。
            if (!binding.searchInput.text.isNullOrEmpty()) {
                binding.searchInput.setText("")
            }
        }
    }

    override fun onDestroyView() {
        debounceHandler.removeCallbacksAndMessages(null)
        pendingFilter = null
        super.onDestroyView()
    }

    companion object {
        /**
         * @param type 0 插画 / 1 小说([Params.DATA_TYPE])
         * @param starType 公开 / 私人收藏([Params.STAR_TYPE]);TemplateActivity 侧由 EXTRA_KEYWORD 传入
         * (对齐 legacy [ceui.lisa.fragments.FragmentBookedTag.newInstance] 的 EXTRA_KEYWORD→starType 映射)。
         */
        @JvmStatic
        fun newInstance(type: Int, starType: String?): BookedTagFeedFragment {
            return BookedTagFeedFragment().apply {
                arguments = Bundle().apply {
                    putInt(Params.DATA_TYPE, type)
                    putString(Params.STAR_TYPE, starType)
                }
            }
        }
    }
}

/** 标签是否命中查询(name 或 translated_name 大小写不敏感 contains);q 需已 lowercase。 */
private fun TagsBean.matches(lowerQuery: String): Boolean {
    val name = name?.lowercase(Locale.getDefault()).orEmpty()
    val translated = translated_name?.lowercase(Locale.getDefault()).orEmpty()
    return name.contains(lowerQuery) || translated.contains(lowerQuery)
}

/**
 * 收藏标签条目。虚拟行(count==-1)与真实标签用不同前缀 key,保证 DiffUtil 身份唯一
 * (「全部」name="" 与「未分類」也彼此区分)。内容比较靠 data class equals——过滤复用同一份
 * [tag] 实例,同实例即相等,不触发无谓重绑。
 */
data class BookedTagFeedItem(val tag: TagsBean) : FeedItem {
    override val feedKey: Any =
        if (tag.count == -1) "virtual:${tag.name}" else "tag:${tag.name}"
}

/**
 * 父标签分组行。[parent] 是收藏标签里的父标签本体;父标签不在收藏标签里时是合成行
 * (count==-1,作品数留空)。[expanded] 仅是展示态,折叠/展开靠重建本条目触发重绑。
 */
data class BookedTagGroupItem(
    val parent: TagsBean,
    val children: List<TagsBean>,
    val expanded: Boolean,
) : FeedItem {
    override val feedKey: Any = "group:${parent.name}"
}

/** 父标签分组下的子标签行(缩进展示)。 */
data class BookedTagChildItem(val tag: TagsBean) : FeedItem {
    override val feedKey: Any = "tagchild:${tag.name}"
}

/**
 * 平铺收藏标签 → 分组折叠展示列表(纯函数,单测见 BookedTagGroupDisplayTest)。
 *
 * 无搜索:
 * - 虚拟行 `[未分類, 全部]` 照旧置顶;子标签不平铺,归入其父标签行下(默认折叠,展开才插子行)。
 * - 组行出现在父标签原有位置;父标签不在收藏标签里时,在其第一个出现的子标签处插合成父行
 *   (count=-1)。父标签在但其子标签全不在 → 退化为普通标签行(无展开意义)。
 *
 * 有搜索(规则对齐同义词词典 SynonymDictViewModel.rebuild):
 * - 隐藏虚拟行;未分组标签命中才显示;父标签命中 → 父行 + 全部在列表中的子行(自动展开);
 *   仅子标签命中 → 父行 + 命中的子行(自动展开);整组无命中 → 整组隐藏,但保持从属缩进。
 *
 * 分组为空时输出与纯平铺逐字节一致(搜索路径同样回落原逻辑)。
 */
internal fun buildGroupedDisplay(
    fullList: List<FeedItem>,
    groups: List<GroupWithChildren>,
    expandedParents: Set<String>,
    lowerQuery: String,
): List<FeedItem> {
    if (groups.isEmpty()) {
        return if (lowerQuery.isEmpty()) {
            fullList
        } else {
            fullList.filter { item ->
                item is BookedTagFeedItem && item.tag.count != -1 && item.tag.matches(lowerQuery)
            }
        }
    }

    val virtualRows = ArrayList<FeedItem>(2)
    val realTags = ArrayList<TagsBean>(fullList.size)
    fullList.forEach { item ->
        if (item is BookedTagFeedItem) {
            if (item.tag.count == -1) virtualRows.add(item) else realTags.add(item.tag)
        }
    }

    // 归一化键:标签名 trim + lowercase(手工配置的分组难免大小写差异,如 "Genshin"/"genshin")
    fun norm(name: String?): String = name.orEmpty().trim().lowercase(Locale.getDefault())

    val groupByNorm = HashMap<String, GroupWithChildren>(groups.size)
    val childOwner = HashMap<String, GroupWithChildren>()
    groups.forEach { g ->
        groupByNorm[norm(g.group.name)] = g
        g.children.forEach { childOwner[norm(it.name)] = g }
    }

    // 每组在收藏标签中实际可见的子标签(保持列表原顺序),以及父标签本体是否在列表中
    val presentChildren = HashMap<String, MutableList<TagsBean>>()
    val parentBean = HashMap<String, TagsBean>()
    realTags.forEach { tag ->
        val key = norm(tag.name)
        if (groupByNorm.containsKey(key)) {
            parentBean[key] = tag
        } else {
            val owner = childOwner[key]
            if (owner != null) {
                presentChildren.getOrPut(norm(owner.group.name)) { ArrayList() }.add(tag)
            }
        }
    }

    // 搜索期:每组命中的子标签子集(父命中时不用它,展示全部可见子标签)
    val matchedChildren = HashMap<String, List<TagsBean>>()
    if (lowerQuery.isNotEmpty()) {
        presentChildren.forEach { (ownerNorm, list) ->
            matchedChildren[ownerNorm] = list.filter { it.matches(lowerQuery) }
        }
    }

    val searching = lowerQuery.isNotEmpty()
    val result = ArrayList<FeedItem>(fullList.size)
    if (!searching) result.addAll(virtualRows)
    val emitted = HashSet<String>()

    fun emitGroup(groupNorm: String, children: List<TagsBean>, expanded: Boolean) {
        if (!emitted.add(groupNorm)) return
        val parent = parentBean[groupNorm] ?: TagsBean().apply {
            name = groupByNorm[groupNorm]?.group?.name.orEmpty()
            count = -1
        }
        result.add(BookedTagGroupItem(parent, children, expanded))
        if (expanded) {
            children.forEach { result.add(BookedTagChildItem(it)) }
        }
    }

    realTags.forEach { tag ->
        val key = norm(tag.name)
        if (groupByNorm.containsKey(key)) {
            // 父标签行(无论搜索与否都在它的原有位置出组行;搜索时未命中且无子命中则整组隐藏)
            if (searching) {
                if (tag.matches(lowerQuery)) {
                    emitGroup(key, presentChildren[key].orEmpty(), expanded = true)
                } else {
                    val hits = matchedChildren[key].orEmpty()
                    if (hits.isNotEmpty()) emitGroup(key, hits, expanded = true)
                }
            } else {
                val children = presentChildren[key].orEmpty()
                if (children.isEmpty()) {
                    // 父在但子全不在 → 普通标签行
                    result.add(BookedTagFeedItem(tag))
                } else {
                    emitGroup(key, children, key in expandedParents)
                }
            }
            return@forEach
        }
        val owner = childOwner[key]
        if (owner != null) {
            val ownerNorm = norm(owner.group.name)
            if (searching) {
                if (!tag.matches(lowerQuery)) return@forEach
                // 仅子命中:父行在此取出(或合成),列命中子集;父行在前面已展开全部时跳过
                emitGroup(ownerNorm, matchedChildren[ownerNorm].orEmpty(), expanded = true)
            } else if (ownerNorm !in emitted && !parentBean.containsKey(ownerNorm)) {
                // 父标签不在收藏标签里:在其第一个子标签处插合成父行
                emitGroup(ownerNorm, presentChildren[ownerNorm].orEmpty(), ownerNorm in expandedParents)
            }
            // 父标签在列表里 → 已在/将在父标签位置出组行,此处跳过
            return@forEach
        }
        // 普通未分组标签
        if (searching && !tag.matches(lowerQuery)) return@forEach
        result.add(BookedTagFeedItem(tag))
    }
    return result
}

/**
 * 收藏标签数据源:一次性把所有分页拉全后返回单页(nextCursor 恒 null),让客户端搜索简单且忠实
 * (legacy 搜索时本就要预加载全部,收藏夹标签又是有界集合)。首屏 load() 内:先取第一页,
 * 再循环 getNextTags(next) 累积到 nextUrl 空。
 * 拉全后在真实标签前拼两个虚拟行 `[未分類, 全部]`(对齐 legacy onFirstLoaded 的插入顺序)。
 *
 * 防御性上限 [MAX_PAGES]:异常数据(nextUrl 不收敛)时截断并 log,已拉的部分照常可搜。
 *
 * 零 Fragment 捕获:只持有 type/starType 两个基本类型。
 */
class BookedTagFeedSource(
    private val type: Int,
    private val starType: String?,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val items: List<FeedItem> = withContext(Dispatchers.IO) {
            val api = Retro.getAppApi()
            val uid = SessionManager.loggedInUid
            val realTags = ArrayList<TagsBean>()
            // starType 为 null 时 Retrofit 省略 restrict query（服务端默认 public），与 legacy 一致。
            val first = if (type == 1) {
                api.getAllNovelBookmarkTags(uid, starType)
            } else {
                api.getAllIllustBookmarkTags(uid, starType)
            }
            realTags.addAll(first.list.orEmpty())
            var next: String? = first.nextUrl
            var hops = 0
            while (!next.isNullOrEmpty()) {
                if (hops >= MAX_PAGES) {
                    Timber.w("BookedTagFeedSource: 预加载达页数上限 %d，收藏标签可能未取全", MAX_PAGES)
                    break
                }
                // 某一页失败即止步、保留已加载部分（对齐 legacy preloadOne/finishPreload 的降级：首屏 +
                // 已翻页照常可搜，不因中途某页出错把整页拖成错误态）。首页 initApi 失败仍照常抛出走错误态。
                val page = try {
                    api.getNextTags(next)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Timber.w(e, "BookedTagFeedSource: 预加载第 %d 页失败，保留已加载部分可搜", hops + 2)
                    break
                }
                realTags.addAll(page.list.orEmpty())
                next = page.nextUrl
                hops++
            }
            buildDisplayItems(realTags)
        }
        return FeedPage(items, null)
    }

    /** 真实标签前拼两个虚拟行:先「未分類」后「全部」→ 最终顺序 `[未分類, 全部, ...realTags]`。 */
    private fun buildDisplayItems(realTags: List<TagsBean>): List<FeedItem> {
        val unSeparated = TagsBean().apply {
            count = -1
            name = "未分類"
        }
        val all = TagsBean().apply {
            count = -1
            name = ""
        }
        val result = ArrayList<FeedItem>(realTags.size + 2)
        result.add(BookedTagFeedItem(unSeparated))
        result.add(BookedTagFeedItem(all))
        realTags.forEach { result.add(BookedTagFeedItem(it)) }
        return result
    }

    companion object {
        /** 预加载页数上限,防 nextUrl 不收敛时无限翻页(收藏夹标签实际远小于此)。 */
        private const val MAX_PAGES = 50
    }
}
