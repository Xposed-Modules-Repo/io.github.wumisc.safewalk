package io.github.wumisc.safewalk

import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

/**
 * 【无障碍】给开关、滑杆补上「它是哪个开关 / 它在调什么」的名字。
 *
 * ## 为什么需要
 *
 * 本项目的 28 个 `SwitchCompat`、14 个 `SeekBar` 全部**没有文字标签**：布局里从没出现过
 * `labelFor`，开关自己也不带 `android:text`。读屏用户聚焦过去只会听到「开关，已关闭」，
 * 听不出这条管的是「路况预览」还是「自动跟随步行」；滑杆更糟，只能听到一个数字而不知道
 * 在调什么。对视障用户来说这等于没有这个功能。
 *
 * ## 为什么不逐个改 XML
 *
 * 42 个控件散在 5 个布局里，每个都要手工找标题、加 id、写 `labelFor`，改一次漏一个，
 * 以后新加控件还会再漏。这里改成**一次运行时绑定**：
 *
 * 1. 遍历整棵视图树，遇到 `SwitchCompat` / `SeekBar` 就去找它的标签；
 * 2. 标签取法：从控件向上最多三层，每层往前扫兄弟节点，取第一个带文字的 `TextView`
 *    （容器则向下找第一个）——正好是「路况预览」这种标题，本项目的行式布局都是这个形状；
 * 3. 把标签节点标成 `labelFor = 控件id`（`labelFor` 要写在**标签**上，方向别搞反），
 *    控件没有 id 就先分配一个。
 *
 * 绑定是幂等的：重复调用不会改坏已有的 `labelFor`，也不会给已有 id 的控件换 id。
 */
object A11yLabelBinder {

    /**
     * 标题的最大字数。超过这个长度的基本是说明文字，不能拿来当开关的名字。
     * 取 22 是因为有些「标题」本身就是一句带数值的短句（如「手电开着静置 20 分钟后提醒省电」20 字），
     * 它们是可以直接念的；而说明文字普遍 40 字以上。
     */
    private const val MAX_TITLE_CHARS = 22

    /** 向上最多找几层容器。再深就不是「紧邻的标题」了，容易连到整页标题上。 */
    private const val MAX_DEPTH = 3

    /** 绑定整棵视图树。传 null 或空树都是安全的空操作。 */
    @JvmStatic
    fun bind(root: View?) {
        if (root == null) return
        walk(root)
    }

    private fun walk(v: View) {
        if (v is SwitchCompat || v is SeekBar) link(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
    }

    private fun link(control: View) {
        if (control.id == View.NO_ID) control.id = View.generateViewId()
        val label = findLabel(control) ?: return
        // labelFor 写在标签上，指向被标签的控件；TalkBack 靠这个反查名字
        // TextView.getLabelFor/setLabelFor（API 17 起），不是 View 的 labelForId
        if (label.labelFor == View.NO_ID) label.labelFor = control.id
    }

    /**
     * 找这个控件对应的标题：紧邻的、带文字的 TextView。
     *
     * 本项目的行式布局有三种形状，都要能接住：
     *  A. `[TextView 标题] [Switch]`（同级兄弟）
     *  B. `[LinearLayout(标题+说明)] [Switch]`（标题在兄弟容器里）
     *  C. `[TextView 标题] [TextView 说明] [SeekBar]`（滑杆在说明下面）
     */
    private fun findLabel(control: View): TextView? {
        var node: View = control
        repeat(MAX_DEPTH) {
            val parent = node.parent as? ViewGroup ?: return null
            val idx = parent.indexOfChild(node)
            // 往前最多看 3 个兄弟：标题和说明常分两行（[标题][说明][滑杆]），
            // 只取最近的那个会把说明当标题，所以交给 pickTitle 按「像不像标题」筛
            val candidates = ArrayList<TextView>(3)
            for (i in idx - 1 downTo 0) {
                firstText(parent.getChildAt(i))?.let { candidates.add(it) }
                if (candidates.size >= 3) break
            }
            if (candidates.isNotEmpty()) return pickTitle(control, candidates)
            node = parent
        }
        return null
    }

    /**
     * 从若干候选里挑标题。
     *
     * 规则是「由近到远，第一个不像说明文字的」，不是「字号最大的」：
     *  · `[标题][说明][滑杆]` 这种形状里，最近的候选是说明（很长），跳过它再取上一行的标题；
     *  · 反过来按字号挑会出错——上一段的标题（15sp「文字描边」）比本段的标题
     *    （13sp「强度」）大，会把滑杆错接到上一段的标题上，还把那个开关的名字抢走。
     * 所以判「像不像标题」只看长度：超过 [MAX_TITLE_CHARS] 的一律当说明跳过。
     */
    private fun pickTitle(control: View, candidates: List<TextView>): TextView? {
        for (t in candidates) {
            val text = t.text?.trim() ?: continue
            if (text.length > MAX_TITLE_CHARS) continue
            if (t.labelFor != View.NO_ID && t.labelFor != control.id) continue
            return t
        }
        return null
    }

    /** 深度优先拿第一个「有可见文字」的 TextView；容器往里钻，空 TextView 跳过。 */
    private fun firstText(v: View?): TextView? {
        if (v == null) return null
        if (v is TextView) {
            return if (v.text?.isNotBlank() == true) v else null
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val t = firstText(v.getChildAt(i))
                if (t != null) return t
            }
        }
        return null
    }
}
