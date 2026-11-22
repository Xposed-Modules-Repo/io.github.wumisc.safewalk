package io.github.wumisc.safewalk

/**
 * 「行走模式运行中」常驻通知副标题的纯逻辑（5.6.8）。
 *
 * ## 修的是什么
 *
 * 5.6.7 及以前，副标题是写死的一句「身后路况持续显示中」：
 * 用户明明开的是**自动跟随**（走起来才显示、停下就收），通知却宣称路况
 * 「持续显示中」——用户原话：「也没开啊，怎么就持续显示中，应该是步态检测运行中吧」。
 * 而且 goForeground 有「已提起就跳过」的短路，这句话从服务启动挂到服务销毁，
 * 小窗收起后也不刷新（真机截图：通知时间 22:28，23:27 仍是旧文案）。
 *
 * ## 三态
 *
 * | 状态 | 副标题 |
 * |---|---|
 * | 小窗正在显示 | 身后路况显示中 |
 * | 自动跟随开、小窗未显示 | 步态检测运行中 · 走起来自动显示 |
 * | 手动常显、小窗未显示（异常/权限收回） | 身后路况持续显示中 |
 *
 * 「持续显示中」只留给手动常显档 —— 那才是这句话的本义。
 */
object WalkNotifText {

    const val SHOWING = "身后路况显示中 · 点击查看"
    const val AUTO_WAITING = "步态检测运行中 · 走起来自动显示"
    const val MANUAL_ALWAYS = "身后路况持续显示中 · 点击查看"

    /**
     * @param showing 小窗当前是否真的挂在屏幕上
     * @param auto    「自动跟随步行」开关（rearCamAuto）
     */
    @JvmStatic
    fun subtitle(showing: Boolean, auto: Boolean): String = when {
        showing -> SHOWING
        auto -> AUTO_WAITING
        else -> MANUAL_ALWAYS
    }
}
