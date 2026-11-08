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
}
