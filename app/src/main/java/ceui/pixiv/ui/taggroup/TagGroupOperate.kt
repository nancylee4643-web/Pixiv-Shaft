package ceui.pixiv.ui.taggroup

import android.content.Context
import android.text.InputType
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.Common
import ceui.pixiv.db.taggroup.GroupWithChildren
import ceui.pixiv.db.taggroup.TagGroupChildEntity
import ceui.pixiv.db.taggroup.TagGroupDao
import ceui.pixiv.db.taggroup.TagGroupEntity
import ceui.pixiv.witstudio.dialog.WitDialog
import java.util.Locale

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
    // 「按标签筛选」列表长按归类
    // ────────────────────────────────────────────────────────────────

    /**
     * 长按未分组标签 → 选父标签归类:候选为已有父标签 + 「新建父标签并归类」。
     * [excludeGroupId] 非 null 时不进候选(子标签「移动到其他父标签」时排除现属父标签)。
     */
    @JvmStatic
    fun showAssignToParentPicker(
        context: Context,
        tagName: String,
        groups: List<GroupWithChildren>,
        excludeGroupId: Long? = null,
    ) {
        // 该名已是父标签(归一化比较,对齐展示管线的父行判定):归为其子标签会被父行判定遮蔽,驳回
        val tagNameNorm = tagName.trim().lowercase(Locale.getDefault())
        groups.firstOrNull { it.group.name.trim().lowercase(Locale.getDefault()) == tagNameNorm }
            ?.let {
                Common.showToast(context.getString(R.string.tag_group_name_is_group, it.group.name))
                return
            }
        val builder = WitDialog.MenuDialogBuilder(context)
            .setTitle(context.getString(R.string.tag_group_assign_title, tagName))
        builder.addItem(context.getString(R.string.tag_group_create_and_assign)) { dialog, _ ->
            dialog.dismiss()
            showCreateGroupAndAssignDialog(context, tagName)
        }
        groups.forEach { group ->
            if (group.group.id == excludeGroupId) return@forEach
            builder.addItem(group.group.name) { dialog, _ ->
                dialog.dismiss()
                assignToGroup(context, tagName, group.group)
            }
        }
        builder.show()
    }

    /** 新建父标签并把 [tagName] 直接归到它下(组建失败则不归类)。 */
    @JvmStatic
    fun showCreateGroupAndAssignDialog(context: Context, tagName: String) {
        val builder = WitDialog.EditTextDialogBuilder(context)
        builder.setTitle(context.getString(R.string.tag_group_create_and_assign_title, tagName))
            .setPlaceholder(context.getString(R.string.tag_group_group_name_hint))
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(context.getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .addAction(context.getString(R.string.add)) { dialog, _ ->
                val name = builder.editText.text?.toString()?.trim().orEmpty()
                if (!isValidTagName(name)) {
                    Common.showToast(context.getString(R.string.tag_group_name_invalid))
                    return@addAction
                }
                // 展示管线按归一化名先判父行,与标签名仅大小写不同的组名会遮蔽归类,一并驳回
                if (name.equals(tagName, ignoreCase = true)) {
                    Common.showToast(context.getString(R.string.tag_group_name_is_group, name))
                    return@addAction
                }
                if (dao(context).getGroupByName(name) != null) {
                    Common.showToast(context.getString(R.string.tag_group_group_exists))
                    return@addAction
                }
                val groupId = dao(context).insertGroup(TagGroupEntity(name = name))
                if (groupId == -1L) {
                    // IGNORE 策略下唯一索引冲突（并发写入等罕见情况）
                    Common.showToast(context.getString(R.string.tag_group_group_exists))
                    return@addAction
                }
                assignToGroup(context, tagName, TagGroupEntity(id = groupId, name = name))
                dialog.dismiss()
            }
            .show()
    }

    /** 长按子标签 → 移动到其他父标签 / 移出分组。 */
    @JvmStatic
    fun showChildAssignMenu(
        context: Context,
        child: TagGroupChildEntity,
        groups: List<GroupWithChildren>,
    ) {
        val labels = arrayOf(
            context.getString(R.string.tag_group_move_to_parent),
            context.getString(R.string.tag_group_remove_from_group),
        )
        WitDialog.MenuDialogBuilder(context)
            .setTitle(child.name)
            .addItems(labels) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showAssignToParentPicker(context, child.name, groups, excludeGroupId = child.groupId)
                    1 -> showDeleteChildDialog(context, child)
                }
            }
            .show()
    }

    /** 把标签名归到 [group] 下:已在则提示、在他组则移动、否则新增。 */
    private fun assignToGroup(context: Context, tagName: String, group: TagGroupEntity) {
        val dao = dao(context)
        // name 唯一索引,理论至多一条
        val existing = dao.getChildrenByName(tagName).firstOrNull()
        when {
            existing == null -> {
                val id = dao.insertChild(TagGroupChildEntity(groupId = group.id, name = tagName))
                if (id == -1L) {
                    Common.showToast(context.getString(R.string.tag_group_child_exists))
                    return
                }
                Common.showToast(context.getString(R.string.operate_success))
            }
            existing.groupId == group.id ->
                Common.showToast(context.getString(R.string.tag_group_already_in_group))
            else -> {
                dao.moveChildToGroup(existing.id, group.id)
                Common.showToast(context.getString(R.string.operate_success))
            }
        }
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
