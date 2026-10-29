package io.github.wumisc.safewalk

/**
 * 配置 Key 常量。
 * 宿主写入 XSharedPreferences(name="safewalk_xsp")，本模块 LSP 侧读取。
 */
object SafeWalkPrefKey {
    const val enableBatteryWarning = "enableBatteryWarning"
    const val enableIdReminder = "enableIdReminder"
    const val enableSitHealthRemind = "enableSitHealthRemind"
    const val enableSocialTipCard = "enableSocialTipCard"
    const val travelMode = "travelMode"
    const val stepThreshold = "stepThreshold"
    const val staticResetSec = "staticResetSec"
    const val emerName = "emerName"
    const val emerBlood = "emerBlood"
    const val emerAddress = "emerAddress"
    const val emerContactName = "emerContactName"
    const val emerContactPhone = "emerContactPhone"
}
