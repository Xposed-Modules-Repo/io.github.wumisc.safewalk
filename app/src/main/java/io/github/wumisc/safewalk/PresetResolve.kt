package io.github.wumisc.safewalk

/**
 * 「一键方案」的参数表与智能判定（纯逻辑，不碰 Context / SharedPreferences，可单测）。
 *
 * 【为什么单独抽出来】
 * 4.9.0 把一键方案写死在 [PreviewStyleActivity] 的 switch 里，服务端要用同一套参数
 * 就只能再抄一遍 —— 抄完两边一改就对不上：用户在设置页点了「夜间」，结果小窗按
 * 白天参数渲染，日志里还写着「已套用夜间方案」。参数表只该有一份。
 *
 * 【5.1.0 智能判定】
 * 用户问：「不能按照深色模式跟随来启用夜间或者白天吗？没开深色模式的用户，
 * 可以增加一个时间选择」。于是有了两层判定：
 *
 *  1. **系统深色模式**（`Configuration.UI_MODE_NIGHT_YES`）—— 最直接的「现在是晚上」
 *     信号，用户开一次就通用，不需要在本应用里再设一遍。
 *  2. **夜间时段**（复用 [SafeWalkPrefKey.nightTorchStartHour] / `nightTorchEndHour`）
 *     —— 照顾「系统常年浅色」和「系统常年深色（自动切换关掉）」两类人。
 *
 * 两层是**或**关系：任一说「夜」就是夜间方案。
 *
 * 【一个必须处理的坑：白天开着深色模式】
 * 大量用户常年开深色模式（晚上不刺眼），白天也不关。如果照搬夜间方案的
 * 「低光增强 = 曝光补偿 +2EV」，白天的画面会直接过曝成一片白，比不开还糟。
 * 所以本类的处理是：**深色模式只决定「视觉风格」，只有真的落在夜间时段里才加曝光**。
 * 描边、取景收窄、对比度、暗部提亮这些纯显示处理白天用无害，甚至更清楚。
 */
object PresetResolve {

    const val DAY = "day"
    const val NIGHT = "night"
    const val PLAIN = "plain"
    const val AUTO = "auto"

    /** 一套完整的显示参数。字段与 [RearCameraOverlay.Style] 一一对应 */
    class Params(
        @JvmField val kind: String,
        @JvmField val framing: Int,
        @JvmField val borderDp: Int,
        @JvmField val lowLight: Boolean,
        @JvmField val hud: Boolean,
        @JvmField val screenEnhance: Boolean,
        @JvmField val screenContrast: Int,
        @JvmField val screenShadowLift: Int,
        @JvmField val screenSaturation: Int
    )

    /** 白天：完整取景、一条细白边把边界交代清楚，算法增强全关（白天光线够，省电也省算力） */
    private val DAY_PARAMS = Params(
        DAY, framing = 0, borderDp = 1, lowLight = false, hud = false,
        screenEnhance = false, screenContrast = 45, screenShadowLift = 40, screenSaturation = 50
    )

    /**
     * 夜间：只看下半路面 + 粗描边 + 画面增强。
     *
     * 饱和度压到 35：夜间高感噪点在灰度里比在彩色里更不显眼，降饱和等于免费降噪；
     * 暗部提亮给到 70：路灯之间的黑区最容易藏台阶，宁可画面发灰也要把轮廓提出来。
     */
    private val NIGHT_PARAMS = Params(
        NIGHT, framing = 55, borderDp = 2, lowLight = true, hud = false,
        screenEnhance = true, screenContrast = 55, screenShadowLift = 70, screenSaturation = 35
    )

    /** 极简：什么都不要，画面就是相机原始输出 */
    private val PLAIN_PARAMS = Params(
        PLAIN, framing = 0, borderDp = 0, lowLight = false, hud = false,
        screenEnhance = false, screenContrast = 45, screenShadowLift = 40, screenSaturation = 50
    )

    /** 按名字取方案；未知名字退回白天，绝不抛异常（配置里可能留着老版本的值） */
    @JvmStatic
    fun params(kind: String?): Params = when (kind) {
        NIGHT -> NIGHT_PARAMS
        PLAIN -> PLAIN_PARAMS
        else -> DAY_PARAMS
    }

    /**
     * 当前小时是否落在夜间时段内。
     *
     * 跨零点必须单独处理：22:00~6:00 是 `hour >= 22 || hour < 6`，
     * 而 6:00~22:00 这种「白天时段」写法（虽然 UI 不给）也得能算对。
     *
     * [start] == [end] 视为「不启用按时间判定」，返回 false —— 给用户一个
     * 「我不想让它按时间猜」的关闭方式，比要求他把两个值拖成相反数自然。
     */
    @JvmStatic
    fun isNightHour(hour: Int, start: Int, end: Int): Boolean {
        val h = ((hour % 24) + 24) % 24
        val s = ((start % 24) + 24) % 24
        val e = ((end % 24) + 24) % 24
        if (s == e) return false
        return if (s < e) h in s until e else (h >= s || h < e)
    }

    /**
     * 智能判定：给出此刻该用哪一套参数。
     *
     * @param nightMode 系统是否处于深色模式
     * @param hour      当前小时 0~23
     * @param start     夜间时段起点（含）
     * @param end       夜间时段终点（不含）
     */
    @JvmStatic
    fun resolveParams(nightMode: Boolean, hour: Int, start: Int, end: Int): Params {
        val nightHour = isNightHour(hour, start, end)
        if (!nightMode && !nightHour) return DAY_PARAMS
        // 深色模式带来的「夜间风格」，但天还亮着 → 去掉曝光补偿，只留视觉处理
        if (!nightHour) {
            return Params(
                NIGHT, NIGHT_PARAMS.framing, NIGHT_PARAMS.borderDp,
                lowLight = false,
                NIGHT_PARAMS.hud, NIGHT_PARAMS.screenEnhance,
                NIGHT_PARAMS.screenContrast, NIGHT_PARAMS.screenShadowLift,
                NIGHT_PARAMS.screenSaturation
            )
        }
        return NIGHT_PARAMS
    }

    /** 判定依据，写进日志让用户一眼看出「为什么现在是这样」 */
    @JvmStatic
    fun reason(nightMode: Boolean, hour: Int, start: Int, end: Int): String {
        val nightHour = isNightHour(hour, start, end)
        // 小时数补零：界面上「22:00~6:00」和「22:00~06:00」混着出现会显得随意
        val win = String.format(java.util.Locale.US, "%02d:00~%02d:00", start, end)
        return when {
            nightMode && nightHour -> "深色模式 + 时段内($win) → 夜间"
            nightMode -> "深色模式（当前 $hour 时不在 $win）→ 夜间风格，不加曝光"
            nightHour -> "浅色模式但落在 $win → 夜间"
            else -> "浅色模式且不在 $win → 白天"
        }
    }
}
