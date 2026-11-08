package io.github.wumisc.safewalk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

/**
 * 配置只读通道（跨进程）。
 *
 * 背景：Hook 代码运行在 SystemUI 进程，无法读取本应用的私有目录
 * （/data/data/io.github.wumisc.safewalk/files/ 对其它 uid 不可读），
 * 所以通过一个只读 ContentProvider 把「功能开关」暴露给 Hook 端。
 *
 * 安全约束：只输出 [WHITELIST] 中的开关类字段，急救卡（姓名/血型/地址/联系人）
 * 等个人隐私数据一律不对外暴露。
 */
class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.github.wumisc.safewalk.config"
        private const val PREFS = "safewalk_xsp"
        private const val COLUMN = "json"

        /**
         * 调用方判定：**用 uid，不用包名**。
         *
         * 原来按 `callingPackage` 查白名单，有两个漏洞：
         * ① 它为 null 时直接放行（fail-open），一旦某次 Binder 身份取不到，
         *    白名单就等于没写；
         * ② 包名是字符串，判断错了就绕过，uid 是内核给的，伪造不了。
         *
         * SystemUI 以 android.uid.system（1000）运行，所以放行 1000 与本应用自身 uid 即可。
         *
         * 注意：这个函数必须是**实例方法**——`callingPackage` 是 ContentProvider 的实例属性，
         * 放在 companion 里编译不过。
         */
        /** 允许跨进程读取的 key，仅限功能开关与显示参数，不含任何个人隐私字段 */
        val WHITELIST: Set<String> = setOf(
            SafeWalkPrefKey.masterEnable,
            SafeWalkPrefKey.enableBatteryWarning,
            SafeWalkPrefKey.enableIdReminder,
            SafeWalkPrefKey.enableSitHealthRemind,
            SafeWalkPrefKey.enableSocialTipCard,
            SafeWalkPrefKey.enableNightTorch,
            SafeWalkPrefKey.enableNightTorchAuto,
            SafeWalkPrefKey.nightTorchStartHour,
            SafeWalkPrefKey.nightTorchEndHour,
            SafeWalkPrefKey.enableRearCamera,
            SafeWalkPrefKey.rearCamMode,
            SafeWalkPrefKey.rearCamAuto,
            SafeWalkPrefKey.rearCamOpacity,
            SafeWalkPrefKey.rearCamCorner,
            SafeWalkPrefKey.travelMode,
            SafeWalkPrefKey.enableAutoTravelMode,
            SafeWalkPrefKey.stepThreshold,
            SafeWalkPrefKey.staticResetSec,
            SafeWalkPrefKey.torchStaticMinutes,
            SafeWalkPrefKey.enableCrashDetection,
            SafeWalkPrefKey.crashSensitivity,
            // 日志总开关也要透传：它不在白名单里时，Hook 侧 `pref(enableDiagLog, true)`
            // 永远拿默认值 true —— 用户关掉日志开关，SystemUI 那边照样往文件里写。
            SafeWalkPrefKey.enableDiagLog,
            SafeWalkPrefKey.testRearCamAction,
            SafeWalkPrefKey.testRearCamTs
        )
    }

    override fun onCreate(): Boolean = true

    private fun isTrustedCaller(): Boolean {
        val uid = android.os.Binder.getCallingUid()
        if (uid == android.os.Process.myUid() || uid == android.os.Process.SYSTEM_UID) return true
        // 极少数 ROM 给 SystemUI 分配了独立 uid（不是 android.uid.system）。
        // 允许按包名兜底，但必须同时满足「系统 uid 段」这个前提，
        // 普通应用（uid >= 10000）永远进不来。
        if (uid < android.os.Process.FIRST_APPLICATION_UID) {
            return callingPackage == "com.android.systemui"
        }
        return false
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        // 只认 /config，别让随便一个 path 也能读出配置
        if (uri.path != "/config") return MatrixCursor(arrayOf(COLUMN))
        // 白名单必须 fail-closed：判定不成立就返回空，而不是放行
        if (!isTrustedCaller()) return MatrixCursor(arrayOf(COLUMN))

        val json = JSONObject()
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.all?.forEach { (k, v) ->
            if (k in WHITELIST) {
                when (v) {
                    is Boolean, is Int, is Long, is Float, is String -> json.put(k, v)
                }
            }
        }
        return MatrixCursor(arrayOf(COLUMN)).apply {
            addRow(arrayOf<Any>(json.toString()))
        }
    }

    /** 支持 call("clearTestAction")：Hook 端执行完实验区指令后清空该字段 */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!isTrustedCaller()) return null

        if (method == "clearTestAction") {
            val ctx = context ?: return null
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()?.remove(SafeWalkPrefKey.testRearCamAction)?.apply()
            // 同步刷新 JSON 文件：Hook 端在 Provider 不可用时会回退读文件，
            // 文件里残留旧指令会导致预览被反复触发
            SafeWalkConfig.exportConfig(ctx)
            return Bundle().apply { putBoolean("ok", true) }
        }
        return null
    }

    override fun getType(uri: Uri): String = "application/json"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
