package io.github.wumisc.safewalk.torch

import android.content.Context

/**
 * 步态状态回调接口
 */
interface GaitStateCallback {
    fun onLongStaticDetected(isStatic: Boolean)
}

/**
 * 手电筒静置省电提醒：
 * - 手电筒开启 + 持续静置达到阈值 → 弹出提醒通知
 * - 用户走动后重置计时，单次静置周期只提醒一次
 */
class TorchStaticReminder(
    private val torchNotificationManager: TorchNotificationManager,
    private val context: Context
) : GaitStateCallback {

    private var staticStartTime: Long? = null
    private var reminderTriggered = false

    /** 静置阈值（分钟），默认20分钟 */
    private fun getThresholdMs(): Long {
        return 20 * 60 * 1000L
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
