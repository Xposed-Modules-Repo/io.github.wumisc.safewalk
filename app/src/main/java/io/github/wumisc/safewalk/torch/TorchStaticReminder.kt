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
    private val context: Context,
    /**
     * 阈值来源（毫秒）。Hook 端把自己已经缓存的配置传进来，
     * 这样既不用再开一条独立的跨进程轮询，用户改完设置也能立刻生效。
     * 传 null 时退化为自己查 Provider（每 60 秒一次）。
     */
    private val thresholdProvider: (() -> Long)? = null
) : GaitStateCallback {

    /** 本轮「连续静置」的起点 */
    private var staticStartTime: Long? = null
    /** 手电开启时刻；null = 手电没开 */
    private var torchOnSince: Long? = null
    private var lastTorchOn = false
    private var reminderTriggered = false

    // 兜底路径的阈值缓存：GaitDetector 以约 50Hz 回调，每次都跨进程查 Provider 会直接拖垮续航
    private var cachedThresholdMs = 0L
    private var lastThresholdTs = 0L

    /** 从 JSON 配置读静置阈值（分钟），默认20分钟；60 秒内复用缓存 */
    private fun getThresholdMs(): Long {
        // 优先用 Hook 传进来的实时配置
        thresholdProvider?.let { return it().coerceAtLeast(60_000L) }

        val now = System.currentTimeMillis()
        if (cachedThresholdMs > 0 && now - lastThresholdTs < 60_000L) return cachedThresholdMs

        // 注意：读不到配置时必须照样写时间戳并落缓存，
        // 否则这个函数会在 50Hz 回调里每次都跨进程查一次 Provider（直接拖垮续航）
        val json = SafeWalkConfig.loadConfig(context)
        cachedThresholdMs = if (json == null) {
            20 * 60 * 1000L
        } else {
            try {
                val minutes = json.optDouble(SafeWalkPrefKey.torchStaticMinutes, 20.0)
                (minutes * 60 * 1000).toLong()
            } catch (e: Exception) {
                20 * 60 * 1000L
            }
        }
        lastThresholdTs = now
        return cachedThresholdMs
    }

    override fun onLongStaticDetected(isStatic: Boolean) {
        val now = System.currentTimeMillis()
        val torchOn = torchNotificationManager.torchIsOn

        // 记录手电开启的上升沿。提醒的语义是「手电开着 + 没在动，持续够久」，
        // 而不是「没在动够久」——之前的写法是把「静置时长」直接当判据，
        // 结果用户在家坐了两小时，一开手电出门，立刻收到「已长时间静置」。
        if (torchOn && !lastTorchOn) {
            torchOnSince = now
            reminderTriggered = false
        } else if (!torchOn) {
            torchOnSince = null
            reminderTriggered = false
        }
        lastTorchOn = torchOn

        if (!isStatic) {
            // 走动 = 新的一轮静置，重新计时（手电还开着，所以 torchOnSince 保留）
            staticStartTime = null
            reminderTriggered = false
            return
        }

        if (staticStartTime == null) staticStartTime = now
        val onSince = torchOnSince ?: return
        // 两个条件都要满足：手电已开够久、且这段时间人一直在原地
        val since = maxOf(staticStartTime!!, onSince)
        if (now - since >= getThresholdMs() && !reminderTriggered) {
            triggerReminder()
            reminderTriggered = true
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
        torchOnSince = null
        lastTorchOn = false
        reminderTriggered = false
    }
}
