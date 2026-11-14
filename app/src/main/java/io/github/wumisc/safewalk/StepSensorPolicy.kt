package io.github.wumisc.safewalk

/**
 * 「本机步态判定能不能信」的诊断（纯逻辑，不碰 Context，可跑 JVM 单测）。
 *
 * 【为什么需要它】
 * 5.0.0 收到用户反馈「这版步态不如之前好用，拿在手里经常跳预览小窗」。
 * 日志（13:57~16:25，3839 行）里有一行反复出现：
 *
 * ```
 * [gait] 本机走纯加速度判定 | 计步传感器=有 已出步=0
 *        （疑似未授予「身体活动」权限或被后台限制，建议在系统设置里允许）
 * ```
 *
 * 也就是说：这台机器**有**硬件计步器，但**一步都没出过数**。于是整套判定退化成
 * 「纯加速度猜」—— 拿在手里晃、放桌上颠、手机在口袋里被带着抖，波形都和走路很像，
 * 误触发是这种方案的天生缺陷，调参只能减少、不能消除。
 *
 * 真正的问题是：**这件事只写在日志里，用户看不到**。他只会觉得「新版本变差了」，
 * 而不知道手机上明明有一个能给出确定答案的传感器，只是权限没给。
 *
 * 所以这一条要在界面上直说，并给出可点的修复动作。
 */
object StepSensorPolicy {

    /** 一切正常：有计步器、有权限，判定以硬件计数为准 */
    const val OK = "ok"

    /** 本机根本没有 TYPE_STEP_COUNTER：只能纯加速度，属于硬件限制，无法修复 */
    const val NO_SENSOR = "no_sensor"

    /** 有计步器但没有 ACTIVITY_RECOGNITION：**可以修**，去授权就能显著减少误触发 */
    const val NO_PERMISSION = "no_permission"

    /**
     * @param hasSensor     本机是否存在 TYPE_STEP_COUNTER
     * @param hasPermission 是否已授予 ACTIVITY_RECOGNITION
     */
    @JvmStatic
    fun diagnose(hasSensor: Boolean, hasPermission: Boolean): String = when {
        !hasSensor -> NO_SENSOR
        !hasPermission -> NO_PERMISSION
        else -> OK
    }

    /** 是否需要把提示显示出来。OK 时不占地方 —— 没问题的地方不该有一行字 */
    @JvmStatic
    fun needsAttention(issue: String): Boolean = issue != OK

    /**
     * 给用户看的一句话。
     *
     * 措辞刻意避开「加速度计」「峰值检测」这类词：用户不需要知道我们怎么算，
     * 只需要知道「现在这样会误弹」和「怎么做能好」。
     */
    @JvmStatic
    fun hint(issue: String): String = when (issue) {
        NO_PERMISSION ->
            "本机有计步器，但没拿到「身体活动」权限，所以现在只能靠晃动猜测你在不在走路 —— " +
                "拿在手里也会误弹小窗。点这里授权后，只有真的迈步才会弹出。"
        NO_SENSOR ->
            "本机没有硬件计步器，只能按晃动幅度判断。走路时手机尽量放口袋或包里，" +
                "拿在手里时手臂自然摆动容易被误判。"
        else -> ""
    }
}
