package ceui.pixiv.db.taggroup

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标签分组 —— 子标签。
 *
 * 「子标签」= 归到某个父标签下的标签名，通常是角色名（如 "宵"、"神里綾華"）。
 * 在收藏标签筛选列表里子标签折叠显示在父标签行下，不再平铺占位。
 *
 * name 全局唯一（unique 索引）：一个子标签只能归属一个父标签，归类是单选的。
 */
@Entity(
    tableName = "tag_group_child_table",
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["name"], unique = true),
    ]
)
data class TagGroupChildEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属父标签 [TagGroupEntity.id] */
    val groupId: Long,
    /** 子标签名（与收藏标签 / 作品标签原文一致） */
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)
