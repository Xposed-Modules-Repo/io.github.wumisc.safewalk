package io.github.wumisc.safewalk

/**
 * 控制中心磁贴的状态判定（纯逻辑，便于单测）。
 *
 * 【磁贴管的是「自动跟随步行」，不是行走模式总开关】
 * 行走模式有两个独立配置：
 *  - `enableRearCamera`：路况预览总开关（有画面 / 没画面）
 *  - `rearCamAuto`：自动跟随步行（走起来才显示、停下收起；关 = 常显）
 * 磁贴对应的语义是**后者**：点开 = 走起来自动弹小窗，再点 = 关掉自动跟随。
 *
 * 因此 ACTIVE 要求两个配置同时为真：
 * 只开了 `enableRearCamera`（App 内的「常显」档）时，磁贴必须是灰的 ——
 * 磁贴亮着却站着不动也一直显示小窗，和「自动跟随」四个字完全矛盾。
 *
 * 三种外观：
 *  - [UNAVAILABLE]：总开关关着，点开也不会生效（预览服务 `applyFromConfig()`
 *    会直接 return），所以必须置灰，不能假装能点。
 *  - [ACTIVE]：自动跟随已开。刻意**不要求**预览服务此刻存活：服务是
 *    START_STICKY，被杀后会被重建；把 `isAlive()` 写进判定会让点完磁贴到
 *    服务起来的那 1~2 秒里磁贴一直是灰的，用户以为没点上。
 *  - [INACTIVE]：其余情况，点一下即开。
 */
object WalkTileStatePolicy {

    /** 与 Tile.STATE_UNAVAILABLE 数值一致（单测不依赖 android 类） */
    const val UNAVAILABLE = 0

    /** 与 Tile.STATE_INACTIVE 数值一致 */
    const val INACTIVE = 1

    /** 与 Tile.STATE_ACTIVE 数值一致 */
    const val ACTIVE = 2

    @JvmStatic
    fun resolve(masterOn: Boolean, walkOn: Boolean, autoOn: Boolean): Int = when {
        !masterOn -> UNAVAILABLE
        walkOn && autoOn -> ACTIVE
        else -> INACTIVE
    }

    /**
     * 磁贴副标题（Android 13+ 在磁贴下方显示一行小字）。
     * 关着的时候写「点击开启」——磁贴最常见的困惑就是「点了一下没反应」，
     * 把动作写在上面比只改颜色有效。
     */
    @JvmStatic
    fun subtitle(
        masterOn: Boolean,
        walkOn: Boolean,
        autoOn: Boolean,
        serviceAlive: Boolean
    ): String = when {
        !masterOn -> "总开关已关闭"
        !walkOn || !autoOn -> "点击开启自动跟随"
        serviceAlive -> "走起来才显示"
        // 严格开关语义：点一下永远是「切换」。这里只说清现状，不承诺「重试」——
        // 否则用户点下去发现是关掉，会觉得磁贴行为不一致。
        else -> "已开启 · 预览未运行，点一下关闭"
    }
}
