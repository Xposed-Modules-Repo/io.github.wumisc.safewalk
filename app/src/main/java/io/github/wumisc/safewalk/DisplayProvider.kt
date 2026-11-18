package io.github.wumisc.safewalk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * 【v1.1 新增】一次性显示通道 Binder 端点（App 进程）。
 *
 * 与 [ConfigProvider] 物理隔离：不同 authority / path / 方法 / 数据源 / 鉴权实现。
 * 医疗字段的**唯一**跨进程出口就是 [call] 的 take 分支，返回后槽立即销毁。
 * query() 恒返回空 Cursor，绝不让自己变成第二条"配置读取"通道。
 *
 * Manifest 刻意**不挂** android:permission：CONTROL 是本应用自签名 signature 权限，
 * SystemUI 是系统签名，挂上后它的 call() 会被系统直接拒绝。
 */
class DisplayProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = SafeWalkPrefKey.DISPLAY_AUTHORITY
        const val METHOD_TAKE = "take"
        const val METHOD_INVALIDATE = "invalidate"
        const val KEY_PAYLOAD = "payload"
        private const val COLUMN = "json"
    }

    override fun onCreate(): Boolean = true

    /**
     * 独立实现、fail-closed 的 uid + 包名双重鉴权（逻辑与 ConfigProvider 同源，但不复用其方法/集合）。
     *
     * SystemUI 在部分 ROM（HyperOS 3/4）跑在应用段 uid（≥10000），此时靠 Binder
     * 提供的、第三方无法伪造的 callingPackage 判定；其余一律拒绝。
     */
    private fun isTrustedDisplayCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return true
        if (uid < Process.FIRST_APPLICATION_UID) {
            return callingPackage == "com.android.systemui"
        }
        val pkg = callingPackage
        val trusted = pkg == "com.android.systemui" ||
            pkg == "com.miui.systemui" ||
            pkg == "com.android.keyguard" ||
            (pkg != null && pkg.startsWith("com.android.systemui:"))
        if (trusted) {
            Log.i("SafeWalk", "[SEC:COMPAT] DisplayProvider 放行应用段 uid 的 SystemUI（pkg=$pkg）")
            return true
        }
        Log.w("SafeWalk", "[SEC:REJECT] DisplayProvider 拒绝非受信调用方（pkg=$pkg uid=$uid）")
        return false
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // fail-closed：鉴权失败返回空 Bundle，不抛异常打断 SystemUI 取件线程
        if (!isTrustedDisplayCaller()) return Bundle()
        return when (method) {
            METHOD_TAKE -> {
                val fields = DisplayPayloadStore.take(arg, SystemClock.elapsedRealtime())
                EmergencyDiag.event(
                    EmergencyDiag.P_DISPLAY,
                    "take " + if (fields == null) "miss" else "hit token=" + EmergencyDiag.tokenHash4(arg)
                )
                if (fields == null) Bundle() else Bundle().apply { putString(KEY_PAYLOAD, fields.toJson()) }
            }
            METHOD_INVALIDATE -> {
                DisplayPayloadStore.invalidate(arg)
                Bundle()
            }
            else -> null
        }
    }

    /** 恒空：杜绝被当成第二条配置读取通道 */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor = MatrixCursor(arrayOf(COLUMN))

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
