package io.github.wumisc.safewalk

/**
 * 配置 Key 常量。
 * UI端写入 XSharedPreferences(name="safewalk_xsp", MODE_WORLD_READABLE)，
 * Hook端通过 XSharedPreferences 读取。
 */
object SafeWalkPrefKey {
    // 总开关
    const val masterEnable = "masterEnable"

    // 5个功能开关
    const val enableBatteryWarning = "enableBatteryWarning"    // 电量预警
    const val enableIdReminder = "enableIdReminder"          // 证件提醒
    const val enableSitHealthRemind = "enableSitHealthRemind" // 久坐提醒
    const val enableSocialTipCard = "enableSocialTipCard"    // 社交礼仪提示
    const val enableNightTorch = "enableNightTorch"          // 夜间手电提醒

    // ===== 后置看路 =====
    /** 后置看路总开关：走路时用后置摄像头看路况。 */
    const val enableRearCamera = "enableRearCamera"
    /** 后置看路形态："fullscreen" 全屏半透明 / "smallwindow" 角落小窗。 */
    const val rearCamMode = "rearCamMode"
    /** 后置看路自动模式：true=走起来自动开、停下自动收；false=手动开关。 */
    const val rearCamAuto = "rearCamAuto"

    // 出行模式
    const val travelMode = "travelMode"  // 求职/约会/聚会/旅游

    // 步态参数
    const val stepThreshold = "stepThreshold"    // 触发步数阈值
    const val staticResetSec = "staticResetSec"  // 静置多少秒重置

    // 急救卡
    const val emerName = "emerName"
    const val emerBlood = "emerBlood"
    const val emerAddress = "emerAddress"
    const val emerContactName = "emerContactName"
    const val emerContactPhone = "emerContactPhone"
}
