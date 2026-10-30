package io.github.wumisc.safewalk

import android.content.Context
import org.json.JSONObject

/**
 * 配置 Key 常量。
 * 新版方案：UI端写入 SharedPreferences（私有），同时导出成 JSON 文件到 files/ 目录。
 * Hook端直接读 JSON 文件，不需要 XSharedPreferences，不需要 MODE_WORLD_READABLE。
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
    /** 后置看路透明度：0~100，值越大越不透明。 */
    const val rearCamOpacity = "rearCamOpacity"
    /** 小窗方向："bottom_right" / "bottom_left" / "top_right" / "top_left"。 */
    const val rearCamCorner = "rearCamCorner"

    // 出行模式
    const val travelMode = "travelMode"  // 求职/约会/聚会/旅游

    // 步态参数
    const val stepThreshold = "stepThreshold"    // 触发步数阈值
    const val staticResetSec = "staticResetSec"  // 静置多少秒重置

    // ===== 手电筒静置提醒 =====
    /** 手电静置提醒时间（分钟）：手电开着+静置多久后弹提醒 */
    const val torchStaticMinutes = "torchStaticMinutes"

    // ===== 后置看路测试指令（实验区用，跨进程） =====
    /** 后置看路测试动作：open_full / open_small / close / ""（空=无动作） */
    const val testRearCamAction = "testRearCamAction"

    // 急救卡
    const val emerName = "emerName"
    const val emerBlood = "emerBlood"
    const val emerAddress = "emerAddress"
    const val emerContactName = "emerContactName"
    const val emerContactPhone = "emerContactPhone"

    /** 配置文件名，放在 files/ 目录下，Hook端直接读这个文件 */
    const val CONFIG_FILE = "safewalk_config.json"
}

/**
 * 新版配置读写：UI端写入 + 导出JSON，Hook端读JSON。
 * 不需要 XSharedPreferences，不需要 MODE_WORLD_READABLE。
 */
object SafeWalkConfig {

    /** UI端：写入配置并导出成JSON文件 */
    @JvmStatic
    fun exportConfig(ctx: Context) {
        val sp = ctx.getSharedPreferences("safewalk_xsp", Context.MODE_PRIVATE)
        val json = JSONObject()

        // 遍历所有key，写进JSON
        sp.all.forEach { (k, v) ->
            when (v) {
                is Boolean -> json.put(k, v)
                is Int -> json.put(k, v)
                is Long -> json.put(k, v)
                is Float -> json.put(k, v)
                is String -> json.put(k, v)
            }
        }

        // 写到 files/ 目录
        ctx.openFileOutput(SafeWalkPrefKey.CONFIG_FILE, Context.MODE_PRIVATE).use {
            it.write(json.toString().toByteArray())
        }
    }

    /** Hook端：直接读JSON文件 */
    @JvmStatic
    fun loadConfigFromFile(): JSONObject? {
        return try {
            val path = "/data/data/io.github.wumisc.safewalk/files/${SafeWalkPrefKey.CONFIG_FILE}"
            val f = java.io.File(path)
            if (!f.exists()) return null
            JSONObject(f.readText())
        } catch (e: Exception) {
            null
        }
    }
}
