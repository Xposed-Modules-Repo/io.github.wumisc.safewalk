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
    /**
     * 判断一条广播的发送方是否可信（系统进程 / 系统 uid 段 / SystemUI / 本应用自己）。
     *
     * 【修复：HyperOS 3/4 上「所有跨进程指令被静默丢弃」的总根因】
     * 旧版只放行 SYSTEM_UID(1000)、myUid()、以及 uid∈[1,10000) 的系统段。
     * 但 HyperOS/部分 ROM 把 `com.android.systemui` 分配在**应用段 uid（≥10000）**
     * 上，于是 Hook 从 SystemUI 发来的 ACK / 预览 / 手电指令一律被判 `false`。
     * 而旧 `isTrustedCommand` 对 `false` 直接短路 return，令牌再正确也进不来——
     * 这正是 diag.log 里 `预览指令鉴权失败，丢弃: REAR_CAM_HOOK_HIDE` 的来源，
     * 也同时打断了「重启系统界面」的 ACK 回执与手电指令。
     *
     * 现在补两道：① API34+ 用 `getSentFromPackage()` 认 SystemUI 包名；
     * ② `getSentFromUid()` 不可信时降级为「仅令牌」（与 API33 及以下同等级防护），
     * 不再一票否决。Hook 侧接收器另有 signature 级权限在分发阶段挡第三方，
     * 因此令牌兜底不会引入新的越权面。
     *
     * @return `true` 确认可信 / `false` 确认不可信 / `null` 拿不到身份（API≤33 或反射失败）
     */
    @JvmStatic
    fun senderIsTrusted(receiver: BroadcastReceiver): Boolean? {
        if (android.os.Build.VERSION.SDK_INT < 34) return null
        return try {
            val uid = receiver.sentFromUid
            // ① 系统 uid 段 / 本应用自身：直接可信
            if (uid == android.os.Process.SYSTEM_UID ||
                uid == android.os.Process.myUid() ||
                uid in 1 until android.os.Process.FIRST_APPLICATION_UID
            ) return true
            // ② 应用段 uid：按发送方包名二次确认是不是 SystemUI。
            //    HyperOS 3/4、部分三星/一加 ROM 都属于这一类。
            val pkg = receiver.sentFromPackage
            if (pkg == "com.android.systemui" || pkg == "com.miui.systemui" ||
                pkg == "com.android.systemui:screenshot" ||
                pkg == "com.android.keyguard"
            ) return true
            false
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 统一的跨进程入口鉴权：**令牌是硬门槛，uid 只作分级与日志**。
     *
     * 旧实现 `if (byUid == false) return false` 在 API34+ 会把合法 SystemUI 广播
     * 全部丢弃（见 [senderIsTrusted] 注释）。现在无论 uid 判定结果如何，只要令牌
     * 不匹配就拒绝；令牌匹配时按 uid 分级记录并放行。`null`（API≤33）沿用
     * 既有「纯令牌」防护等级，行为不变。
     */
    @JvmStatic
    fun isTrustedCommand(receiver: BroadcastReceiver, token: String?): Boolean =
        isTrustedCommand(receiver, token, SafeWalkPrefKey.BROADCAST_TOKEN)

    /**
     * **严格版**鉴权：只给「会产生物理后果」的指令用（目前是撞击广播）。
     *
     * 与 [isTrustedCommand] 的唯一区别：`senderIsTrusted()` 明确返回 `false`
     * （即 API 34+ 拿得到发送方身份，且不是系统/SystemUI/本应用）时**直接拒绝**，
     * 不再因为令牌匹配就降级放行。
     *
     * 为什么必须严格：令牌是写死在代码里的字符串（`safewalk-bc-2.5`），
     * 反编译 APK 就能拿到。宽松版在 HyperOS 应用段 uid 的场景下只能靠令牌兜底，
     * 于是任意一个第三方应用反编译出令牌、发一条 CRASH_DETECTED，
     * 就能让急救卡走完倒计时、真的给紧急联系人发短信并拨号。
     * 手电/日志这类指令误触发只是烦人，撞击误触发是真花钱、真骚扰联系人，
     * 所以这里单独收紧。
     *
     * `null`（API 33 及以下拿不到发送方身份）仍沿用纯令牌防护——
     * 这些系统版本上 `sentFromUid` 根本不存在，没有更强的判据可用。
     */
    @JvmStatic
    fun isStrictCommand(receiver: BroadcastReceiver, token: String?, expected: String?): Boolean {
        if (expected == null || token != expected) {
            android.util.Log.w("SafeWalk", "[SEC:REJECT] 严格通道令牌缺失/不匹配，丢弃")
            return false
        }
        return when (senderIsTrusted(receiver)) {
            true -> true
            false -> {
                android.util.Log.w("SafeWalk", "[SEC:REJECT] 严格通道发送方不可信，拒绝执行")
                false
            }
            null -> true
        }
    }

    /** 同上，但允许指定别的令牌（例如「重启系统界面」那条通道有自己的令牌） */
    @JvmStatic
    fun isTrustedCommand(receiver: BroadcastReceiver, token: String?, expected: String?): Boolean {
        val byUid = senderIsTrusted(receiver)
        // 令牌必须先过：它是一直存在、且与 API 版本无关的唯一硬门槛。
        if (expected == null || token != expected) {
            android.util.Log.w("SafeWalk", "[SEC:REJECT] 广播令牌缺失/不匹配，丢弃（uid=$byUid）")
            return false
        }
        return when (byUid) {
            true -> true  // 系统 uid 或 SystemUI，正常放行
            false -> {    // 应用段 uid 但令牌匹配：兼容 ROM，降级放行并留痕
                android.util.Log.i("SafeWalk",
                    "[SEC:COMPAT] 发送方 uid 非系统段，令牌匹配 → 兼容放行（HyperOS/应用段 SystemUI）")
                true
            }
            null -> true  // API≤33：拿不到身份，纯令牌防护（历史行为）
        }
    }
}
