package ceui.pixiv.db.taggroup

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标签分组 —— 父标签（组）。
 *
 * 「父标签」= 收藏标签筛选列表里用于收纳子标签的组名，通常是作品名（如 "原神"）。
 * 名字不允许含空格（Pixiv 用空格作为标签分隔符，对齐同义词词典 [ceui.pixiv.db.synonym.SynonymTargetEntity] 的约束）。
 * 分组是全局的：插画 / 漫画 / 小说收藏的按标签筛选共用同一套分组。
 */
@Entity(
    tableName = "tag_group_table",
    indices = [Index(value = ["name"], unique = true)]
)
data class TagGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 父标签名（组名），全局唯一，不允许空格 */
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)
