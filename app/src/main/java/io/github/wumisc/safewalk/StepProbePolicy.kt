package io.github.wumisc.safewalk

/**
 * 步数探针分级判定（**纯逻辑，不依赖 android.\***，可跑 JVM 单测）。
 *
 * ## 修的是什么
 *
 * 4.4.0 的探针只看 `stepEventCount == 0`（合格步数），超过 45 秒就报
 * 「❌ 启动 46s 仍未收到任何步数事件 … 先到功能实验区 → 步态自检确认传感器
 * 本身有没有数据」。但 `stepEventCount` 只在 `recordStep()`（喂给 GaitDetector
 * 后判定为合格步）里自增，**原始传感器回调数不算**。于是：
 *
 *   · 用户静止 / 拿在手里看日志，传感器每帧都在回调，只是没凑出合格步
 *     → 也被判成「传感器静默失效」，还误导用户去步态自检；
 *   · 每 45 秒复刷一次（真机日志 19:07:33「46s」、19:08:19「92s」）。
 *
 * 真机 19:06:47 的现场恰恰是 `系统计步器注册=false 加速度计兜底=true`：
 * SystemUI 没有 ACTIVITY_RECOGNITION 权限，TYPE_STEP_DETECTOR 注册被拒是
 * **预期行为**，加速度计兜底正常在跑。此时「零合格步」只说明没在走路，
 * 不是故障。
 *
 * ## 分级
 *
 * 引入「原始回调数」[rawCallbacks]，把「没走路」和「传感器真坏了」分开：
 *   · [Verdict.SENSOR_ALIVE_NO_STEP] 有回调、无合格步 → 中性提示，不报 ❌
 *   · [Verdict.SENSOR_SILENT]        连回调都没有   → 才是真静默失效，报 ❌
 *
 * [reported] 保证**每个启动会话只报一次**，不再按窗口反复刷。
 */
object StepProbePolicy {

    enum class Verdict {
        /** 未到探测窗口 / 总开关关闭 / 尚未 init / 本会话已报过 */
        NOT_READY,
        /** 已有合格步，链路正常，无需报 */
        OK,
        /** 传感器有原始回调但没走出合格步：静止、持握、持机看日志属正常 */
        SENSOR_ALIVE_NO_STEP,
        /** 连一次原始回调都没有：注册成功却静默失效，真故障 */
        SENSOR_SILENT
    }

    @JvmStatic
    @JvmOverloads
    fun evaluate(
        masterOn: Boolean,
        initTs: Long,
        now: Long,
        windowMs: Long,
        rawCallbacks: Long,
        qualifiedSteps: Int,
        reported: Boolean,
        graceMs: Long = 0L
    ): Verdict {
        if (!masterOn || initTs <= 0L || reported) return Verdict.NOT_READY
        if (now < initTs) return Verdict.NOT_READY                 // 时钟回拨
        if (now - initTs < windowMs + graceMs) return Verdict.NOT_READY
        if (qualifiedSteps > 0) return Verdict.OK
        return if (rawCallbacks > 0L)
            Verdict.SENSOR_ALIVE_NO_STEP
        else
            Verdict.SENSOR_SILENT
    }
}
