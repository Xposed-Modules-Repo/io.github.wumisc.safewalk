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
 * - 手电筒开启 + 持续静置达到阈值（默认 12 分钟）→ 弹出提醒通知
 * - 短颠簸（公交/地铁晃动 <12s）不算真走动，不重置静置计时
 * - 通知 60s 自动消失，但手电仍开着仍静置时，每 5 分钟补一次，最多补 2 次
 * - 真正走动 / 手电关闭 → 重置，单次静置周期重新计
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

    /** 第一次提醒之后，下一次补提醒的时间戳 */
    private var nextRecheckAt = 0L
    /** 本轮已补提醒次数（第一次之外最多补 maxRecheck 次） */
    private var retryCount = 0
    /** 最近一次被判为「在动」的时刻；短颠簸宽限用，不立即重置静置计时 */
    private var movementSince: Long? = null

    /** 补提醒间隔：通知 60s 自动消失后，手电仍开着仍静置则过这么久再弹 */
    private val recheckGapMs = 5 * 60_000L
    /** 最多补提醒次数（第一次 + 最多补 2 次 = 最多 3 条） */
    private val maxRecheck = 2
    /** 短颠簸宽限：连续「在动」不足此时长不算真走动，保留静置起点 */
    private val movementGraceMs = 12_000L

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
            12 * 60 * 1000L
        } else {
            try {
                val minutes = json.optDouble(SafeWalkPrefKey.torchStaticMinutes, 12.0)
                (minutes * 60 * 1000).toLong()
            } catch (e: Exception) {
                12 * 60 * 1000L
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
            retryCount = 0
            nextRecheckAt = 0L
        } else if (!torchOn) {
            // 手电一关：全部计时复位（TorchCallback 也会顺手 dismissNotification）
            torchOnSince = null
            reminderTriggered = false
            retryCount = 0
            nextRecheckAt = 0L
            movementSince = null
            staticStartTime = null
        }
        lastTorchOn = torchOn

        if (!torchOn) return

        // —— 短颠簸宽限 ——
        // 公交/地铁的颠簸会断断续续被判成「在走」，以前一个晃点就把静置计时整个
        // 清掉，坐着车晃一路永远凑不满阈值。这里：连续「在动」不足 movementGraceMs
        // 不算真正走动，保留静置起点；超过才认定真走，才重置。
        if (!isStatic) {
            if (movementSince == null) movementSince = now
            if (now - movementSince!! >= movementGraceMs) {
                staticStartTime = null
                reminderTriggered = false
                retryCount = 0
                nextRecheckAt = 0L
                movementSince = null
            }
            return
        }
        movementSince = null

        if (staticStartTime == null) staticStartTime = now
        val onSince = torchOnSince ?: return
        // 两个条件都要满足：手电已开够久、且这段时间人一直在原地
        val since = maxOf(staticStartTime!!, onSince)

        // 第一次：满阈值弹一次
        if (!reminderTriggered && now - since >= getThresholdMs()) {
            triggerReminder()
            reminderTriggered = true
            // 通知 60s 后自动消失；真忘了关的人没看到也不怕，过 recheckGapMs 再补
            nextRecheckAt = now + recheckGapMs
            retryCount = 0
            return
        }
        // 补提醒：手电仍开着、仍静置，按间隔再弹，最多补 maxRecheck 次
        if (reminderTriggered && retryCount < maxRecheck && now >= nextRecheckAt) {
            triggerReminder()
            retryCount++
            nextRecheckAt = now + recheckGapMs
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
        retryCount = 0
        nextRecheckAt = 0L
        movementSince = null
    }
}
