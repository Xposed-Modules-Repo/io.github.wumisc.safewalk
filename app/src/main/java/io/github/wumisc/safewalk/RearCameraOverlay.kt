package io.github.wumisc.safewalk

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import java.util.concurrent.Executor
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager

/**
 * 行走模式悬浮窗。
 *
 * 由 Hook 端（SystemUI 进程）持有，展示方式完全由 [show] 的三个参数驱动：
 * 形态、不透明度、小窗停靠角。参数变化时只更新布局，切换形态才重建视图。
 *
 * 注意：这个类虽然编译进本 APK，但实际**只运行在 SystemUI 进程**。
 * 相机权限用的是系统身份（uid 1000）自己的权限，而本应用的 manifest
 * 里刻意没有声明 CAMERA —— 所以下面 openCamera 会关掉 Lint 的 MissingPermission，
 * 那是「本应用没声明」的误报，真正的权限判断就写在方法里。
 *
 * @param onFailure 画面出不来时的回调（无相机权限 / 相机没有画面）。
 *                  以前这些情况只写一行日志，用户看到的是「点预览没反应」，
 *                  再往下就是「这功能是假的」。
 * @param onStage   预览链路每走一步就回调一次（窗口挂载 / 相机打开 / 收到画面…）。
 *                  Hook 端把它转发回 App 显示在页面上——黑屏时不用再猜卡在哪。
 */
class RearCameraOverlay(
    private val ctx: Context,
    private val onFailure: ((String) -> Unit)? = null,
    private val onStage: ((String) -> Unit)? = null,
    /** 仅测试用：注入一个可记录的 WindowManager，验证窗口参数是否合法 */
    wmOverride: WindowManager? = null
) {

    companion object {
        private const val TAG = "SafeWalk-Cam"
        const val MODE_FULLSCREEN = "fullscreen"
        const val MODE_SMALLWINDOW = "smallwindow"

        const val CORNER_BR = "bottom_right"
        const val CORNER_BL = "bottom_left"
        const val CORNER_TR = "top_right"
        const val CORNER_TL = "top_left"

        /** 常见相机应用包名前缀，用于前台避让判断 */
        private val CAMERA_PACKAGES = setOf(
            "com.android.camera",
            "com.huawei.camera",
            "com.miui.camera",
            "com.xiaomi.camera",
            "com.oplus.camera",
            "com.vivo.camera",
            "com.samsung.android.camera",
            "com.oneplus.camera",
            "com.google.android.GoogleCamera"
        )

        /**
         * close 之后至少等这么久再 open。
         *
         * CameraDevice.close() 只是发起断开请求，HAL 侧的释放是异步的。
         * 紧接着调 openCamera 会和这次 close 交错，实测表现是
         * 「正在打开相机 → 相机已打开」都打了，但 createCaptureSession
         * 的 onConfigured 永远不回调，5 秒看门狗报「相机没有返回画面」。
         * 400ms 足够大多数 HAL 走完 disconnect。
         */
        private const val REOPEN_GAP_MS = 400L

        /** createCaptureSession 的宽限：超时说明会话卡死，必须主动释放重来 */
        private const val SESSION_TIMEOUT_MS = 3_000L

        /** 看门狗宽限（含模式切换的 400ms 重建延迟） */
        private const val FRAME_TIMEOUT_MS = 6_000L

        /** 相机链路最多连续重试几次（相机被别的应用占着时不能无限重开） */
        private const val MAX_CAMERA_RETRY = 2
    }

    /** 当前生效的展示参数 */
    private data class Style(val mode: String, val opacity: Int, val corner: String)

    private val wm = wmOverride
        ?: (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
    private val cm = ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surfaceTexture: SurfaceTexture? = null
    /** 预览用 Surface，必须显式 release，否则反复开关会耗尽 buffer */
    private var previewSurface: Surface? = null
    private var tv: TextureView? = null
    private var isShowing = false
    private var style = Style(MODE_FULLSCREEN, 50, CORNER_BR)

    /**
     * 相机「代号」。每次 [closeCamera] 递增一次，[openCamera] 把当时的值
     * 带进 StateCallback；回调回来时代号对不上就说明这是上一轮的设备，立刻关掉。
     *
     * 没有这个代号时踩过一个很贵的坑：小窗切全屏，旧设备的 close 还没完成，
     * 新设备就已经 onOpened 并覆盖了 [camera] 字段——旧的那台再没人持有，
     * 也永远不会被 close，于是摄像头一直处于「被打开」状态。
     * 后果是新的会话配置静默卡死（一帧都不来），同时手电 setTorchMode
     * 一直抛 CAMERA_IN_USE——预览和手电一起坏，但根因只有一个。
     */
    private var cameraGen = 0
    /** 正在等 openCamera 的回调。异步窗口期靠它挡住重复 open */
    private var opening = false
    /** 上次关闭相机的时刻，用来做 close→open 的最小间隔 */
    private var lastCloseAt = 0L
    /** 会话配置超时的兜底任务 */
    private var sessionTimeout: Runnable? = null
    /** 相机链路连续失败次数，出画面即清零 */
    private var retryCount = 0

    /** 最近一帧画面的时间戳，0 表示本次显示还没出过画面 */
    private var lastFrameTs = 0L
    private var frameWatchdog: Runnable? = null
    private var failureReported = false
    /** 视图尺寸为 0 时的重试次数（Surface 回调常常早于布局） */
    private var layoutRetry = 0

    /** 统一走 SafeWalkLSPHook 的日志出口（logcat + SystemUI 私有目录） */
    private fun diag(msg: String) {
        try {
            SafeWalkLSPHook.diag("[cam] $msg")
        } catch (t: Throwable) {
            // 单元测试环境里没有 Xposed 的类（compileOnly），
            // 这里不能因为打日志把预览逻辑带崩，退回 logcat 就好
            Log.i(TAG, msg)
        }
    }

    /** 预览链路分步回执：窗口挂没挂上、相机开没开、有没有收到画面，一步一报 */
    private var firstFrameReported = false
    private fun stage(msg: String) {
        diag(msg)
        try { onStage?.invoke(msg) } catch (_: Throwable) {}
    }

    val showing: Boolean get() = isShowing

    /**
     * 显示悬浮窗。
     * @param mode 全屏 / 小窗
     * @param opacity 不透明度 10~100，越小越透；实际渲染上限 99%（见 apply）
     * @param corner 小窗停靠角（全屏模式忽略）
     */
    fun show(mode: String = MODE_FULLSCREEN, opacity: Int = 50, corner: String = CORNER_BR) {
        val target = Style(
            if (mode == MODE_SMALLWINDOW) MODE_SMALLWINDOW else MODE_FULLSCREEN,
            opacity.coerceIn(10, 100),
            corner
        )
        // 除了参数未变，还要确认视图真的还挂在 WindowManager 上；
        // 被系统回收（切用户/内存压力）后 parent 会变 null，此时必须重建
        if (isShowing && target == style && tv?.parent != null) {
            // 视图还在，但相机未必还在：Surface 被系统重建过、上次打开失败、
            // 或者相机被别的应用抢走，都会留下「窗口在、画面全黑」的状态。
            // 原实现直接 return，于是这个黑窗口再也不会重连。
            when {
                camera == null && !opening -> {
                    diag("↻ 窗口在、相机不在，重新打开")
                    openCamera()
                }
                camera != null && session == null && !opening -> {
                    // 设备在、会话没配起来：这就是「点了没反应、也没有任何日志」
                    // 的那种僵死状态，主动清掉重连
                    diag("↻ 窗口在、设备在，但会话缺失，重新连接")
                    recoverCamera(cameraGen, "会话缺失")
                }
                else -> diag("预览已在运行，无需重复打开")
            }
            return
        }

        val modeChanged = isShowing && target.mode != style.mode
        // 视图被系统摘掉但对象还在：这是最容易被忽略的一种。
        // 直接 addView 回去，TextureView 的 SurfaceTexture 已经失效，
        // onSurfaceTextureAvailable 也不会再回调 —— 结果是窗口在、画面全黑。
        val viewDetached = tv != null && tv?.parent == null
        style = target
        isShowing = true
        failureReported = false
        layoutRetry = 0
        firstFrameReported = false

        if (modeChanged || viewDetached) {
            // 形态切换必须重建视图：尺寸、圆角、Surface 都绑在 View 上。
            //
            // 这里**不再**额外延迟 apply：重建后的 onSurfaceTextureAvailable 会
            // 立刻调 openCamera，而 openCamera 自己带 REOPEN_GAP_MS 冷却，
            // 会把真正的打开动作推迟到 HAL 断开之后。把「等」集中在一个地方，
            // 比在 show/apply 里各铺一层延迟更容易看清谁在等什么。
            releaseView()
        }
        apply()
    }

    /** 隐藏悬浮窗 */
    fun hide() {
        // 注意：不能只判断 isShowing。
        // 失败路径里窗口可能已经被收起（isShowing=false），但相机没释放干净；
        // 这时外部再发一次「收起」正是唯一的补救入口，直接 return 就把
        // 这个占着摄像头的设备永久留在那里了——手电也就跟着点不亮。
        if (!isShowing && camera == null && tv == null) return
        isShowing = false
        cancelWatchdog()
        teardown()
        Log.i(TAG, "隐藏悬浮窗")
    }

    private fun releaseView() {
        closeCamera()
        tv?.let {
            if (it.parent != null) {
                try { wm.removeView(it) } catch (_: Throwable) {}
            }
        }
        tv = null
        surfaceTexture = null
    }

    private fun apply() {
        if (tv == null) tv = buildTextureView()

        tv?.outlineProvider = roundOutline()
        tv?.invalidateOutline()
        // 透明度直接作用于视图本身（第一版实测可用的做法，不引入变量）。
        //
        // 上限刻意压在 0.99，永远不给 1.0：
        // 真机实测「全屏 + alpha=1.0」画面出不来，alpha<1 正常。
        // 全屏铺满且像素 alpha 全是 255 时，系统会把这个 layer 判成不透明，
        // 与窗口声明的 TRANSLUCENT 冲突，合成路径选错 → 黑屏；
        // alpha<1 时明确需要 GPU 混合，走的是正常路径。
        // 99% 与 100% 肉眼无差，但能稳定出画面。
        val renderAlpha = minOf(style.opacity / 100f, 0.99f)
        tv?.alpha = renderAlpha

        val lp = buildParams()
        try {
            if (tv?.parent == null) {
                wm.addView(tv, lp)
                stage("窗口已挂载: ${style.mode} ${lp.width}x${lp.height} alpha=$renderAlpha")
            } else {
                wm.updateViewLayout(tv, lp)
                stage("窗口已更新: ${style.mode} ${lp.width}x${lp.height} alpha=$renderAlpha")
            }
            armFrameWatchdog()
        } catch (t: Throwable) {
            Log.e(TAG, "❌ 显示悬浮窗失败", t)
            stage("❌ 窗口挂载失败: ${t.message}")
            isShowing = false
            // 没挂上去的 View 必须丢掉：留着它，下一轮 show 会发现
            // 「tv 非空但 parent 为空」又去重建，每 2 秒抖一次，白折腾
            tv = null
            surfaceTexture = null
        }
    }

    private fun buildTextureView(): TextureView {
        return TextureView(ctx.applicationContext).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    // apply 作用域内 surfaceTexture 会解析到 TextureView 的只读属性，必须显式限定
                    this@RearCameraOverlay.surfaceTexture = st
                    stage("画面缓冲就绪 ${w}x${h}")
                    openCamera()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    // 有帧进来就说明预览真的通了，记时间戳给看门狗
                    lastFrameTs = System.currentTimeMillis()
                    failureReported = false
                    // 通了就把失败计数清零：下次再出问题仍然有完整的重试额度
                    retryCount = 0
                    if (!firstFrameReported) {
                        firstFrameReported = true
                        stage("✅ 已收到画面")
                    }
                }
            }
            // 永远声明非不透明：这条路径实测能出画面，且与 0.99 的 alpha 自洽
            isOpaque = false
            isFocusable = false
            isClickable = false
            clipToOutline = true
        }
    }

    private fun roundOutline(): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (style.mode == MODE_SMALLWINDOW) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
            } else {
                outline.setRect(0, 0, view.width, view.height)
            }
        }
    }

    private fun buildParams(): WindowManager.LayoutParams {
        // 窗口格式固定用 TRANSLUCENT，不随不透明度切换。
        // 曾试过「100% 时换 OPAQUE」，真机仍然黑屏（系统对不透明全屏悬浮窗
        // 另有一套拦截/合成逻辑），而 TRANSLUCENT + alpha<1 是实测唯一稳的组合。
        return if (style.mode == MODE_FULLSCREEN) {
            /*
             * 全屏用真实屏幕像素尺寸，而不是 MATCH_PARENT(-1)。
             *
             * 相机搬到 App 进程后，MATCH_PARENT 的 TYPE_APPLICATION_OVERLAY
             * 在部分 ROM 上不会把 TextureView 布局到非零尺寸 ——
             * 窗口挂上去了，但视图宽高是 0，openCamera 拿到 0×0 直接放弃，
             * 5 秒看门狗再把窗口收掉，表现就是「全屏黑屏、小窗正常」。
             * 用确定的像素尺寸，TextureView 一定能布局出正数尺寸。
             *
             * 尺寸取窗口度量的整屏（含状态栏/导航栏），不用 resources.displayMetrics：
             * 后者是「应用可用区域」，全屏悬浮窗会比屏幕小一圈，画面被拉伸、
             * 边缘留白，看起来像没铺满。
             */
            val (screenW, screenH) = screenSize()
            WindowManager.LayoutParams(
                screenW,
                screenH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        // TextureView 必须跑在硬件加速的窗口里。
                        // 从 Service 直接 addView 的窗口默认**不会**被标记为硬件加速
                        // （那条路径只看这个 flag），表现就是相机开得起来、
                        // 会话也配置成功，但一帧都上不了屏 —— 纯黑。
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        // 允许铺到状态栏/导航栏下面，否则「全屏」会被系统栏挤小一圈
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
        } else {
            val (vGrav, hGrav) = when (style.corner) {
                CORNER_BL -> Gravity.BOTTOM to Gravity.START
                CORNER_TR -> Gravity.TOP to Gravity.END
                CORNER_TL -> Gravity.TOP to Gravity.START
                else -> Gravity.BOTTOM to Gravity.END
            }
            WindowManager.LayoutParams(
                dp(150), dp(260),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        // 同上：TextureView 没有硬件加速就是一整块黑
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = vGrav or hGrav
                // 与第一版一致：四角统一 12dp 边距
                x = dp(12)
                y = dp(12)
            }
        }
    }

    private fun dp(v: Int): Int = Math.round(v * ctx.resources.displayMetrics.density)

    /**
     * 整屏像素尺寸。
     *
     * 优先用 WindowMetrics（API 30+），它给的是窗口系统认可的完整边界；
     * 低版本用 getRealMetrics（含系统栏）。两条路都拿不到正数时退回
     * displayMetrics —— 宁可小一圈，也不能给 LayoutParams 传 0，
     * 0 会让 TextureView 根本不布局，相机直接放弃。
     */
    private fun screenSize(): Pair<Int, Int> {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
            } else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    return dm.widthPixels to dm.heightPixels
                }
            }
        } catch (_: Throwable) {
        }
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * 前台是否相机类应用。
     * 命中时 Hook 端会暂时收起悬浮窗，避免挡住取景框。
     */
    fun isCameraAppInForeground(): Boolean {
        return try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val atm = atmClass.getMethod("getInstance").invoke(null)
            val info = atmClass.getMethod("getFocusedRootTaskInfo").invoke(atm) ?: return false
            val topActivity = info.javaClass.getField("topActivity").get(info) ?: return false
            val pkg = topActivity.javaClass.getField("packageName").get(topActivity) as? String
                ?: return false

            if (CAMERA_PACKAGES.contains(pkg)) return true
            // 部分 ROM 用厂商自有相机包名，退化为包名关键字匹配
            pkg.contains("camera", ignoreCase = true) && !pkg.contains("safewalk", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun teardown() {
        closeCamera()
        tv?.let {
            if (it.parent != null) {
                try { wm.removeView(it) } catch (_: Throwable) {}
            }
        }
        tv = null
        surfaceTexture = null
    }

    /**
     * 释放相机（会话 → 设备 → Surface），并推进代号。
     *
     * 代号一推进，所有还在飞的 StateCallback 都会认出「我已经过期」并自行关掉
     * 手里的设备——这是防止设备泄漏的唯一手段。close() 本身不等待完成，
     * 所以这里同时记下时刻，让下一次 openCamera 先等 HAL 断开。
     */
    private fun closeCamera() {
        cameraGen++
        opening = false
        cancelSessionTimeout()
        session?.let { try { it.close() } catch (_: Throwable) {} }
        session = null
        camera?.let { try { it.close() } catch (_: Throwable) {} }
        camera = null
        previewSurface?.let { try { it.release() } catch (_: Throwable) {} }
        previewSurface = null
        surfaceTexture = null
        lastCloseAt = android.os.SystemClock.uptimeMillis()
    }

    private fun cancelSessionTimeout() {
        sessionTimeout?.let { mainHandler.removeCallbacks(it) }
        sessionTimeout = null
    }

    private fun hasCameraPermission(): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun cancelWatchdog() {
        frameWatchdog?.let { mainHandler.removeCallbacks(it) }
        frameWatchdog = null
    }

    /**
     * 画面看门狗。
     *
     * 「窗口加进去了」不等于「能看到画面」：缺相机权限、相机被占用、Surface 失效，
     * 都会得到一个半透明的黑框。用户分不清「黑屏」和「没反应」，
     * 所以这里给 6 秒宽限：一帧都没有就收起窗口并明确报错。
     */
    private fun armFrameWatchdog() {
        cancelWatchdog()
        val start = System.currentTimeMillis()
        val r = Runnable {
            if (!isShowing) return@Runnable
            if (lastFrameTs >= start) return@Runnable
            // 报错前先把现场参数写进日志：黑屏到底是窗口、相机还是合成的问题，
            // 一次就能分辨（cam/session 都非空却没帧 = 合成侧；为空 = 相机没开起来）
            diag("❌ ${FRAME_TIMEOUT_MS}ms 无画面 | mode=${style.mode} opacity=${style.opacity} " +
                    "cam=${camera != null} session=${session != null} opening=$opening " +
                    "gen=$cameraGen retry=$retryCount " +
                    "surface=${surfaceTexture != null} view=${tv?.width}x${tv?.height}")
            reportFailure(if (hasCameraPermission()) "相机没有返回画面" else "应用没有相机权限")
            hide()
        }
        frameWatchdog = r
        mainHandler.postDelayed(r, FRAME_TIMEOUT_MS)
    }

    private fun reportFailure(reason: String) {
        if (failureReported) return
        failureReported = true
        diag("❌ 预览失败: $reason")
        stage("❌ $reason（${FRAME_TIMEOUT_MS / 1000} 秒内没有收到画面）")
        try { onFailure?.invoke(reason) } catch (_: Throwable) {}
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            if (!isShowing) return
            // 已经在开 / 已经开着，都不要再发起一次。
            //
            // 以前只判断 camera != null，挡不住「第一次 open 的回调还没回来」这段
            // 异步窗口：onSurfaceTextureAvailable 在部分 ROM 上会连回两次，
            // 两次都看到 camera 为 null，于是发起两次 cm.openCamera，
            // 两个设备句柄都算打开成功，后一个覆盖字段，前一个永久泄漏。
            if (opening || camera != null) return

            // 刚 close 过：HAL 还没断开，等冷却结束再来
            if (lastCloseAt != 0L) {
                val since = android.os.SystemClock.uptimeMillis() - lastCloseAt
                if (since < REOPEN_GAP_MS) {
                    val wait = REOPEN_GAP_MS - since
                    diag("相机刚释放 ${since}ms，${wait}ms 后重开")
                    mainHandler.removeCallbacks(reopenTask)
                    mainHandler.postDelayed(reopenTask, wait)
                    return
                }
            }

            // 权限检查必须内联，lint 的 MissingPermission 只认同方法内的显式判断
            if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                diag("❌ 无相机权限，跳过预览")
                // 相机开不了就不要再留一个黑框挂在屏幕上
                stage("❌ 应用没有相机权限")
                reportFailure("应用没有相机权限")
                mainHandler.post { hide() }
                return
            }
            val view = tv ?: return
            val st = view.surfaceTexture ?: return
            if (view.width <= 0 || view.height <= 0) {
                diag("❌ 视图尺寸为 0，跳过本次开相机")
                // Surface 回调比布局早的 ROM 上会先拿到 0×0。
                // 只 post 一次经常还是 0（post 跑在同一帧的下一轮消息里，
                // 布局可能还没完成），这里退避式重试几次，总时长控制在 0.6 秒内，
                // 远小于看门狗的 6 秒。
                if (layoutRetry < 3) {
                    layoutRetry++
                    view.postDelayed({ openCamera() }, 200L)
                }
                return
            }
            layoutRetry = 0
            st.setDefaultBufferSize(view.width, view.height)
            val surface = Surface(st)
            previewSurface = surface
            val id = backCameraId()
            if (id == null) {
                Log.e(TAG, "无后置摄像头")
                stage("❌ 找不到后置摄像头")
                // 提前 return 也要把刚建的 Surface 放掉，否则每调一次少一个 buffer
                try { surface.release() } catch (_: Throwable) {}
                previewSurface = null
                return
            }
            // 捕获发起时的代号：回调回来要是对不上，说明这次请求已经过期
            val gen = cameraGen
            opening = true
            Log.i(TAG, "打开后置摄像头: $id gen=$gen")
            stage("正在打开相机 id=$id")
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != cameraGen) {
                        // 这是上一轮的设备：close 已经发起，但握手先完成了。
                        // 必须自己关掉，否则它永远占着摄像头——新会话配置不出来、
                        // 手电也一直是 CAMERA_IN_USE。
                        try { d.close() } catch (_: Throwable) {}
                        diag("⚠️ 丢弃过期相机句柄 gen=$gen 当前=$cameraGen")
                        return
                    }
                    opening = false
                    // 打开是异步的：如果这期间用户已经收起悬浮窗，
                    // 直接持有相机会导致摄像头常亮（耗电 + 隐私指示灯不灭）
                    if (!isShowing || tv == null) {
                        try { d.close() } catch (_: Throwable) {}
                        lastCloseAt = android.os.SystemClock.uptimeMillis()
                        diag("⚠️ 相机打开时视图已销毁，立即释放")
                        stage("⚠️ 相机打开时窗口已关闭，立即释放")
                        return
                    }
                    if (camera != null) {
                        try { d.close() } catch (_: Throwable) {}
                        diag("⚠️ 已有相机设备，丢弃新句柄 gen=$gen")
                        return
                    }
                    stage("相机已打开")
                    camera = d
                    try {
                        val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(surface)
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        // 用 SessionConfiguration(Executor) 替代已废弃的
                        // createCaptureSession(List<Surface>, StateCallback, Handler)
                        val outputConfig = OutputConfiguration(surface)
                        val sessionConfig = SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(outputConfig),
                            Executor { mainHandler.post(it) },
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(cs: CameraCaptureSession) {
                                    if (gen != cameraGen || !isShowing) {
                                        try { cs.close() } catch (_: Throwable) {}
                                        return
                                    }
                                    cancelSessionTimeout()
                                    session = cs
                                    stage("预览流已启动")
                                    try {
                                        cs.setRepeatingRequest(builder.build(), null, mainHandler)
                                    } catch (t: Throwable) {
                                        diag("❌ 预览请求失败: ${t.message}")
                                    }
                                }

                                override fun onConfigureFailed(cs: CameraCaptureSession) {
                                    if (gen != cameraGen) return
                                    cancelSessionTimeout()
                                    Log.e(TAG, "会话配置失败")
                                    stage("❌ 相机会话配置失败")
                                    recoverCamera(gen, "会话配置失败")
                                }
                            }
                        )
                        d.createCaptureSession(sessionConfig)

                        // 会话配置的兜底：有的机型在相机被占用时 onConfigured 和
                        // onConfigureFailed 一个都不回调，就这么静默挂着。
                        // 不主动超时的话，只能等看门狗报「没有画面」，什么线索都没有。
                        cancelSessionTimeout()
                        val to = Runnable {
                            sessionTimeout = null
                            if (gen != cameraGen || session != null) return@Runnable
                            diag("❌ 会话 ${SESSION_TIMEOUT_MS}ms 未就绪（相机可能被别的应用占用）")
                            stage("❌ 相机会话超时")
                            recoverCamera(gen, "会话配置超时")
                        }
                        sessionTimeout = to
                        mainHandler.postDelayed(to, SESSION_TIMEOUT_MS)
                    } catch (t: Throwable) {
                        Log.e(TAG, "启动预览失败", t)
                        diag("❌ 启动预览失败: ${t.message}")
                        recoverCamera(gen, "启动预览异常")
                    }
                }

                override fun onDisconnected(d: CameraDevice) {
                    try { d.close() } catch (_: Throwable) {}
                    if (gen != cameraGen) return
                    opening = false
                    camera = null
                    session = null
                    cancelSessionTimeout()
                    diag("⚠️ 相机被其他应用抢占")
                }

                override fun onError(d: CameraDevice, error: Int) {
                    try { d.close() } catch (_: Throwable) {}
                    if (gen != cameraGen) return
                    opening = false
                    camera = null
                    session = null
                    cancelSessionTimeout()
                    Log.e(TAG, "相机错误: $error")
                    diag("❌ 相机错误: $error")
                    recoverCamera(gen, "相机错误 $error")
                }
            }, mainHandler)
        } catch (t: Throwable) {
            opening = false
            Log.e(TAG, "打开相机失败", t)
            diag("❌ 打开相机异常: ${t.message}")
            // openCamera 抛异常时 Surface 已经建好了，不释放就永远挂在那里
            try { previewSurface?.release() } catch (_: Throwable) {}
            previewSurface = null
            lastCloseAt = android.os.SystemClock.uptimeMillis()
        }
    }

    /**
     * 相机链路出错后的统一恢复：清干净、重新计时、隔一小会儿再开。
     *
     * 限制重试次数（[MAX_CAMERA_RETRY]），避免「相机被别的应用占着」时
     * 每 3 秒开一次、无限循环烧电。真正收到画面后计数清零。
     */
    private fun recoverCamera(gen: Int, why: String) {
        if (gen != cameraGen) return
        if (!isShowing) {
            closeCamera()
            return
        }
        closeCamera()
        if (retryCount >= MAX_CAMERA_RETRY) {
            retryCount = 0
            diag("❌ $why，已重试 $MAX_CAMERA_RETRY 次仍失败，放弃")
            reportFailure("相机没有返回画面")
            hide()
            return
        }
        retryCount++
        diag("↻ $why，清理后重试（第 $retryCount/$MAX_CAMERA_RETRY 次）")
        stage("↻ $why，正在重试")
        // 新一轮要有自己的宽限窗口，不能沿用上一轮的看门狗计时
        armFrameWatchdog()
        mainHandler.removeCallbacks(reopenTask)
        mainHandler.postDelayed(reopenTask, REOPEN_GAP_MS)
    }

    /** 冷却结束后的重开入口。单独抽成字段，方便 removeCallbacks 去重 */
    private val reopenTask = Runnable { if (isShowing) openCamera() }

    private fun backCameraId(): String? {
        try {
            for (id in cm.cameraIdList) {
                val cc = cm.getCameraCharacteristics(id)
                if (cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            return null
        } catch (_: Throwable) {
            return null
        }
    }
}
