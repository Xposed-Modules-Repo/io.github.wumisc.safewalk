package io.github.wumisc.safewalk

/**
 * 控制中心磁贴的状态判定（纯逻辑，便于单测）。
 *
 * 磁贴只有三种外观，判定依据必须是**用户能理解的事实**，而不是内部实现细节：
 *  - [UNAVAILABLE]：总开关关着。这时点开行走模式也不会生效（预览服务会在
 *    `applyFromConfig()` 里直接 return），所以磁贴必须置灰，不能假装能点。
 *  - [ACTIVE]：行走模式开关是开着的。这里刻意**不要求**预览服务此刻存活：
 *    服务是 START_STICKY，被杀后会被系统重建；若把 `isAlive()` 写进判定，
 *    点完磁贴到服务起来的那 1~2 秒里磁贴会一直是灰的，用户会以为没点上。
 *    「配置为开」才是用户的心智模型，服务存活与否只用于副标题提示。
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
    fun resolve(masterOn: Boolean, walkOn: Boolean): Int = when {
        !masterOn -> UNAVAILABLE
        walkOn -> ACTIVE
        else -> INACTIVE
    }

    /**
     * 磁贴副标题（Android 13+ 在磁贴下方显示一行小字）。
     * 关着的时候写「点击开启」——磁贴最常见的困惑就是「点了一下没反应」，
     * 把动作写在上面比只改颜色有效。
     */
    @JvmStatic
    fun subtitle(masterOn: Boolean, walkOn: Boolean, serviceAlive: Boolean): String = when {
        !masterOn -> "总开关已关闭"
        !walkOn -> "点击开启身后路况"
        serviceAlive -> "身后路况已开启"
        // 严格开关语义：点一下永远是「切换」。这里只说清现状，不承诺「重试」——
        // 否则用户点下去发现是关掉，会觉得磁贴行为不一致。
        else -> "已开启 · 预览未运行，点一下关闭"
    }
}
