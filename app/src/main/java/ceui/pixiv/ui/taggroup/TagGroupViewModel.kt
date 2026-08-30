package ceui.pixiv.ui.taggroup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.taggroup.TagGroupChildEntity
import ceui.pixiv.db.taggroup.TagGroupEntity

/**
 * 标签分组管理页 ViewModel（仿 [ceui.pixiv.ui.synonym.SynonymDictViewModel] 简化版）。
 *
 * 数据全部在这里：分组 LiveData、展开状态；Fragment 只渲染 + 转发点击。
 * 分组是用户手动配置的小数据集（几十条级），主线程 rebuild 即可，
 * 不需要同义词词典的后台 executor。
 */
class TagGroupViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getAppDatabase(application).tagGroupDao()

    /** 已展开的父标签 id（默认折叠） */
    private val expandedGroupIds = MutableLiveData<Set<Long>>(emptySet())

    private val allGroups = dao.getAllWithChildrenLive()

    /** 扁平化后的树形列表（父标签行 + 缩进子标签行），随分组 / 展开状态变化自动重建 */
    val displayItems = MediatorLiveData<List<DictItem>>().apply {
        addSource(allGroups) { rebuild() }
        addSource(expandedGroupIds) { rebuild() }
    }

    /** 总数统计（父标签数 to 子标签数），过滤前的全量 */
    val totalCount = MediatorLiveData<Pair<Int, Int>>().apply {
        addSource(allGroups) { list ->
            value = (list?.size ?: 0) to (list?.sumOf { it.children.size } ?: 0)
        }
    }

    /** 展开 ↔ 折叠某个父标签 */
    fun toggleExpanded(groupId: Long) {
        val current = expandedGroupIds.value.orEmpty()
        expandedGroupIds.value = if (groupId in current) current - groupId else current + groupId
    }

    private fun rebuild() {
        val entries = allGroups.value.orEmpty()
        val expanded = expandedGroupIds.value.orEmpty()
        val items = ArrayList<DictItem>(entries.size * 2)
        entries.forEach { entry ->
            val isExpanded = entry.group.id in expanded
            items.add(DictItem.Group(entry.group, entry.children.size, isExpanded))
            if (isExpanded) {
                entry.children.forEach { items.add(DictItem.Child(it)) }
            }
        }
        displayItems.value = items
    }

    /** 树形列表的两种行 */
    sealed class DictItem {
        data class Group(
            val entity: TagGroupEntity,
            val childCount: Int,
            val expanded: Boolean,
        ) : DictItem()

        data class Child(val entity: TagGroupChildEntity) : DictItem()
    }
}
