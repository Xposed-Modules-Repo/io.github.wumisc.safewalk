package io.github.wumisc.safewalk

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import io.github.wumisc.safewalk.torch.ACTION_APP_TORCH_OFF
import io.github.wumisc.safewalk.torch.ACTION_APP_TORCH_ON

/**
 * 手电通知按钮的落点（**Activity**，不是广播）。
 *
 * ## 为什么从广播换成 Activity
 *
 * 夜间提醒由 SystemUI 里的 Hook 发出，按钮却要驱动应用进程里的相机。
 * 广播这条路在两个环节上都不保证：
 *
 *  1. **进程可能不在**。夜间应用进程常被 ROM 回收，跨进程广播能不能把它拉起来
 *     取决于厂商的后台策略，HyperOS 上大概率直接丢弃 —— 用户看到的就是
 *     「点了没反应」，而且日志里连一行都没有，连排查的入口都不给。
 *  2. **拉起来了也未必能开相机**。`setTorchMode` 要求持有 CAMERA 权限，
 *     而进程刚被广播唤醒时，`WalkPreviewService` 未必来得及初始化，
 *     「预览占着摄像头」这个判断也就做不准。
 *
 * `PendingIntent.getActivity` 没有这两个问题：点击通知是**用户操作**，
 * 系统必须把 Activity 拉起来；Activity 在前台时，`startService` 收预览、
 * `setTorchMode` 点灯全都是合法调用。这是 Android 上唯一有保证的
 * 「点通知执行动作」方式。
 *
 * 界面本身是透明的，用户只会看到一次 Toast 和亮起的闪光灯。
 */
class TorchActionActivity : Activity() {

    companion object {
        private const val TAG = "SafeWalk-Torch"
    }

    @Suppress("DEPRECATION") // else 分支为 minSdk30–33 的有意兼容路径
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先鉴权再做事。这个页面是 exported 的（通知 PendingIntent 必须能跨进程拉起），
        // 而它一被执行就会点亮闪光灯 —— 不校验的话，任意应用 startActivity 一个
        // 空 Intent 就能悄悄开灯（耗电、给偷拍打光），或者把用户正用的灯熄掉。
        //
        // 两条判据：
        // ① action 必须在白名单里（原来写的是 `action != OFF` 就算开灯，
        //    于是 null / 任意 action 都会去开灯）；
        // ② 调用方必须可信：API 34+ 用 launchedFromUid，低版本退回广播令牌
        //    （getLaunchedFromUid 是 API 34 才有的方法，33 上根本不存在）。
        val action = intent?.action
        val enable: Boolean = when (action) {
            ACTION_APP_TORCH_ON -> true
            ACTION_APP_TORCH_OFF -> false
            else -> {
                DiagLog.log("[torch] 透明页被非法 action 拉起（${action ?: "null"}），拒绝")
                finish()
                return
            }
        }
        if (!isTrustedCaller()) {
            DiagLog.log("[torch] 透明页被不可信来源拉起，拒绝")
            finish()
            return
        }

        // 透明页：不画任何东西，只为拿到一次「前台身份」
        // Android 14(34) 起 overridePendingTransition 被废弃，改用 overrideActivityTransition
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }

        val app = applicationContext
        DiagLog.init(app)
        DiagLog.log("[torch] 通知按钮(Activity): ${if (enable) "开灯" else "关灯"}")

        var done = false
        TorchController.set(this, enable) { r ->
            if (done) return@set
            done = true
            DiagLog.log(
                if (r.ok) "[torch] ✅ 按钮操作成功"
                else "[torch] ❌ 按钮操作失败: ${r.reason} ${r.message}"
            )
            toast(
                if (r.ok) (if (enable) "手电已开启" else "手电已关闭")
                else "手电打不开：${r.message}"
            )
            finish()
        }

        // 兜底：点灯链路最多 4 秒（预览让路 500ms + 三次重试），
        // 到时还没结果就自己关掉，绝不留一个透明页面挂在后台
        window.decorView.postDelayed({
            if (!done) {
                done = true
                DiagLog.log("[torch] ⚠️ 4 秒未拿到结果，关闭页面")
                finish()
            }
        }, 4_500L)
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    /**
     * 调用方是否可信：系统进程 / 系统 uid 段 / 本应用自己。
     *
     * `Activity.getLaunchedFromUid()` 直到 **API 34** 才存在，minSdk 是 33，
     * 所以在 Android 13 上取不到 uid，只能退回广播令牌（由通知方写入 extra）。
     * 这不是完美的门禁，但比「完全不校验」强得多，而且两条路径都堵住了
     * 「空 Intent 就能点灯」这个最容易利用的入口。
     */
    private fun isTrustedCaller(): Boolean {
        val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                launchedFromUid
            } catch (_: Throwable) {
                -1
            }
        } else {
            -1
        }
        if (uid == android.os.Process.SYSTEM_UID ||
            uid == android.os.Process.myUid() ||
            uid in 1 until android.os.Process.FIRST_APPLICATION_UID
        ) return true
        val token = try {
            intent?.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
        } catch (_: Throwable) {
            null
        }
        return token != null && token == SafeWalkPrefKey.BROADCAST_TOKEN
    }
}
