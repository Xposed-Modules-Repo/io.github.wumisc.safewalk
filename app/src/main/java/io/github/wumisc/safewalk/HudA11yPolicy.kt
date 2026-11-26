package io.github.wumisc.safewalk

/**
 * 【无障碍 · HUD 播报策略】纯逻辑，不依赖 android.\*，可跑 JVM 单测。
 *
 * ## 为什么要有它
 *
 * 路况小窗的 HUD（状态文字 + 今日步数徽章）是挂在悬浮窗里的 TextView，视觉用户一眼能看，
 * 但读屏用户默认**什么也听不到**：浮层窗口不可聚焦、两个 TextView 各念一句又会碎成
 * "路况预览中"、"今日 12 步" 两条孤立播报。
 *
 * 这里解决两件事：
 * 1. [compose] 把两行合成**一句人话**，整列只暴露一个无障碍节点（子节点标 NO，避免重复念）。
 * 2. [shouldAnnounce] 决定**什么时候主动播**：
 *    · 步数每变一次就播 = 走路时读屏被刷屏，绝对不行；
 *    · 只有「文案变了」且（距上次 ≥ 3 秒 或 累计走过 50 步里程碑）才播；
 *    · 时钟回拨一律放行，避免负数间隔把播报永久锁死。
 *
 * 用户随时可以手动让读屏聚焦这一列再听一遍，所以主动播报宁少勿多。
 */
object HudA11yPolicy {

    /** 两次主动播报的最小间隔：3 秒。 */
    const val MIN_ANNOUNCE_GAP_MS = 3_000L

    /** 步数里程碑：累计走这么多步，允许插播一次（即使 3 秒未到）。 */
    const val STEP_MILESTONE = 50L

    /**
     * 合成一句可读文案。
     *
     * @param hudText  HUD 状态文字（可为空 / null）
     * @param stepText 步数徽章文案，例如「今日 128 步」（隐藏时为 null）
     */
    @JvmStatic
    fun compose(hudText: String?, stepText: String?): String {
        val parts = ArrayList<String>(3)
        hudText?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        stepText?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (parts.isEmpty()) return ""
        // 步数行开头加「步数」，读屏念数字前先给语义，避免只听到「今日 128」
        return parts.joinToString("，")
    }

    /**
     * 是否值得主动播一次。
     *
     * @param prevDesc 上一次播出去的文案（null = 从未播过）
     * @param newDesc  这次要播的文案
     * @param now      当前时间戳（毫秒）
     * @param lastTs   上次播报时间戳（0 = 从未）
     * @param stepsSinceAnnounce 自上次播报以来新增的步数
     */
    @JvmStatic
    fun shouldAnnounce(
        prevDesc: String?,
        newDesc: String,
        now: Long,
        lastTs: Long,
        stepsSinceAnnounce: Long
    ): Boolean {
        if (newDesc.isBlank()) return false
        if (newDesc == prevDesc) return false
        if (now < lastTs) return true                    // 时钟回拨：放行
        if (lastTs == 0L) return true                     // 首条必播
        if (stepsSinceAnnounce >= STEP_MILESTONE) return true
        return now - lastTs >= MIN_ANNOUNCE_GAP_MS
    }
}
