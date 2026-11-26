package io.github.wumisc.safewalk

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager

/**
 * 【步数源探针】本机到底能不能拿到「走了几步」。
 *
 * 「自动跟随步行」这套逻辑的地基是步数：没有可靠的步数，只剩加速度猜，
 * 拿在手里一晃就误弹小窗 —— 这正是 [StepSensorPolicy] 一直在界面上提示的事。
 *
 * 无框架（没有 LSPosed）时，SystemUI 侧那一路旁证也没有了，所有判定都压在这条腿上，
 * 所以磁贴要先问一句：这台机器真的能给出步数吗？答案决定磁贴是「自动跟随」
 * 还是退化成「路况预览开关」（见 [TileSemanticPolicy]）。
 */
object StepSourceProbe {

    /** 返回 [StepSensorPolicy] 的三种结论之一；任何异常都按「可用」处理，不误伤能用的机器。 */
    @JvmStatic
    fun issue(ctx: Context): String = try {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val hasSensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        val hasPerm = ctx.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        StepSensorPolicy.diagnose(hasSensor, hasPerm)
    } catch (t: Throwable) {
        StepSensorPolicy.OK
    }

    /** 「自动跟随步行」是否值得开启：只有步数源真的可用才开。 */
    @JvmStatic
    fun autoFollowAvailable(ctx: Context): Boolean = issue(ctx) == StepSensorPolicy.OK
}
