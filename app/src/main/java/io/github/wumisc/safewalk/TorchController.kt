package io.github.wumisc.safewalk

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.wumisc.safewalk.torch.TorchHelper
import io.github.wumisc.safewalk.torch.TorchResult

/**
 * 手电控制（**应用进程**）。
 *
 * ## 为什么手电要搬回应用进程
 *
 * 原来手电只有一个通道：通知按钮 → 广播给 SystemUI 里的 Hook → Hook 调
 * `CameraManager.setTorchMode`。听起来很合理（系统进程权限最高），但它有一个
 * 无解的场景：**本应用自己的后视预览正开着**。
 *
 * 一颗摄像头同一时刻只能有一个使用者。预览在应用进程里拿着 CameraDevice，
 * 系统进程的 setTorchMode 就会抛 `CAMERA_IN_USE`——Hook 那边只会打一行
 * 「开启手电结果: false」，用户看到的是「按了没反应」。反过来，
 * 预览卡死时泄漏的 CameraDevice 也会一直占着摄像头，手电照样点不亮。
 *
 * 而应用进程本来就持有 CAMERA 权限（预览需要），也**只有它知道自己正占着相机**，
 * 所以「先把预览收起来，再点灯」这件事只能在这里做。
 *
 * ## 失败原因不再吞掉
 *
 * setTorchMode 抛 CAMERA_IN_USE 时用户看到的往往是「灯没亮，也没提示」。
 * 这里把原因分出来：占用就等一下重试（相机释放是异步的），
 * 系统禁用就直接说明，不再让用户对着一颗不亮的灯猜。
 */
object TorchController {

    /** 让预览释放相机后再点灯，给 HAL 留出断开时间 */
    private const val PREVIEW_RELEASE_WAIT_MS = 500L
    /** 相机被占用时的重试间隔 */
    private const val RETRY_WAIT_MS = 700L
    /** 最多尝试几次（含首次） */
    private const val MAX_ATTEMPTS = 3

    private val handler = Handler(Looper.getMainLooper())

    /** 最近一次操作结果，页面/实验室读它显示原因 */
    @Volatile
    var lastResult: TorchResult = TorchResult(false)
        private set

    /** 最近一次成功切换到的状态 */
    @Volatile
    var isOn: Boolean = false
        private set

    /**
     * 开/关手电。
     *
     * @param callback 必定在主线程回调，携带真实结果（不再只有 true/false）
     */
    @JvmStatic
    @JvmOverloads
    fun set(ctx: Context, enable: Boolean, callback: ((TorchResult) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!enable) {
            DiagLog.log("[torch] 关闭手电")
            attempt(app, false, 1, callback)
            return
        }

        // 开灯前先给预览让路：这是 CAMERA_IN_USE 的唯一来源，也是本应用能自己解决的
        if (WalkPreviewService.isPreviewShowing()) {
            DiagLog.log("[torch] 后视预览正占用摄像头，先收起预览再开灯")
            WalkPreviewService.send(app, WalkPreviewService.ACTION_HIDE, null)
            handler.postDelayed({ attempt(app, true, 1, callback) }, PREVIEW_RELEASE_WAIT_MS)
        } else {
            attempt(app, true, 1, callback)
        }
    }

    private fun attempt(app: Context, enable: Boolean, n: Int, cb: ((TorchResult) -> Unit)?) {
        val r = TorchHelper.setTorchEx(app, enable)
        if (r.ok) {
            isOn = enable
            lastResult = r
            DiagLog.log("[torch] ${if (enable) "已开启" else "已关闭"}手电（第 $n 次）")
            cb?.invoke(r)
            return
        }
        // 占用是可恢复的：相机 close 之后 HAL 还要一小会儿才真正放开
        if (r.inUse && n < MAX_ATTEMPTS) {
            // 第一次重试时顺手再强制收一次预览。
            // 有的情况是「窗口已经收了、但设备没释放干净」，此时
            // isPreviewShowing() 返回 false，上面那步不会触发；
            // 再发一条 HIDE 是唯一能把残留设备关掉的入口。
            if (n == 1 && WalkPreviewService.isAlive()) {
                DiagLog.log("[torch] 摄像头仍被占用，强制再收一次预览")
                WalkPreviewService.send(app, WalkPreviewService.ACTION_HIDE, null)
            }
            DiagLog.log("[torch] 摄像头仍被占用（${r.message}），${RETRY_WAIT_MS}ms 后重试 第 ${n + 1} 次")
            handler.postDelayed({ attempt(app, enable, n + 1, cb) }, RETRY_WAIT_MS)
            return
        }
        lastResult = r
        DiagLog.log("[torch] ❌ 手电${if (enable) "开启" else "关闭"}失败: ${r.reason} ${r.message}")
        cb?.invoke(r)
    }
}
