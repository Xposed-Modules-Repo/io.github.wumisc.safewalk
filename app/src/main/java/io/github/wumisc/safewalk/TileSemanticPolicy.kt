package io.github.wumisc.safewalk

/**
 * 【磁贴语义策略】磁贴到底是「自动跟随开关」还是「路况预览开关」。
 *
 * 背景：磁贴原本只有一个语义 —— 切「自动跟随步行」（`rearCamAuto`）。
 * 但自动跟随的地基是步数：无框架时机身侧旁证全没了，只剩 App 自己这条腿；
 * 而有些机器根本没有计步器、或者「身体活动」权限没给，这时候：
 *   · 磁贴亮着也永远不会自己弹窗（用户以为坏了）；
 *   · 用户想看一眼路况，唯一的入口却是一个永远不动的自动开关。
 *
 * 所以按步数源是否可用分两档：
 *  · [AUTO_FOLLOW]：步数可用 → 磁贴照旧=自动跟随，行为与 LSPosed 版完全一致；
 *  · [MANUAL_PREVIEW]：步数不可用 → 磁贴改成「路况预览」总开关（手动显/收），
 *    并把自动跟随强制关掉，不留下一个点了没反应的假开关。
 *
 * 这里面刻意不出现「有没有框架」的判断：自动跟随从来都是 App 侧步态驱动的，
 * SystemUI 的钩子只做呈现与旁证。步数不可用时，有框架也一样转不动 —— 结论一致。
 *
 * 【判据必须每次现算，不能缓存】步数可用性是会变的：用户可能后来才去授「身体活动」权限，
 * 也可能刚从没计步器的旧机换机。所以磁贴每次刷新、App 每次回前台都重新问一遍
 * [StepSourceProbe.issue]，一旦步数回来就立刻退回 [AUTO_FOLLOW]，
 * 不会永久钉死在「手动档」——否则用户打开自动跟随会发现点了没用。
 */
object TileSemanticPolicy {

    /** 磁贴=自动跟随开关（步数可用） */
    const val AUTO_FOLLOW = "auto_follow"

    /** 磁贴=路况预览总开关（步数不可用） */
    const val MANUAL_PREVIEW = "manual_preview"

    /** 与 Tile.STATE_* 数值对齐，单测不依赖 android 类 */
    private const val UNAVAILABLE = 0
    private const val INACTIVE = 1
    private const val ACTIVE = 2

    /**
     * @param stepIssue [StepSensorPolicy] 的结论（ok / no_sensor / no_permission）
     */
    @JvmStatic
    fun mode(stepIssue: String): String =
        if (stepIssue == StepSensorPolicy.OK) AUTO_FOLLOW else MANUAL_PREVIEW

    @JvmStatic
    fun isManual(stepIssue: String): Boolean = mode(stepIssue) == MANUAL_PREVIEW

    /**
     * 要不要强制关掉自动跟随。
     *
     * 只在步数不可用时成立，且**必须**关：否则 `rearCamAuto=true` 会留在配置里，
     * 服务起来后一直等步态，永远不显示，而界面上那个开关还是亮的。
     */
    @JvmStatic
    fun shouldForceAutoOff(stepIssue: String): Boolean = isManual(stepIssue)

    /** 手动档的磁贴外观：管的是预览总开关 `enableRearCamera`。 */
    @JvmStatic
    fun manualState(masterOn: Boolean, walkOn: Boolean): Int = when {
        !masterOn -> UNAVAILABLE
        walkOn -> ACTIVE
        else -> INACTIVE
    }

    /** 手动档副标题：把「点一下会做什么」直接写在磁贴上。 */
    @JvmStatic
    fun manualSubtitle(masterOn: Boolean, walkOn: Boolean, serviceAlive: Boolean): String = when {
        !masterOn -> "总开关已关闭"
        !walkOn -> "点击显示路况"
        serviceAlive -> "常显中 · 点击关闭"
        else -> "已打开 · 预览未运行，点击关闭"
    }
}
