package io.github.wumisc.safewalk

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

/**
 * 动态注册广播的版本兼容层。
 *
 * `Context.registerReceiver(receiver, filter, flags)` 这个三参数重载是 **API 33** 才加的，
 * 而本模块 minSdk = 30。在 Android 11 / 12 上直接调它会抛 NoSuchMethodError，
 * 而 Hook 的 init() 整段包在 try-catch 里——结果是第一处注册失败后，
 * 后面的预览广播、手电广播、电量广播**全部被跳过**，模块只剩一个空转的 tick。
 *
 * 这类问题单元测试完全测不出来（Robolectric 跑在 compileSdk 上），
 * 只有在 Android 11/12 真机上才暴露，属于和「二级菜单打不开」同一级别的问题。
 */
object ReceiverCompat {

    @JvmStatic
    @SuppressLint("NewApi", "UnspecifiedRegisterReceiverFlag")
    fun registerExported(ctx: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // RECEIVER_EXPORTED 是 API 33 的 static final int，编译期会内联成 2，
            // 所以旧系统上不会 NoSuchFieldError；这里用常量是为了让 lint 认得出语义
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            // API 30~32：三参数重载不存在，用两参数版本（该版本默认就是可跨进程接收）
            ctx.registerReceiver(receiver, filter)
        }
    }

    @JvmStatic
    @SuppressLint("NewApi", "UnspecifiedRegisterReceiverFlag")
    fun registerNotExported(ctx: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
    }

    /**
     * 注册「带签名权限的导出接收器」。
     *
     * 接收器跑在 SystemUI 进程里，为了跨进程必须 EXPORTED；但导出意味着任何应用
     * 都能往这个 action 发广播。`broadcastPermission` 让**系统在分发阶段**就校验
     * 发送方是否持有该权限——我们用的是 signature 级，只有和模块同签名的应用
     * （也就是模块自己）能通过。这比字符串令牌可靠得多：令牌能反编译拿到，
     * 签名拿不到。
     *
     * 版本差异：
     * · API 33+ 必须显式带 RECEIVER_EXPORTED 标志（targetSdk 33+ 的硬性要求）；
     * · API 30~32 只认四参数重载 `registerReceiver(r, filter, broadcastPermission, scheduler)`，
     *   这个重载从 API 1 就有，不会 NoSuchMethodError。
     */
    @JvmStatic
    @SuppressLint("NewApi", "UnspecifiedRegisterReceiverFlag")
    fun registerExportedWithPermission(
        ctx: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        permission: String
    ) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, permission, null, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter, permission, null)
        }
    }

    /**
     * 判断一条广播的发送方是否可信（系统进程 / 系统 uid 段 / 本应用自己）。
     *
     * 为什么需要：App 侧的这几个接收器（撞击、手电、日志回传、预览指令）必须
     * EXPORTED 才能收 SystemUI 的消息，但导出之后任何应用都能往同一个 action
     * 发广播。原先唯一的门禁是源码里写死的字符串令牌，反编译就能拿到——
     * 伪造一条 CRASH_DETECTED 足以让急救卡倒计时结束、真的发短信和拨号。
     *
     * `getSentFromUid()` 是 **API 34** 才有的方法，所以：
     * · 返回 `true`  → 确认可信，放行；
     * · 返回 `false` → 确认不可信，直接丢弃；
     * · 返回 `null`  → 拿不到身份（API 33 及以下），调用方继续用令牌兜底。
     */
    @JvmStatic
    fun senderIsTrusted(receiver: BroadcastReceiver): Boolean? {
        if (android.os.Build.VERSION.SDK_INT < 34) return null
        return try {
            val uid = receiver.sentFromUid
            uid == android.os.Process.SYSTEM_UID ||
                    uid == android.os.Process.myUid() ||
                    uid in 1 until android.os.Process.FIRST_APPLICATION_UID
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 统一的跨进程入口鉴权：先看发送方 uid，再验令牌。
     *
     * 两步都要过：uid 挡掉普通应用（API 34+），令牌挡掉同 uid 段但非预期的发送方，
     * 并在 API 33 及以下独自承担全部防护。
     */
    @JvmStatic
    fun isTrustedCommand(receiver: BroadcastReceiver, token: String?): Boolean =
        isTrustedCommand(receiver, token, SafeWalkPrefKey.BROADCAST_TOKEN)

    /** 同上，但允许指定别的令牌（例如「重启系统界面」那条通道有自己的令牌） */
    @JvmStatic
    fun isTrustedCommand(receiver: BroadcastReceiver, token: String?, expected: String?): Boolean {
        val byUid = senderIsTrusted(receiver)
        if (byUid == false) return false
        return expected != null && token == expected
    }
}
