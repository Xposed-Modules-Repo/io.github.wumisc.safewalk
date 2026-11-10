package io.github.wumisc.safewalk

/**
 * 厂商私有设置页的类别。
 *
 * 只有这四类需要「指路」——它们都没有公开 API 能查询或修改状态，只能把用户
 * 送到系统设置里的某个界面。其余能力（前台服务、悬浮窗、手电、Hook）一律走
 * 运行时降级链，不查表，见 `docs/rom-adaptation-plan.md` 第 3 节。
 */
enum class TargetKind(
    /** 给用户看的一行说明。 */
    val label: String
) {
    /** 自启动 / 允许后台活动。 */
    AUTOSTART("自启动 / 后台活动"),

    /** 电池优化白名单 / 省电策略。 */
    BATTERY("电池优化白名单"),

    /** 后台弹出界面（只有部分 ROM 把它做成独立开关）。 */
    BACKGROUND_POPUP("后台弹出界面"),

    /** 紧急呼叫设置（车祸预警的兜底入口）。 */
    EMERGENCY_CALL("紧急呼叫设置")
}

/**
 * 一个可跳转的设置页目标。
 *
 * ## 为什么用字符串而不是 `ComponentName` / `Intent`
 *
 * [RomProfile] 是**纯数据**，要能在 JVM 单测里构造和断言。`ComponentName` 与 `Intent`
 * 都是 Android 类，本地单测里一碰就抛 "not mocked"。所以这里只存 `pkg/cls` 扁平串
 * 或 action 串，运行时再由 [toComponentName] / [action] 交给 Android 解析。
 *
 * 这与项目里 `WalkTileStatePolicy` 用裸整数代替 `Tile.STATE_*` 是同一个取舍：
 * **能被单测覆盖的纯逻辑，优先于能直接用 Android 类型的便利**。
 *
 * ## 为什么必须区分 component 与 action
 *
 * 有些页面在 AOSP 里就有标准 action（电池优化），有些只有厂商私有 component。
 * 混在一起会让「找不到就回退」的逻辑没法区分该用哪种方式跳转。
 *
 * @param kind   页面类别
 * @param spec   `component:pkg/cls` 或 `action:android.settings.xxx`
 * @param label  给用户看的去处说明，例如「安全中心 → 自启动」
 */
data class PageTarget(
    val kind: TargetKind,
    val spec: String,
    val label: String
) {
    init {
        require(spec.startsWith(COMPONENT_PREFIX) || spec.startsWith(ACTION_PREFIX)) {
            "PageTarget.spec 必须以 $COMPONENT_PREFIX 或 $ACTION_PREFIX 开头，实际是：$spec"
        }
        require(spec.length > COMPONENT_PREFIX.length) { "PageTarget.spec 缺少目标：$spec" }
    }

    /** true 表示这是 `action:` 形式，需要 `Intent(action)`；false 表示 `component:` 形式。 */
    val isAction: Boolean get() = spec.startsWith(ACTION_PREFIX)

    /** `component:` 形式的 `pkg/cls`；action 形式返回 null。 */
    fun componentSpec(): String? =
        if (isAction) null else spec.removePrefix(COMPONENT_PREFIX)

    /** `action:` 形式的 action 串；component 形式返回 null。 */
    fun action(): String? =
        if (isAction) spec.removePrefix(ACTION_PREFIX) else null

    /**
     * 能否交给 `ComponentName.unflattenFromString` 解析。
     *
     * 只做格式校验（必须恰好一个 `/`、两侧非空），不保证系统上真的有这个组件 ——
     * 真正的可用性判断必须靠运行时的 `resolveActivity`，见计划第 0 节约束 2。
     */
    fun looksLikeComponent(): Boolean {
        val c = componentSpec() ?: return false
        val slash = c.indexOf('/')
        if (slash <= 0 || slash != c.lastIndexOf('/')) return false
        return slash < c.length - 1
    }

    companion object {
        const val COMPONENT_PREFIX = "component:"
        const val ACTION_PREFIX = "action:"

        /** 构造 component 形式的便捷方法（表里绝大多数条目用它，少写前缀少出错）。 */
        fun component(kind: TargetKind, pkgAndCls: String, label: String) =
            PageTarget(kind, COMPONENT_PREFIX + pkgAndCls, label)

        /** 构造 action 形式的便捷方法。 */
        fun action(kind: TargetKind, action: String, label: String) =
            PageTarget(kind, ACTION_PREFIX + action, label)
    }
}

/**
 * 一个 UI 生态的适配档案（纯数据）。
 *
 * 设计约束来自计划第 3 节：
 * 1. **只回答「用户该去哪个页面」**，不回答「功能能不能用」——后者永远是运行时决定；
 * 2. 所有条目都只是**候选**，运行时逐条 `resolveActivity` 校验，全失败回退应用详情页；
 * 3. 没写进来的东西不会让功能消失，最坏是多点两下。
 *
 * @param id         所属生态
 * @param pages      私有设置页候选（按 [TargetKind] 分类）
 * @param cameraPkgs 该生态的相机类包名（**不含**通用表，合并由 [RomRegistry] 负责）
 * @param note       给维护者的一句话备注，解释这个生态最容易踩的坑
 */
data class RomProfile(
    val id: RomId,
    val pages: List<PageTarget>,
    val cameraPkgs: Set<String>,
    val note: String
) {
    /** 按类别取候选页，保持表内声明顺序（顺序即优先级）。 */
    fun pagesOf(kind: TargetKind): List<PageTarget> = pages.filter { it.kind == kind }

    /** 是否有任何一条自启动候选。AOSP 允许为空。 */
    fun hasAutostart(): Boolean = pagesOf(TargetKind.AUTOSTART).isNotEmpty()
}
