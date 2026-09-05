package ceui.pixiv.db.synonym

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 同义词词典 DAO（issue #904）。
 *
 * 词典规模通常在几百条以内，沿用本库 allowMainThreadQueries 的同步访问模式
 * （同 [ceui.lisa.database.SearchDao]），LiveData 查询天然异步。
 */
@Dao
interface SynonymDao {

    // ---------- 查询 ----------

    /** 全部目标标签 + 各自同义词，管理页 / 匹配框 observe 用 */
    @Transaction
    @Query("SELECT * FROM synonym_target_table ORDER BY createdAt ASC")
    fun getAllWithSynonymsLive(): LiveData<List<TargetWithSynonyms>>

    /** 全部目标标签 + 各自同义词，同步版（匹配引擎 / 按标签收藏命中置顶用） */
    @Transaction
    @Query("SELECT * FROM synonym_target_table ORDER BY createdAt ASC")
    fun getAllWithSynonyms(): List<TargetWithSynonyms>

    @Query("SELECT * FROM synonym_target_table WHERE name = :name LIMIT 1")
    fun getTargetByName(name: String): SynonymTargetEntity?

    @Query("SELECT * FROM synonym_target_table WHERE id = :id LIMIT 1")
    fun getTargetById(id: Long): SynonymTargetEntity?

    /** 重复检测：某个同义词名已存在于哪些目标标签下 */
    @Query("SELECT * FROM synonym_tag_table WHERE name = :name")
    fun getSynonymsByName(name: String): List<SynonymTagEntity>

    /** 某目标下全部同义词 —— 导入/添加时做大小写不敏感的重复检测（issue #905） */
    @Query("SELECT * FROM synonym_tag_table WHERE targetId = :targetId ORDER BY createdAt ASC")
    fun getSynonymsOfTarget(targetId: Long): List<SynonymTagEntity>

    /**
     * 最近使用的 N 个目标标签 ——「添加为同义词」/「移动到其他目标标签」菜单用
     * （词典可能数千个目标，菜单只列最近的）。
     * issue #910：按 lastUsedAt 倒序（手动输入/合并旧目标后也会冒泡到顶部），createdAt 作次序兜底。
     */
    @Query("SELECT * FROM synonym_target_table ORDER BY lastUsedAt DESC, createdAt DESC LIMIT :limit")
    fun getRecentTargets(limit: Int): List<SynonymTargetEntity>

    /** 目标标签总数 —— 清空词典确认框用（同步查询，COUNT 毫秒级） */
    @Query("SELECT COUNT(*) FROM synonym_target_table")
    fun countTargets(): Int

    // ---------- 目标标签 ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTarget(target: SynonymTargetEntity): Long

    @Query("UPDATE synonym_target_table SET name = :newName WHERE id = :targetId")
    fun renameTarget(targetId: Long, newName: String)

    /** 刷新「最近使用」时间（issue #910）：目标收到新同义词 / 被移入 / 被合并入时调用 */
    @Query("UPDATE synonym_target_table SET lastUsedAt = :ts WHERE id = :targetId")
    fun touchTarget(targetId: Long, ts: Long)

    @Query("DELETE FROM synonym_target_table WHERE id = :targetId")
    fun deleteTargetOnly(targetId: Long)

    @Query("DELETE FROM synonym_tag_table WHERE targetId = :targetId")
    fun deleteSynonymsOfTarget(targetId: Long)

    /** 删除目标标签 + 它名下全部同义词（issue：删除不同步删除用户已收藏标签，要二次确认 —— UI 层负责确认） */
    @Transaction
    fun deleteTargetWithSynonyms(targetId: Long) {
        deleteSynonymsOfTarget(targetId)
        deleteTargetOnly(targetId)
    }

    // ---------- 同义词标签 ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSynonym(synonym: SynonymTagEntity): Long

    @Query("UPDATE synonym_tag_table SET name = :newName, remark = :remark WHERE id = :id")
    fun updateSynonym(id: Long, newName: String, remark: String?)

    @Query("UPDATE synonym_tag_table SET remark = :remark WHERE id = :id")
    fun updateSynonymRemark(id: Long, remark: String?)

    /** 移动同义词到另一个目标标签（issue #905：同义词移动 / 目标标签合并共用） */
    @Query("UPDATE synonym_tag_table SET targetId = :newTargetId WHERE id = :id")
    fun moveSynonymToTarget(id: Long, newTargetId: Long)

    @Query("DELETE FROM synonym_tag_table WHERE id = :id")
    fun deleteSynonymById(id: Long)

    /** 批量删除冗余同义词 —— 一次性去重清理用（issue #905） */
    @Query("DELETE FROM synonym_tag_table WHERE id IN (:ids)")
    fun deleteSynonymsByIds(ids: List<Long>)

    // ---------- 清空词典（管理页退路：用户不想要内置词典时一键清掉） ----------

    @Query("DELETE FROM synonym_target_table")
    fun deleteAllTargets()

    @Query("DELETE FROM synonym_tag_table")
    fun deleteAllSynonyms()

    @Transaction
    fun clearAll() {
        deleteAllSynonyms()
        deleteAllTargets()
    }
}
