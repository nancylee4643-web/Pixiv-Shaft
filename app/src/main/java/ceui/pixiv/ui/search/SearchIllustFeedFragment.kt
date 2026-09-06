package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.model.ListIllust
import ceui.lisa.repo.SearchIllustRepo
import ceui.lisa.utils.Common
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.appServices
import ceui.pixiv.db.synonym.SynonymMatcher
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.search.v3.SearchTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import ceui.pixiv.ui.usage.observeNana7miQuotaNotice

/**
 * 搜索「插画/漫画」tab（feeds 框架版，替代 legacy FragmentSearchIllust + SearchIllustRepo + IAdapter）。
 * 卡片复用 [IllustFeedFragment] 的标准瀑布流插画卡。
 *
 * 搜索链路重（sort 路由 / 内置热门榜 / 投稿期间档 / 关键字后缀 / R18 三态 + 仅看 AI + starSize
 * 客户端过滤）——**为无损、零发散，数据源直接包裹既有的 [SearchIllustRepo]**（复刻它全部逻辑风险太大），
 * 直接调它的 suspend initApi/initNextApi，过滤走 repo 自己的 FilterMapper。过滤后已是「搜索专属过滤过」的 bean，
 * 用 [IllustFeedItem.raw] 直接建条目（**绝不能走 .of，会在仅看 AI 时误删 AI**）。
 *
 * 响应式重搜：数据源读 activity-scoped [SearchModel] 最新参数（不快照），fragment observe nowGo →
 * 命中标签匹配档才 refresh（对齐 legacy 的 TAG_MATCH_VALUE guard，防选了小说专属 target 时插画也重搜）。
 */
class SearchIllustFeedFragment : IllustFeedFragment() {

    private var searchRefreshPending = false

    private val searchModel: SearchModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[SearchModel::class.java]
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获：捕获 activity-scoped SearchModel（≥ Activity 生命周期），先取局部 val
        val searchModel = ViewModelProvider(requireActivity())[SearchModel::class.java]
        // 进程级服务也先取局部 val（应用级对象，不延长任何 Fragment 生命周期）
        val services = requireContext().appServices()
        SearchIllustFeedSource(searchModel) {
            // 8 个必填参数先给 null，全部由 update(searchModel) 填；构造时 super 已建好 FilterMapper。
            SearchIllustRepo(
                null, null, null, null, null, null, null, null,
                nana7miOutbox = services.accountOnlineReportOutbox,
                nana7miTelemetryService = services.nana7miSearchTelemetry,
                remoteAppConfig = services.remoteAppConfig,
            )
        }
    }

    override val emptyStateText: CharSequence
        get() = SearchRiskPolicy.withheldQuery(searchModel.keyword.value)?.let { query ->
            getString(R.string.search_results_withheld_notice, query)
        } ?: super.emptyStateText

    /**
     * 不把搜索游标交给详情页 pager 续读（基类默认会交）。
     *
     * 基类那句默认（「pixiv 列表的游标本身就是 nextUrl，详情页划到底可以照着它继续请求」）隐含
     * 一个前提：**nextUrl 拉回来的东西就是本列表的结果集**。搜索不满足——搜索的结果集是由
     * [ceui.lisa.repo.SearchIllustRepo] 的 FilterMapper 流水线定义的（R-18 三态 / 仅看 AI /
     * 收藏数门槛 / 隐藏已收藏），nextUrl 只是那条流水线的入料。
     *
     * 而详情 pager 的回传链复现不了这条流水线：VActivity 用的是裸 `Mapper`（不认 searchR18Restriction
     * / searchOnlyAi），回到本页 `feedItemFromBean` 默认走 [IllustFeedItem.of] →
     * `passesContentFilters`（只有全局过滤链）。两头都丢，于是：
     * - R-18 限制选「仅安全」→ 续拉页整页 R-18 全部放行，追回列表；
     * - 「仅看 AI」+ 全局「屏蔽 AI 作品」开 → 首屏靠 FilterMapper 的 `!searchOnlyAi` 让步保住 AI，
     *   续拉页没有这个让步，`passesContentFilters` 把 AI 全删干净 —— 与用户诉求正好相反。
     *
     * 交 null 即关掉续读：详情页仍可在交接来的快照里翻，只是划到底不再自动续拉（对齐所有本地源
     * 的既有做法）。要恢复续读，得先让回传链拿得到搜索档位、能一比一复现 FilterMapper，
     * 而不是把这个游标交出去。[IllustFeedItem.raw] 的文档已经写明「搜索专属过滤 feeds 侧
     * 不复刻」——本页正是那条禁令的适用对象。
     */
    override val detailContinuationCursor: String?
        get() = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 撞热度排序额度时结果会静默降级成预览，这条提示是用户唯一能知道原因的地方。
        observeNana7miQuotaNotice()
        searchModel.nowGo.observe(viewLifecycleOwner) {
            // 普通查询仍只在「标签匹配」档响应（对齐 legacy）。命中本地策略时则所有
            // 分栏都必须刷新为空，避免切换 tab 后短暂看到上一次搜索留下的结果。
            val shouldWithhold = SearchRiskPolicy.shouldWithhold(searchModel.keyword.value)
            if (shouldWithhold) {
                // 本地短路不会发网络；离屏页也立即清空，避免 ViewPager 滑动过程中在
                // onResume 之前露出上一代结果。
                searchRefreshPending = false
                feedViewModel.refresh()
            } else if (PixivSearchParamUtil.TAG_MATCH_VALUE.contains(searchModel.searchType.value)) {
                // ViewPager 的离屏页仍处于 STARTED，LiveData 也会通知它。只让当前
                // RESUMED tab 立即请求；离屏页合并成一次待刷新，等用户真正切过来再搜。
                if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    searchRefreshPending = false
                    feedViewModel.refresh()
                } else {
                    searchRefreshPending = true
                }
            } else {
                // A later event may target only the novel tab. Do not carry an older pending
                // illustration refresh across that newer, incompatible SearchModel snapshot.
                searchRefreshPending = false
            }
        }
    }

    override fun onResume() {
        // 先快照切换前的加载状态：super.onResume() 会为从未加载的页面执行
        // ensureLoaded()，这种情况已经会使用最新 SearchModel，不能紧接着再 refresh 一次。
        // 旧页面已加载或旧请求还在跑时，则需要显式 refresh 替换成最新条件。
        val hadExistingLoad = feedViewModel.uiState.value.let {
            it.hasLoadedOnce || it.refresh is LoadState.Loading
        }
        super.onResume()
        if (searchRefreshPending) {
            searchRefreshPending = false
            if (hadExistingLoad) {
                feedViewModel.refresh()
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(): SearchIllustFeedFragment = SearchIllustFeedFragment()
    }
}

/**
 * 搜索插画数据源：包裹 [SearchIllustRepo]。load(null) 前 `update(searchModel)` 重读最新参数 +
 * 配置 FilterMapper（R18 三档 / onlyAi / starSize）；load(cursor) 用 repo 翻页。过滤走 repo.mapper()
 * （FilterMapper，含 legacy 全部搜索过滤 + ObjectPool 合池，setValue 失败自动 postValue 兜底，off-main 安全）。
 *
 * 同义词扩大搜索（设置开关 + 标签匹配档 + 词典命中时激活）：关键词各词按词典展开变体组，
 * 笛卡尔积出多条 lane 查询（见 [SynonymSearchExpansion]），每 lane 一个独立 Repo 并发请求、
 * 结果按 lane 序合并——跨路重复由 FeedViewModel 按 identity（illust id）去重。主路（原始查询）
 * 失败整页报错，扩展路失败静默丢弃。多路 next_url 经 [SynonymSearchExpansion.encodeCursor]
 * 编进一个复合游标翻页；未激活扩展时走下方单路路径，行为与本功能加入前完全一致。
 */
class SearchIllustFeedSource(
    private val searchModel: SearchModel,
    /** 只在首页真正要发请求时才调；风险拦截命中的首页不会建 Repo。 */
    private val repoFactory: () -> SearchIllustRepo,
) : FeedSource<String> {

    private var repo: SearchIllustRepo? = null

    /** 扩展激活的当前代：各 lane 的 Repo，下标 0 = 原始查询主路；null = 本代走单路路径。 */
    private var laneRepos: List<SearchIllustRepo>? = null

    /** 已 Toast 提示过扩展的原始关键词——同一词反复刷新不重复提示。 */
    private var lastToastedKeyword: String? = null

    /** 一代扩展方案：lane 查询串 + 提示用的新增变体词。 */
    private class ExpansionPlan(val laneQueries: List<String>, val addedWords: List<String>)

    override suspend fun load(cursor: String?): FeedPage<String> {
        // 策略只决定一代搜索的首页；翻页沿用这一代已经固定的 nextUrl。每次键入会更新
        // SearchModel，但未提交前不应截断屏幕上那一代安全结果的翻页。
        val keywordSnapshot = if (cursor == null) searchModel.keyword.value.orEmpty() else null
        if (keywordSnapshot != null) {
            val shouldWithhold = if (SearchRiskPolicy.isWarmedUp()) {
                SearchRiskPolicy.shouldWithhold(keywordSnapshot)
            } else {
                withContext(Dispatchers.Default) {
                    SearchRiskPolicy.shouldWithhold(keywordSnapshot)
                }
            }
            if (shouldWithhold) {
                laneRepos = null
                return FeedPage(emptyList(), null)
            }
        }

        // 多路复合游标翻页
        if (cursor != null) {
            val laneUrls = SynonymSearchExpansion.decodeCursor(cursor)
            if (laneUrls != null) {
                return loadExpansionNextPage(laneUrls)
            }
        }

        // 首页：词典命中则多路并发
        if (cursor == null) {
            val plan = resolveExpansionPlan(keywordSnapshot!!)
            if (plan.laneQueries.size > 1) {
                return loadExpansionFirstPage(keywordSnapshot, plan)
            }
            laneRepos = null
        }

        // 单路路径（无扩展首页 / 裸 next_url 翻页）
        val r = repo ?: repoFactory().also { repo = it }
        // initApi / initNextApi 是 suspend：借号、缓存查询、Pixiv 请求全在各自的挂起点里切线程，
        // 这里不用再包 withContext(IO)。（搜索历史写入已上移到 SearchActivity，update 不做 Room I/O。）
        val list: ListIllust = if (cursor == null) {
            r.update(searchModel, keywordSnapshot) // 与上面的策略判断共用同一 keyword 快照
            r.initApi()
        } else {
            r.nextUrl = cursor
            r.initNextApi()
        }
        val items = withContext(Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            val filtered = r.mapper().apply(list)
            // FilterMapper 已做完全部搜索专属过滤 → 直接建条目，不再过滤（否则仅看 AI 误删 AI）。
            filtered.list.orEmpty().mapNotNull { IllustFeedItem.raw(it) }
        }
        return FeedPage(items, list.nextUrl?.takeIf { it.isNotEmpty() })
    }

    // ---------- 同义词扩大搜索 ----------

    /**
     * 门控 + 查词典 + 组合 lane：总开关/扩展开关、标签匹配档（partial/exact/null 默认档；
     * title_and_caption 是标题简介搜索不扩展）、词典命中。任何失败回退单路（返回单条 lane）。
     */
    private suspend fun resolveExpansionPlan(keyword: String): ExpansionPlan {
        if (keyword.isBlank() ||
            !Shaft.sSettings.isSynonymDictEnabled ||
            !Shaft.sSettings.isSynonymExpandSearchEnabled
        ) {
            return singleLanePlan()
        }
        val target = searchModel.searchType.value
        val isTagSearch = target == null ||
                target == SearchTarget.PartialMatchForTags.apiValue ||
                target == SearchTarget.ExactMatchForTags.apiValue
        if (!isTagSearch) {
            return singleLanePlan()
        }
        return try {
            withContext(Dispatchers.IO) {
                val dict = AppDatabase.getAppDatabase(Shaft.getContext())
                    .synonymDao()
                    .getAllWithSynonyms()
                if (dict.isEmpty()) {
                    return@withContext singleLanePlan()
                }
                val groups = SynonymMatcher.keywordVariantGroups(keyword, dict)
                    // 风险策略逐变体过滤（原词已由上面整句 shouldWithhold 覆盖）；
                    // 词表此刻已解密，contains 扫描无额外开销
                    .map { group ->
                        if (group.size <= 1) group
                        else listOf(group[0]) + group.drop(1).filterNot(SearchRiskPolicy::shouldWithhold)
                    }
                val laneQueries = SynonymSearchExpansion.buildLaneQueries(groups)
                if (laneQueries.size <= 1) {
                    singleLanePlan()
                } else {
                    ExpansionPlan(laneQueries, groups.flatMap { it.drop(1) }.distinct())
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.e(e, "synonym search expansion resolve failed, fallback to single lane")
            singleLanePlan()
        }
    }

    private fun singleLanePlan(): ExpansionPlan = ExpansionPlan(emptyList(), emptyList())

    /** 扩展首页：每 lane 一个全新 Repo（借号会话 / FilterMapper 都是实例级状态）并发请求。 */
    private suspend fun loadExpansionFirstPage(keyword: String, plan: ExpansionPlan): FeedPage<String> {
        val repos = plan.laneQueries.map { repoFactory() }
        val results = fetchLanes(repos) { index, repo ->
            repo.update(searchModel, plan.laneQueries[index])
            repo.initApi()
        }
        val page = assembleLanePage(repos, results)
        // 首页成功后才登记本代 lane：失败时 refresh 的游标仍是旧一代的，laneRepos 也必须
        // 留在旧一代，旧游标翻页才能配上旧 lane 的借号会话
        laneRepos = repos
        notifyExpansionOnce(keyword, plan)
        return page
    }

    /** 扩展翻页：复合游标解码出各路 next_url，逐路推进；lane 状态与游标不一致时防御性终止。 */
    private suspend fun loadExpansionNextPage(laneUrls: List<String>): FeedPage<String> {
        val repos = laneRepos
        if (repos == null || repos.size != laneUrls.size) {
            Timber.w(
                "synonym lanes cursor mismatch: lanes=%s urls=%s, end pagination",
                repos?.size, laneUrls.size,
            )
            laneRepos = null
            return FeedPage(emptyList(), null)
        }
        val results = fetchLanes(repos) { index, repo ->
            val url = laneUrls[index]
            if (url.isEmpty()) {
                null // 该路已到底（编码时空串占位）
            } else {
                repo.nextUrl = url
                repo.initNextApi()
            }
        }
        return assembleLanePage(repos, results)
    }

    /**
     * 并发跑全部 lane。主路（下标 0）失败向上抛（整页进 Error，用户可重试）；
     * 扩展路失败 / [request] 返回 null（已到底）→ 该 lane 记 null，丢弃本页与游标。
     * CancellationException 一律上抛。
     */
    private suspend fun fetchLanes(
        repos: List<SearchIllustRepo>,
        request: suspend (index: Int, repo: SearchIllustRepo) -> ListIllust?,
    ): List<ListIllust?> = coroutineScope {
        repos.mapIndexed { index, repo ->
            async {
                try {
                    request(index, repo)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    if (index == 0) throw t
                    Timber.e(t, "synonym search lane %d failed, dropped", index)
                    null
                }
            }
        }.awaitAll()
    }

    /** 各路结果过各自 Repo 的 FilterMapper 后按 lane 序拼接，next_url 编回复合游标。 */
    private suspend fun assembleLanePage(
        repos: List<SearchIllustRepo>,
        results: List<ListIllust?>,
    ): FeedPage<String> {
        val items = withContext(Dispatchers.Default) {
            results.mapIndexedNotNull { index, list ->
                if (list == null) return@mapIndexedNotNull null
                @Suppress("UNCHECKED_CAST")
                val filtered = repos[index].mapper().apply(list)
                // FilterMapper 已做完全部搜索专属过滤 → 直接建条目，不再过滤（否则仅看 AI 误删 AI）。
                filtered.list.orEmpty().mapNotNull { IllustFeedItem.raw(it) }
            }.flatten()
        }
        val cursor = SynonymSearchExpansion.encodeCursor(results.map { it?.nextUrl })
        return FeedPage(items, cursor)
    }

    /** 扩展生效的轻提示：同一原始关键词只提示一次（source 随 VM 存活，跨刷新有效）。 */
    private suspend fun notifyExpansionOnce(keyword: String, plan: ExpansionPlan) {
        if (plan.addedWords.isEmpty() || lastToastedKeyword == keyword) return
        lastToastedKeyword = keyword
        withContext(Dispatchers.Main) {
            Common.showToast(
                Shaft.getContext().getString(
                    R.string.synonym_search_expand_toast,
                    plan.addedWords.joinToString("、"),
                ),
                1,
            )
        }
    }
}
