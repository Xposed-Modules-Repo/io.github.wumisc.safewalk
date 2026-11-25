package io.github.wumisc.safewalk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 【v1.1 新增】Hook 侧显示取件客户端（运行在 SystemUI 进程）。
 *
 * 职责：收 App 发来的 SHOW（只带一次性 token）→ 后台线程经 Binder 从
 * DisplayProvider.take 取最小载荷 → 主线程交给 EmergencyOverlay 渲染；
 * 收到 VISIBLE/HIDE 后退场并兜底 invalidate。
 *
 * 取件绝不在 SystemUI 主线程做（Binder 可能冷启动 App 进程，耗时几百 ms）。
 */
object DisplayClient {
    private const val EXTRA_EVENT_ID = "event_id"

    private val main = Handler(Looper.getMainLooper())

    /** 取件专用单线程；热重载释放后置空，新一代 install 再重建。 */
    private var executor: java.util.concurrent.ExecutorService? = null

    @Volatile
    private var ctx: Context? = null
    @Volatile
    private var overlay: EmergencyOverlay? = null
    @Volatile
    private var currentToken: String? = null
    /** 已注册的接收器引用，dispose 时逐个反注册，不把旧闭包留在 SystemUI 上。 */
    private val registered = mutableListOf<BroadcastReceiver>()

    private val showReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != SafeWalkPrefKey.ACTION_EMERGENCY_SHOW) return
            if (intent.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
                != SafeWalkPrefKey.BROADCAST_TOKEN
            ) {
                EmergencyDiag.event(EmergencyDiag.P_SEC_REJECT, "SHOW 广播令牌不匹配，忽略")
                return
            }
            val token = intent.getStringExtra(SafeWalkPrefKey.EXTRA_DISPLAY_TOKEN) ?: return
            val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: ""
            onShow(token, eventId)
        }
    }

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != SafeWalkPrefKey.ACTION_EMERGENCY_VISIBLE &&
                action != SafeWalkPrefKey.ACTION_EMERGENCY_HIDE
            ) return
            if (intent.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
                != SafeWalkPrefKey.BROADCAST_TOKEN
            ) return
            onExit()
        }
    }

    /** 由 SafeWalkLSPHook.runInit 的 step("显示取件客户端") 调用（幂等） */
    @JvmStatic
    fun install(appCtx: Context, ov: EmergencyOverlay) {
        val c = appCtx.applicationContext ?: appCtx
        ctx = c
        overlay = ov
        if (executor == null || executor!!.isShutdown) {
            executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "SafeWalk-display").apply { isDaemon = true }
            }
        }
        val showFilter = IntentFilter(SafeWalkPrefKey.ACTION_EMERGENCY_SHOW)
        val exitFilter = IntentFilter().apply {
            addAction(SafeWalkPrefKey.ACTION_EMERGENCY_VISIBLE)
            addAction(SafeWalkPrefKey.ACTION_EMERGENCY_HIDE)
        }
        // 复用成熟的带 signature CONTROL 权限注册：第三方在分发阶段就被系统挡掉
        register(c, showReceiver, showFilter)
        // 【BUG 修复】VISIBLE/HIDE 原来定义了 exitReceiver 却从未注册：
        // 急救卡可见/取消后 ACK 广播被 showReceiver 提前丢弃，onExit 永远不跑，
        // 覆盖层字段副本与一次性 token 得不到清理。现在单独注册退场接收器。
        register(c, exitReceiver, exitFilter)
        EmergencyDiag.event(EmergencyDiag.P_HOOK, "display_client_installed")
    }

    private fun register(c: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        try {
            ReceiverCompat.registerExportedWithPermission(
                c, receiver, filter, SafeWalkLSPHook.CONTROL_PERMISSION
            )
            registered.add(receiver)
        } catch (_: Throwable) {
        }
    }

    /**
     * 【API 102 热重载】释放旧一代在 SystemUI 里持有的接收器、取件线程与上下文引用。
     * 不释放的话旧闭包会继续收 SHOW/VISIBLE/HIDE，操作已作废的 overlay 与 ctx。
     */
    @JvmStatic
    fun dispose() {
        val c = ctx
        for (r in registered) {
            try { c?.unregisterReceiver(r) } catch (_: Throwable) {
            }
        }
        registered.clear()
        try { executor?.shutdownNow() } catch (_: Throwable) {
        }
        executor = null
        currentToken = null
        overlay = null
        ctx = null
    }

    private fun onShow(token: String, eventId: String) {
        currentToken = token
        executor?.execute {
            val fields = takeViaBinder(token)
            main.post {
                if (fields == null || DisplayFields.isBlank(fields)) {
                    // 降级：token 失效/过期/鉴权拒/无字段 → 不渲染医疗内容，不崩、不阻塞
                    overlay?.showFallback(eventId)
                    EmergencyDiag.event(EmergencyDiag.P_OVERLAY, "fields_unavailable event=$eventId")
                } else {
                    overlay?.render(fields, eventId)
                }
            }
        }
    }

    private fun takeViaBinder(token: String): DisplayFields? = try {
        val c = ctx ?: return null
        val uri = Uri.parse(
            "content://${SafeWalkPrefKey.DISPLAY_AUTHORITY}${SafeWalkPrefKey.DISPLAY_PATH}"
        )
        val b = c.contentResolver.call(uri, DisplayProvider.METHOD_TAKE, token, null)
        b?.getString(DisplayProvider.KEY_PAYLOAD)?.let { DisplayFields.fromJson(it) }
    } catch (t: Throwable) {
        EmergencyDiag.event(
            EmergencyDiag.P_DISPLAY,
            "binder_take_failed: ${t.javaClass.simpleName} token=${EmergencyDiag.tokenHash4(token)}"
        )
        null
    }

    /** Activity 可见 / 用户取消：覆盖层退场 + 清字段副本 + 兜底清槽 */
    private fun onExit() {
        overlay?.hide()
        val token = currentToken
        currentToken = null
        if (token.isNullOrBlank()) return
        executor?.execute {
            try {
                val c = ctx ?: return@execute
                val uri = Uri.parse(
                    "content://${SafeWalkPrefKey.DISPLAY_AUTHORITY}${SafeWalkPrefKey.DISPLAY_PATH}"
                )
                c.contentResolver.call(uri, DisplayProvider.METHOD_INVALIDATE, token, null)
            } catch (_: Throwable) {
            }
        }
    }
}
