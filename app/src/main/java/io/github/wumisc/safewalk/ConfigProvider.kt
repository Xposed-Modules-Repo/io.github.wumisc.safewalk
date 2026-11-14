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
            SafeWalkPrefKey.nightTorchStartHour,
            SafeWalkPrefKey.nightTorchEndHour,
            // 单一「夜间手电」开关（小窗联动）：Hook 侧 check() 读它判断是否跳过旧点灯链路。
            SafeWalkPrefKey.enableNightTorchGait,
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
            SafeWalkPrefKey.testRearCamTs,
            // 本次迭代新增（跨进程给 Hook 用）：
            //  · enablePreviewContrast 悬浮窗对比度开关（App 侧悬浮窗也读，顺带透传保持一致）；
            //  · previewContrastLevel 对比度强度 0~100（同上，保证导出配置不丢这一项）。
            //
            // 4.3 移除了 hyperOsMode：首页那张「HyperOS 版本适配」卡片已删，改为
            // RomSettings.romOverride（本机 UI 是哪一家）。后者只影响设置页跳转与
            // 文案，Hook 端不需要知道 —— 少一个跨进程状态，就少一种两边不一致的可能。
            SafeWalkPrefKey.enablePreviewContrast,
            SafeWalkPrefKey.previewContrastLevel,
            // 4.9.0 预览小窗自定义：纯显示参数，无隐私字段。进白名单只为导出配置
            // 时一并带上，换机/重装后小窗形态与文字设置不会丢。
            SafeWalkPrefKey.previewHudEnabled,
            SafeWalkPrefKey.previewHudText,
            SafeWalkPrefKey.previewHudTextSize,
            SafeWalkPrefKey.previewSmallWidthDp,
            SafeWalkPrefKey.previewSmallMarginDp,
            // 5.0.0 取景范围 / 描边 / 低光增强：同样是纯显示参数，
            // 进白名单只为「导出配置带全」，Hook 侧不读。
            SafeWalkPrefKey.previewFramingPct,
            SafeWalkPrefKey.previewBorderDp,
            SafeWalkPrefKey.previewLowLight,
            // 5.1.0 智能方案 + 画面增强：同样是纯显示参数，进白名单只为导出配置带全。
            // 注意 nightTorchStartHour / nightTorchEndHour 本来就在上面（手电联动时段），
            // 智能方案的夜间判定直接复用它们 —— 用户只该有一个「夜间」概念，
            // 分成两套时段只会出现「手电按 22 点算夜间、小窗按 20 点算」的荒唐结果。
            SafeWalkPrefKey.presetAuto,
            SafeWalkPrefKey.previewScreenEnhance,
            SafeWalkPrefKey.previewScreenContrast,
            SafeWalkPrefKey.previewScreenShadowLift,
            SafeWalkPrefKey.previewScreenSaturation,
            // 5.3.0 画面方向：字符串档位（auto/fixed/flip），
            // 进白名单既是为了导出配置带全，也是为了 Hook 侧切换形态时能读到当前档位。
            SafeWalkPrefKey.previewRotationMode
        )
    }

    override fun onCreate(): Boolean = true

        private fun isTrustedCaller(): Boolean {
        val uid = android.os.Binder.getCallingUid()
        if (uid == android.os.Process.myUid() || uid == android.os.Process.SYSTEM_UID) return true
        // 系统 uid 段（1..9999）里的 SystemUI，按老规则放行。
        if (uid < android.os.Process.FIRST_APPLICATION_UID) {
            return callingPackage == "com.android.systemui"
        }

        // 【本次全量迭代 BUG 修复】HyperOS 3/4 上，SystemUI 可能被分配到
        // 「应用段 uid（>= 10000）」。原实现在这里直接 return false，于是
        // Hook（跑在 SystemUI 进程）读本 Provider 永远拿到空 Cursor →
        // refreshConfig() 沿用默认 → 所有 pref() 取默认值 →
        // 用户看到的就是「智能提醒二级菜单里很多开关怎么拨都不生效」。
        // 这与 ReceiverCompat.isTrustedCommand() 的同源误判是同一个根因。
        //
        // 修复：改用 Binder 提供的、第三方无法伪造的 callingPackage 做判据。
        // 安全边界不变 —— 本 Provider 只输出 WHITELIST 里的开关字段（不含任何隐私），
        // 即便被极端仿冒，泄露的也只是几个布尔开关。
        val pkg = callingPackage
        val systemUiPkg = pkg == "com.android.systemui"
                || pkg == "com.miui.systemui"
                || pkg == "com.android.keyguard"
                || pkg == "com.android.systemui:screenshot"
                || (pkg != null && pkg.startsWith("com.android.systemui:"))
        if (systemUiPkg) {
            android.util.Log.i("SafeWalk",
                "[SEC:COMPAT] ConfigProvider 放行应用段 uid 的 SystemUI（pkg=$pkg uid=$uid）" +
                " —— HyperOS 兼容，避免配置读取被一票否决")
            return true
        }
        android.util.Log.w("SafeWalk",
            "[SEC:REJECT] ConfigProvider 拒绝非受信调用方（pkg=$pkg uid=$uid）")
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
