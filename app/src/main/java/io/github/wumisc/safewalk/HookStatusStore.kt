package io.github.wumisc.safewalk

import android.content.Context
import android.content.SharedPreferences

/**
 * 【Hook 心跳记录】App 侧保存最近一次「框架在线」广播的内容。
 *
 * 数据来源：`SafeWalkLSPHook` 每 60 秒发一次 `ACTION_MODULE_READY`（复用既有通道，
 * 不再新开一条广播），里带锚点数、框架名、API 版本。SystemUI 重启后钩子没回来时
 * 广播就断，这里的时间戳自然变旧 —— 首页状态概览据此判「注入失联」。
 *
 * 刻意不复用 `safewalk_xsp`：那是用户配置，会被配置导入/导出与云备份覆盖；
 * 心跳属于运行时观测数据，不该跟着配置跑。
 */
object HookStatusStore {

    private const val SP = "safewalk_hook_status"
    private const val KEY_TS = "last_ts"
    private const val KEY_VERSION = "version_name"
    private const val KEY_ANCHORS = "anchors"
    private const val KEY_FRAMEWORK = "framework"
    private const val KEY_API = "api"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    @JvmStatic
    fun record(
        ctx: Context,
        versionName: String,
        anchors: Int,
        framework: String,
        api: Int,
        now: Long
    ) {
        try {
            sp(ctx).edit()
                .putLong(KEY_TS, now)
                .putString(KEY_VERSION, versionName)
                .putInt(KEY_ANCHORS, anchors)
                .putString(KEY_FRAMEWORK, framework)
                .putInt(KEY_API, api)
                .apply()
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun lastTs(ctx: Context): Long = try {
        sp(ctx).getLong(KEY_TS, 0L)
    } catch (t: Throwable) {
        0L
    }

    @JvmStatic
    fun versionName(ctx: Context): String = try {
        sp(ctx).getString(KEY_VERSION, "") ?: ""
    } catch (t: Throwable) {
        ""
    }

    @JvmStatic
    fun anchors(ctx: Context): Int = try {
        sp(ctx).getInt(KEY_ANCHORS, 0)
    } catch (t: Throwable) {
        0
    }

    @JvmStatic
    fun framework(ctx: Context): String = try {
        sp(ctx).getString(KEY_FRAMEWORK, "") ?: ""
    } catch (t: Throwable) {
        ""
    }

    @JvmStatic
    fun api(ctx: Context): Int = try {
        sp(ctx).getInt(KEY_API, 0)
    } catch (t: Throwable) {
        0
    }

    /** 曾经收到过 Hook 广播（区分「没装框架」与「装了但没生效」的关键）。 */
    @JvmStatic
    fun everSeen(ctx: Context): Boolean = lastTs(ctx) > 0L
}
