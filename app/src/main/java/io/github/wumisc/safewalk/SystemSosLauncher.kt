package io.github.wumisc.safewalk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 系统 SOS 联动（P1-1）：候选 Intent 链 + 逐个 resolveActivity 校验 + 全失败兜底。
 *
 * AOSP 的 EmergencyAffordanceManager.performEmergencyCall() 无公开 SDK，仅系统签名可用；
 * 本项目一律用公开 Intent：ACTION_DIAL 预填紧急号码（不静默代拨），
 * 外加厂商"紧急呼救设置"入口，全失败退应用详情页。
 */
object SystemSosLauncher {
    private const val ACTION_EMERGENCY_SETTINGS = "android.telephony.action.EMERGENCY_CALL"

    private val SOS_SETTING_COMPONENTS = arrayOf(
        "com.android.settings/.Settings\$EmergencySettingsActivity",
        "com.miui.securitycenter/.emergency.EmergencyActivity",
        "com.huawei.emergency/.MainActivity",
        "com.coloros.safecenter/.permission.startup.EmergencyActivity",
        "com.iqoo.call/.emergency.EmergencyActivity"
    )

    /** 预填拨号盘（不静默代拨）：调用方必须让用户自己按拨号键 */
    @JvmStatic
    fun dialEmergency(ctx: Context, number: String): Boolean = try {
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        resolveFirst(ctx, listOf(i))
    } catch (_: Throwable) {
        false
    }

    /** 打开系统紧急呼救设置；全失败时退到应用详情页 */
    @JvmStatic
    fun openSystemSos(ctx: Context): Boolean {
        val candidates = ArrayList<Intent>()
        try {
            candidates.add(Intent(ACTION_EMERGENCY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
        for (path in SOS_SETTING_COMPONENTS) {
            try {
                val cn = ComponentName.unflattenFromString(path) ?: continue
                candidates.add(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }
        return resolveFirst(ctx, candidates)
    }

    /** 逐个校验是否有 Activity 能接，避免 ActivityNotFound 崩溃 */
    private fun resolveFirst(ctx: Context, candidates: List<Intent>): Boolean {
        val pm = ctx.packageManager
        for (i in candidates) {
            try {
                if (i.resolveActivity(pm) != null) {
                    ctx.startActivity(i)
                    EmergencyDiag.event(EmergencyDiag.P_SOS, "system_sos_opened")
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }
}
