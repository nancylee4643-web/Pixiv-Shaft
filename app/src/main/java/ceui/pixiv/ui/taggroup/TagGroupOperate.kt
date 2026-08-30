package ceui.pixiv.ui.taggroup

import android.content.Context
import android.text.InputType
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.Common
import ceui.pixiv.db.taggroup.TagGroupChildEntity
import ceui.pixiv.db.taggroup.TagGroupDao
import ceui.pixiv.db.taggroup.TagGroupEntity
import ceui.pixiv.witstudio.dialog.WitDialog

/**
 * 标签分组共享操作弹窗（仿 [ceui.pixiv.ui.synonym.SynonymOperate]）。
 *
 * 校验规则对齐同义词词典：标签名非空、不含空格（Pixiv 用空格作为标签分隔符）；
 * 父标签名全局唯一；子标签名全局唯一（一个子标签只归属一个父标签）。
 *
 * 分组是小数据集，DB 读写沿用本库 allowMainThreadQueries 的主线程同步模式；
 * 写入后由 Room LiveData 驱动管理页与筛选页自动刷新。
 */
object TagGroupOperate {

    private fun dao(context: Context): TagGroupDao =
        AppDatabase.getAppDatabase(context).tagGroupDao()

    /** 名字合法性：非空且不含半角/全角空格 */
    private fun isValidTagName(name: String): Boolean =
        name.isNotEmpty() && !name.contains(' ') && !name.contains('　')

    // ────────────────────────────────────────────────────────────────
    // 父标签（组）
    // ────────────────────────────────────────────────────────────────

    /** 新建父标签 */
    @JvmStatic
    fun showCreateGroupDialog(context: Context) {
        val builder = WitDialog.EditTextDialogBuilder(context)
        builder.setTitle(R.string.tag_group_new_group)
            .setPlaceholder(context.getString(R.string.tag_group_group_name_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.add)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                if (!isValidTagName(name)) {
                    Common.showToast(context.getString(R.string.tag_group_name_invalid))
                    return@addAction
                }
                if (dao(context).getGroupByName(name) != null) {
                    Common.showToast(context.getString(R.string.tag_group_group_exists))
                    return@addAction
                }
                val id = dao(context).insertGroup(TagGroupEntity(name = name))
                if (id == -1L) {
                    // IGNORE 策略下唯一索引冲突（并发写入等罕见情况）
                    Common.showToast(context.getString(R.string.tag_group_group_exists))
                    return@addAction
                }
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .show()
    }

    /** 重命名父标签（重命名为已存在的名字直接驳回 —— 分组没有同义词词典的合并语义） */
    @JvmStatic
    fun showRenameGroupDialog(context: Context, group: TagGroupEntity) {
        val builder = WitDialog.EditTextDialogBuilder(context)
        builder.setTitle(context.getString(R.string.tag_group_rename_group_title, group.name))
            .setPlaceholder(context.getString(R.string.tag_group_group_name_hint))
            .setDefaultText(group.name)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                if (!isValidTagName(name)) {
                    Common.showToast(context.getString(R.string.tag_group_name_invalid))
                    return@addAction
                }
                if (name == group.name) {
                    dialog.dismiss()
                    return@addAction
                }
                val existing = dao(context).getGroupByName(name)
                if (existing != null && existing.id != group.id) {
                    Common.showToast(context.getString(R.string.tag_group_group_exists))
                    return@addAction
                }
                dao(context).renameGroup(group.id, name)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .show()
    }

    /** 删除父标签及其全部子标签（二次确认） */
    @JvmStatic
    fun showDeleteGroupDialog(context: Context, group: TagGroupEntity, childCount: Int) {
        WitDialog.MessageDialogBuilder(context)
            .setTitle(group.name)
            .setMessage(
                context.getString(R.string.tag_group_delete_group_confirm, group.name, childCount)
            )
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.tag_group_delete)) { dialog, _ ->
                dao(context).deleteGroupWithChildren(group.id)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .create()
            .show()
    }

    /** 点击父标签行 → 管理菜单 */
    @JvmStatic
    fun showGroupMenu(context: Context, group: TagGroupEntity, childCount: Int) {
        val labels = arrayOf(
            context.getString(R.string.tag_group_add_child),
            context.getString(R.string.tag_group_rename),
            context.getString(R.string.tag_group_delete),
            context.getString(R.string.tag_group_new_group),
        )
        WitDialog.MenuDialogBuilder(context)
            .addItems(labels) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showAddChildDialog(context, group)
                    1 -> showRenameGroupDialog(context, group)
                    2 -> showDeleteGroupDialog(context, group, childCount)
                    3 -> showCreateGroupDialog(context)
                }
            }
            .show()
    }

    // ────────────────────────────────────────────────────────────────
    // 子标签
    // ────────────────────────────────────────────────────────────────

    /** 给指定父标签添加子标签 */
    @JvmStatic
    fun showAddChildDialog(context: Context, group: TagGroupEntity) {
        val builder = WitDialog.EditTextDialogBuilder(context)
        builder.setTitle(context.getString(R.string.tag_group_add_child) + " · " + group.name)
            .setPlaceholder(context.getString(R.string.tag_group_child_name_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.add)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                if (!isValidTagName(name)) {
                    Common.showToast(context.getString(R.string.tag_group_name_invalid))
                    return@addAction
                }
                val dao = dao(context)
                // 子标签名全局唯一：已归属任何分组（含本组）/ 与父标签同名都算已存在
                if (name == group.name ||
                    dao.getChildrenByName(name).isNotEmpty()
                ) {
                    Common.showToast(context.getString(R.string.tag_group_child_exists))
                    return@addAction
                }
                val id = dao.insertChild(TagGroupChildEntity(groupId = group.id, name = name))
                if (id == -1L) {
                    Common.showToast(context.getString(R.string.tag_group_child_exists))
                    return@addAction
                }
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .show()
    }

    /** 重命名子标签（目标名已被其他子标签使用则驳回） */
    @JvmStatic
    fun showRenameChildDialog(context: Context, child: TagGroupChildEntity) {
        val builder = WitDialog.EditTextDialogBuilder(context)
        builder.setTitle(context.getString(R.string.tag_group_rename_child_title, child.name))
            .setPlaceholder(context.getString(R.string.tag_group_child_name_hint))
            .setDefaultText(child.name)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.sure)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                if (!isValidTagName(name)) {
                    Common.showToast(context.getString(R.string.tag_group_name_invalid))
                    return@addAction
                }
                if (name == child.name) {
                    dialog.dismiss()
                    return@addAction
                }
                val dao = dao(context)
                if (dao.getChildrenByName(name).isNotEmpty()) {
                    Common.showToast(context.getString(R.string.tag_group_child_exists))
                    return@addAction
                }
                dao.renameChild(child.id, name)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .show()
    }

    /** 删除子标签（= 移出分组，二次确认） */
    @JvmStatic
    fun showDeleteChildDialog(context: Context, child: TagGroupChildEntity) {
        WitDialog.MessageDialogBuilder(context)
            .setTitle(child.name)
            .setMessage(context.getString(R.string.tag_group_delete_child_confirm, child.name))
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.tag_group_delete)) { dialog, _ ->
                dao(context).deleteChildById(child.id)
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .create()
            .show()
    }

    /** 点击子标签行 → 管理菜单 */
    @JvmStatic
    fun showChildMenu(context: Context, child: TagGroupChildEntity) {
        val labels = arrayOf(
            context.getString(R.string.tag_group_rename),
            context.getString(R.string.tag_group_delete),
        )
        WitDialog.MenuDialogBuilder(context)
            .addItems(labels) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showRenameChildDialog(context, child)
                    1 -> showDeleteChildDialog(context, child)
                }
            }
            .show()
    }

    // ────────────────────────────────────────────────────────────────
    // 清空（管理页退路）
    // ────────────────────────────────────────────────────────────────

    @JvmStatic
    fun confirmClearAll(context: Context) {
        val groupCount = dao(context).countGroups()
        if (groupCount == 0) {
            Common.showToast(context.getString(R.string.tag_group_empty))
            return
        }
        WitDialog.MessageDialogBuilder(context)
            .setTitle(context.getString(R.string.tag_group_clear_all))
            .setMessage(context.getString(R.string.tag_group_clear_all_confirm, groupCount))
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.tag_group_delete)) { dialog, _ ->
                dao(context).clearAll()
                Common.showToast(context.getString(R.string.operate_success))
                dialog.dismiss()
            }
            .create()
            .show()
    }
}
