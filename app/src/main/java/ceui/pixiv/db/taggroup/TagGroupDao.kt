package ceui.pixiv.db.taggroup

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 标签分组 DAO。
 *
 * 分组规模通常在几十条以内，沿用本库 allowMainThreadQueries 的同步访问模式
 * （同 [ceui.pixiv.db.synonym.SynonymDao]），LiveData 查询天然异步。
 */
@Dao
interface TagGroupDao {

    // ---------- 查询 ----------

    /** 全部父标签 + 各自子标签，管理页 / 筛选页 observe 用 */
    @Transaction
    @Query("SELECT * FROM tag_group_table ORDER BY createdAt ASC")
    fun getAllWithChildrenLive(): LiveData<List<GroupWithChildren>>

    /** 全部父标签 + 各自子标签，同步版 */
    @Transaction
    @Query("SELECT * FROM tag_group_table ORDER BY createdAt ASC")
    fun getAllWithChildren(): List<GroupWithChildren>

    @Query("SELECT * FROM tag_group_table WHERE name = :name LIMIT 1")
    fun getGroupByName(name: String): TagGroupEntity?

    /** 子标签名已被哪些组使用（name 全局唯一，理论至多一条；大小写不敏感归一在 UI 层做） */
    @Query("SELECT * FROM tag_group_child_table WHERE name = :name")
    fun getChildrenByName(name: String): List<TagGroupChildEntity>

    @Query("SELECT * FROM tag_group_child_table WHERE groupId = :groupId ORDER BY createdAt ASC")
    fun getChildrenOfGroup(groupId: Long): List<TagGroupChildEntity>

    @Query("SELECT COUNT(*) FROM tag_group_table")
    fun countGroups(): Int

    // ---------- 父标签 ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertGroup(group: TagGroupEntity): Long

    @Query("UPDATE tag_group_table SET name = :newName WHERE id = :groupId")
    fun renameGroup(groupId: Long, newName: String)

    @Query("DELETE FROM tag_group_table WHERE id = :groupId")
    fun deleteGroupOnly(groupId: Long)

    @Query("DELETE FROM tag_group_child_table WHERE groupId = :groupId")
    fun deleteChildrenOfGroup(groupId: Long)

    /** 删除父标签 + 它名下全部子标签（UI 层负责二次确认） */
    @Transaction
    fun deleteGroupWithChildren(groupId: Long) {
        deleteChildrenOfGroup(groupId)
        deleteGroupOnly(groupId)
    }

    // ---------- 子标签 ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertChild(child: TagGroupChildEntity): Long

    @Query("UPDATE tag_group_child_table SET name = :newName WHERE id = :id")
    fun renameChild(id: Long, newName: String)

    /** 移动子标签到另一个父标签 */
    @Query("UPDATE tag_group_child_table SET groupId = :newGroupId WHERE id = :id")
    fun moveChildToGroup(id: Long, newGroupId: Long)

    @Query("DELETE FROM tag_group_child_table WHERE id = :id")
    fun deleteChildById(id: Long)

    // ---------- 清空 ----------

    @Query("DELETE FROM tag_group_table")
    fun deleteAllGroups()

    @Query("DELETE FROM tag_group_child_table")
    fun deleteAllChildren()

    @Transaction
    fun clearAll() {
        deleteAllChildren()
        deleteAllGroups()
    }
}
