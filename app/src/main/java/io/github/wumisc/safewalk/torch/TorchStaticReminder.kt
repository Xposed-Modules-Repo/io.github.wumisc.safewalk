package io.github.wumisc.safewalk.torch

import android.content.Context

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
 */
class TorchStaticReminder(
    private val torchNotificationManager: TorchNotificationManager,
    private val context: Context,
    private val staticThresholdMs: Long = 20 * 60 * 1000L // 默认20分钟
) : GaitStateCallback {

    private var staticStartTime: Long? = null
    private var reminderTriggered = false

    override fun onLongStaticDetected(isStatic: Boolean) {
        val now = System.currentTimeMillis()
        if (isStatic) {
            if (staticStartTime == null) {
                staticStartTime = now
            }
            val staticDuration = now - staticStartTime!!
            if (staticDuration >= staticThresholdMs && torchNotificationManager.torchIsOn && !reminderTriggered) {
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
