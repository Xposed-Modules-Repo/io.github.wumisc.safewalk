package io.github.wumisc.safewalk.torch

import android.content.Context
import io.github.wumisc.safewalk.SafeWalkConfig
import io.github.wumisc.safewalk.SafeWalkPrefKey

/**
 * 步态状态回调接口，由 GaitDetector 实现并注入
 */
interface GaitStateCallback {
    fun onLongStaticDetected(isStatic: Boolean)
}

/**
 * 手电筒静置省电提醒：
 * - 手电筒开启 + 持续静置达到阈值 → 弹出提醒通知
 * - 用户走动后重置计时，单次静置周期只提醒一次
 * - 阈值从 JSON 配置实时读取，用户改设置不用重启 Hook
 */
class TorchStaticReminder(
    private val torchNotificationManager: TorchNotificationManager,
    private val context: Context
) : GaitStateCallback {

    private var staticStartTime: Long? = null
    private var reminderTriggered = false

    /** 从 JSON 配置读静置阈值（分钟），默认20分钟 */
    private fun getThresholdMs(): Long {
        val json = SafeWalkConfig.loadConfigFromFile() ?: return 20 * 60 * 1000L
        return try {
            val minutes = json.optDouble(SafeWalkPrefKey.torchStaticMinutes, 20.0)
            (minutes * 60 * 1000).toLong()
        } catch (e: Exception) {
            20 * 60 * 1000L
        }
    }

    override fun onLongStaticDetected(isStatic: Boolean) {
        val now = System.currentTimeMillis()
        if (isStatic) {
            if (staticStartTime == null) {
                staticStartTime = now
            }
            val staticDuration = now - staticStartTime!!
            val thresholdMs = getThresholdMs()
            if (staticDuration >= thresholdMs && torchNotificationManager.torchIsOn && !reminderTriggered) {
                triggerReminder()
                reminderTriggered = true
            }
        } else {
            staticStartTime = null
            reminderTriggered = false
        }
    }

    private fun triggerReminder() {
        torchNotificationManager.refreshNotification()
    }

    /**
     * 重置状态
     */
    fun reset() {
        staticStartTime = null
        reminderTriggered = false
    }
}
