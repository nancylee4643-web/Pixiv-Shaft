package ceui.pixiv.db.taggroup

import androidx.room.Embedded
import androidx.room.Relation

/**
 * 父标签 + 它名下的全部子标签，一次性查出（管理页树形列表 / 筛选页分组共用）。
 */
data class GroupWithChildren(
    @Embedded val group: TagGroupEntity,
    @Relation(parentColumn = "id", entityColumn = "groupId")
    val children: List<TagGroupChildEntity>,
)
