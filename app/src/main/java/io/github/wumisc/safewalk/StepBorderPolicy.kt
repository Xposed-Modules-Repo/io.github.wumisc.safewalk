package io.github.wumisc.safewalk

/**
 * 「步数奖励描边」的纯逻辑（只算档位和颜色，不碰 View，便于 JVM 单测）。
 *
 * ## 规则（用户原话）
 *
 * | 今日步数 | 描边颜色 | 小红旗 |
 * |---|---|---|
 * | < 3000 | 原白色（不变） | 无 |
 * | ≥ 3000 | 橙色 | 无 |
 * | ≥ 8000 | 淡红色 | 无 |
 * | ≥ 20000 | 五星红旗红 | 有 |
 * | ≥ 30000 | 淡蓝色 | 有 |
 *
 * 档位是**从高到低**判的：30000 以上一定走淡蓝，不会再命中 20000 的国旗红 ——
 * 否则两档同时成立，颜色会取决于判断顺序，跨版本容易翻车。
 *
 * 小红旗按「2 万步以上」的字面含义处理：20000 起一直挂到 30000 及以后，
 * 不做「过了 3 万反而把旗子收走」的处理。用户若想要 3 万档单独换图标，
 * 改 [showFlag] 一处即可。
 *
 * ## 为什么颜色写死成 ARGB 常量
 *
 * HUD / 描边都不能受系统深色模式、主题色、色弱模式影响：这里的描边是**成绩反馈**，
 * 同一档位在所有人手机上必须是同一个颜色。透明度沿用旧描边的 0xCC（约 80%），
 * 只换色相不换明度，换来换去观感一致。
 */
object StepBorderPolicy {

    /** 无奖励档（保持旧描边） */
    const val TIER_NONE = 0

    /** ≥ 3000：橙色 */
    const val TIER_ORANGE = 1

    /** ≥ 8000：淡红色 */
    const val TIER_LIGHT_RED = 2

    /** ≥ 20000：五星红旗红 + 小红旗 */
    const val TIER_FLAG_RED = 3

    /** ≥ 30000：淡蓝色（小红旗保留） */
    const val TIER_LIGHT_BLUE = 4

    const val ORANGE_STEPS = 3_000L
    const val LIGHT_RED_STEPS = 8_000L
    const val FLAG_RED_STEPS = 20_000L
    const val LIGHT_BLUE_STEPS = 30_000L

    /** 旧描边的默认色：白 80% */
    const val COLOR_DEFAULT = 0xCCFFFFFF.toInt()

    /** 橙 */
    const val COLOR_ORANGE = 0xCCFF8C1A.toInt()

    /** 淡红 */
    const val COLOR_LIGHT_RED = 0xCCFF9AA2.toInt()

    /** 五星红旗红（GB 12982 标准红 #EE1C25） */
    const val COLOR_FLAG_RED = 0xCCEE1C25.toInt()

    /** 淡蓝 */
    const val COLOR_LIGHT_BLUE = 0xCC87CEFA.toInt()

    /**
     * 今日步数 → 档位。
     *
     * 负数（理论上不会出现，[TodayStepPolicy] 已 clamp）按 0 处理，不判奖励。
     */
    @JvmStatic
    fun tier(steps: Long): Int = when {
        steps >= LIGHT_BLUE_STEPS -> TIER_LIGHT_BLUE
        steps >= FLAG_RED_STEPS -> TIER_FLAG_RED
        steps >= LIGHT_RED_STEPS -> TIER_LIGHT_RED
        steps >= ORANGE_STEPS -> TIER_ORANGE
        else -> TIER_NONE
    }

    /** 今日步数 → 描边 ARGB（含 0xCC 透明度） */
    @JvmStatic
    fun borderColor(steps: Long): Int = borderColor(steps, true)

    /**
     * 今日步数 → 描边 ARGB；[enabled]=false 时一律回默认白。
     *
     * 「步数奖励」开关关掉后，颜色不由步数决定 —— 但仍走同一个入口，
     * 避免渲染层出现「开关关着却还在算档位」的分支。
     */
    @JvmStatic
    fun borderColor(steps: Long, enabled: Boolean): Int = when (if (enabled) tier(steps) else TIER_NONE) {
        TIER_ORANGE -> COLOR_ORANGE
        TIER_LIGHT_RED -> COLOR_LIGHT_RED
        TIER_FLAG_RED -> COLOR_FLAG_RED
        TIER_LIGHT_BLUE -> COLOR_LIGHT_BLUE
        else -> COLOR_DEFAULT
    }

    /** 是否挂小红旗（≥ 20000，含 30000 淡蓝档） */
    @JvmStatic
    fun showFlag(steps: Long): Boolean = showFlag(steps, true)

    /** 是否挂小红旗；开关关掉时无论多少步都不放 */
    @JvmStatic
    fun showFlag(steps: Long, enabled: Boolean): Boolean =
        enabled && steps >= FLAG_RED_STEPS

    /** 档位名，只用于诊断日志 */
    @JvmStatic
    fun tierName(tier: Int): String = when (tier) {
        TIER_ORANGE -> "橙(≥3000)"
        TIER_LIGHT_RED -> "淡红(≥8000)"
        TIER_FLAG_RED -> "国旗红+旗(≥20000)"
        TIER_LIGHT_BLUE -> "淡蓝(≥30000)"
        else -> "默认白(<3000)"
    }
}
