package ceui.pixiv.ui.taggroup

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.V3Palette
import com.blankj.utilcode.util.BarUtils

/**
 * 标签分组管理页（仿 [ceui.pixiv.ui.synonym.SynonymDictFragment] 简化版），
 * 承载于 TemplateActivity「标签分组」。
 *
 * - 树形列表：父标签行 + 缩进子标签行，默认折叠，点父标签行右侧「▸ N 个子标签」展开/收起
 * - 单击父标签行 → 管理菜单（添加子标签 / 重命名 / 删除 / 新建父标签，走 [TagGroupOperate]）
 * - 单击子标签行 → 管理菜单（重命名 / 删除）
 * - Toolbar 菜单：新建父标签 / 清空全部分组
 *
 * 数据归 [TagGroupViewModel]，本类只渲染 + 转发点击。
 */
class TagGroupFragment : Fragment(R.layout.fragment_tag_group) {

    private val viewModel by viewModels<TagGroupViewModel>()
    private lateinit var adapter: GroupAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Toolbar>(R.id.toolbar).apply {
            // EdgeToEdge host：状态栏 inset 走 runtime padding，不用 fitsSystemWindows。
            updatePadding(top = BarUtils.getStatusBarHeight())
            setNavigationOnClickListener { activity?.finish() }
            menu.add(getString(R.string.tag_group_new_group)).setOnMenuItemClickListener {
                TagGroupOperate.showCreateGroupDialog(requireContext()); true
            }
            menu.add(getString(R.string.tag_group_clear_all)).setOnMenuItemClickListener {
                TagGroupOperate.confirmClearAll(requireContext()); true
            }
        }

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_view)
        val emptyView = view.findViewById<TextView>(R.id.empty_view)
        val countText = view.findViewById<TextView>(R.id.count_text)

        adapter = GroupAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        viewModel.displayItems.observe(viewLifecycleOwner) { items ->
            adapter.submit(items.orEmpty())
            emptyView.visibility = if (items.isNullOrEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.totalCount.observe(viewLifecycleOwner) { (groups, children) ->
            countText.text = getString(R.string.tag_group_group_count, groups) + " · " +
                    getString(R.string.tag_group_child_count, children)
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 树形列表 Adapter
    // ────────────────────────────────────────────────────────────────

    private inner class GroupAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<TagGroupViewModel.DictItem> = emptyList()
        private val palette by lazy { V3Palette.from(requireContext()) }

        /** 折叠态箭头的中性灰，从 v3_text_3 取（日夜自适配） */
        private val textMuted by lazy {
            ContextCompat.getColor(requireContext(), R.color.v3_text_3)
        }

        /** 圆角主题色强调条（父行左侧条 / 子行导轨共用） */
        private fun accentPill(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(palette.textAccent)
        }

        /** 计数徽章底：主题色淡胶囊 */
        private fun countBadgeBg(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(palette.alpha15)
        }

        /** 展开态卡片底色：淡主题色填充 + 主题色细描边，圆角对齐 v3_glass_surface(20dp) */
        private fun expandedCardBg(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(palette.alpha08)
            setStroke(1, palette.alpha15)
        }

        fun submit(newItems: List<TagGroupViewModel.DictItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is TagGroupViewModel.DictItem.Group -> TYPE_GROUP
            is TagGroupViewModel.DictItem.Child -> TYPE_CHILD
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_GROUP) {
                GroupVH(inflater.inflate(R.layout.item_tag_group, parent, false))
            } else {
                ChildVH(inflater.inflate(R.layout.item_tag_group_child, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is TagGroupViewModel.DictItem.Group -> (holder as GroupVH).bind(item)
                is TagGroupViewModel.DictItem.Child -> (holder as ChildVH).bind(item)
            }
        }

        private inner class GroupVH(view: View) : RecyclerView.ViewHolder(view) {
            private val accentBar = view.findViewById<View>(R.id.accent_bar)
            private val nameView = view.findViewById<TextView>(R.id.group_name)
            private val countView = view.findViewById<TextView>(R.id.child_count)
            private val chevron = view.findViewById<ImageView>(R.id.expand_chevron)
            private val toggleZone = view.findViewById<View>(R.id.toggle_zone)

            /** 上一次绑定的父标签 id：同一行重绑（= 用户点了展开/收起）才给箭头转场动画，
             *  回收复用到别的行则直接定位，避免滚动时无意义旋转 */
            private var boundGroupId: Long = -1L

            fun bind(item: TagGroupViewModel.DictItem.Group) {
                val sameRow = boundGroupId == item.entity.id
                boundGroupId = item.entity.id
                val expanded = item.expanded

                nameView.text = item.entity.name

                // 计数徽章：主题色淡底胶囊 + 主题色文字
                countView.text = getString(R.string.tag_group_child_count, item.childCount)
                countView.background = countBadgeBg()
                countView.setTextColor(palette.textAccent)

                // 左侧强调条：折叠淡、展开亮
                accentBar.background = accentPill()
                accentBar.alpha = if (expanded) 1f else 0.5f

                // 展开态：卡片淡主题色高亮；折叠态：中性玻璃面
                if (expanded) {
                    itemView.background = expandedCardBg()
                } else {
                    itemView.setBackgroundResource(R.drawable.v3_glass_surface)
                }

                // 箭头：折叠指向右「›」，展开旋转 90° 朝下「⌄」；展开态染主题色
                chevron.imageTintList =
                    ColorStateList.valueOf(if (expanded) palette.textAccent else textMuted)
                val targetRotation = if (expanded) 90f else 0f
                chevron.animate().cancel()
                if (sameRow && chevron.rotation != targetRotation) {
                    chevron.animate()
                        .rotation(targetRotation)
                        .setDuration(260)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                } else {
                    chevron.rotation = targetRotation
                }

                // 右侧计数+箭头是展开/收起热区；行主体单击进管理菜单
                toggleZone.setOnClickListener { viewModel.toggleExpanded(item.entity.id) }
                itemView.setOnClickListener {
                    TagGroupOperate.showGroupMenu(requireContext(), item.entity, item.childCount)
                }
            }
        }

        private inner class ChildVH(view: View) : RecyclerView.ViewHolder(view) {
            private val rail = view.findViewById<View>(R.id.child_rail)
            private val pill = view.findViewById<View>(R.id.child_pill)
            private val nameView = view.findViewById<TextView>(R.id.child_name)

            fun bind(item: TagGroupViewModel.DictItem.Child) {
                nameView.text = item.entity.name
                // 主题色细导轨，低透明把同一父标签下的子标签串成一条竖线
                rail.background = accentPill()
                rail.alpha = 0.35f
                pill.setOnClickListener {
                    TagGroupOperate.showChildMenu(requireContext(), item.entity)
                }
            }
        }
    }

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_CHILD = 1
    }
}
