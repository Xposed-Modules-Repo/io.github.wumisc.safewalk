package io.github.wumisc.safewalk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
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
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.jvm.JvmOverloads

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

        /**
         * 不可恢复失败（目前只剩「应用没有相机权限」）后的「静默期」。
         *
         * 这类失败重试不会自己变好，但 Hook 每 2s 的 updateOverlay 仍会不断
         * 发 ACTION_HOOK_SHOW，App 每次都 show → 开相机失败 → hide → 回执 false，
         * 于是日志里出现一串「false → true → false」乒乓，既反复开相机耗电、又刷屏。
         * 静默期内 show() 直接拒绝拉起；用户主动重试或授权后解除。
         *
         * 注意：系统/策略禁用相机（锁屏、灭屏、后台省电）是**暂时**不可用，
         * 已改由 handlePolicyDisabled 静默处理，绝不能进这个静默期。
         */
        private const val HARD_FAILURE_BACKOFF_MS = 5 * 60 * 1000L
    }

    /**
     * 当前生效的展示参数。
     * [enhanceContrast] 纳入 Style，是为了让「只切换增强对比度、其余参数不变」
     * 也能走进 apply() 刷新 HUD 层（否则会被 target==style 的去重直接吞掉）。
     */
    private data class Style(
        val mode: String, val opacity: Int, val corner: String,
        val enhanceContrast: Boolean,
        /** 【对比度强度进度条】0~100，驱动 HUD 描边/阴影/衬底强弱 */
        val contrastLevel: Int,
        /** 【小窗联动闪光灯】在预览会话内开 FLASH_MODE_TORCH，随小窗显示/收起开关 */
        val torchWithPreview: Boolean
    )

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

    /**
     * 【新增对比度 UI 模块】窗口根容器。
     *
     * 原来直接把 TextureView 挂到 WindowManager（addView(tv)）。为了让「文字层」
     * 能叠在画面上、又不受透明度影响，改成挂一个 FrameLayout 容器：
     *   · 子层 0 = [tv] 相机画面，继续吃 [style.opacity] 换算出的 alpha；
     *   · 子层 1 = [hudView] 状态文字层，alpha 恒为 1，带描边/阴影/深色衬底。
     * 只用一个窗口，不新增 overlay 数量，避免额外触发 HyperOS 的悬浮窗后台限制。
     */
    private var rootView: FrameLayout? = null
    /** 增强对比度开启时显示的 HUD 文字层；关闭时置 GONE，不移除（避免频繁重建） */
    private var hudView: HudTextView? = null

    private var isShowing = false
    private var style = Style(MODE_FULLSCREEN, 50, CORNER_BR, false, 50, false)

    /**
     * 【小窗联动闪光灯】后置相机是否带闪光灯。
     * 由 openCamera() 读 CameraCharacteristics.FLASH_INFO_AVAILABLE 得到；
     * 不带闪光灯的机型不能下发 FLASH_MODE_TORCH（否则整条 preview request 会被 HAL 拒绝，
     * 表现为预览黑屏），所以这里做能力探测 + 自动降级。
     */
    private var flashAvailable = false

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

    /**
     * 不可恢复失败的静默截止时刻。0 表示未处于静默期。
     * 由 [reportHardFailure] 设置，[show] 检查，[clearHardFailureBackoff] 解除。
     */
    @Volatile
    private var hardFailureBackoffUntil = 0L

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
     * @param enhanceContrast 【新增】增强对比度：开启后在画面之上叠加带描边/阴影/
     *        深色衬底的 HUD 文字层，且该层**不受 opacity 影响**。
     * @param torchWithPreview 【小窗联动闪光灯】开启时在预览会话的 CaptureRequest 里
     *        常亮闪光灯；小窗收起 → 会话关闭 → 灯自动灭。
     * @param contrastLevel 【对比度强度进度条】0~100，只在 [enhanceContrast] 开启时生效，
     *        控制 HUD 文字层描边宽度 / 阴影半径 / 深色衬底不透明度。画面层透明度
     *        始终只由 [opacity] 决定，两者互不干扰。
     * @JvmOverloads 保证 Java 侧旧的三参/四参/五参调用继续可用。
     */
    @JvmOverloads
    fun show(
        mode: String = MODE_FULLSCREEN,
        opacity: Int = 50,
        corner: String = CORNER_BR,
        enhanceContrast: Boolean = false,
        torchWithPreview: Boolean = false,
        contrastLevel: Int = 50
    ) {
        // 硬失败静默期：Hook 每 2s 一次 ACTION_HOOK_SHOW 会不断把我们叫醒，
        // 但「无相机权限」不会自己变好。静默期内直接拒绝拉起，
        // 让窗口保持「未显示」→ 回执稳定为 false，掐断 false→true→false 乒乓。
        // 系统策略禁用是暂时状态，不走这里（见 handlePolicyDisabled）。
        if (System.currentTimeMillis() < hardFailureBackoffUntil) {
            // 兜底：静默期内万一有残留窗口，收掉它，保证 showing 恒为 false
            if (isShowing) hide()
            return
        }
        val target = Style(
            if (mode == MODE_SMALLWINDOW) MODE_SMALLWINDOW else MODE_FULLSCREEN,
            opacity.coerceIn(10, 100),
            corner,
            enhanceContrast,
            contrastLevel.coerceIn(0, 100),
            torchWithPreview
        )
        // 【原地快刷】只改「对比度强度 / 闪光灯联动」这类不需要重建视图的参数时，
        // 不重建窗口、不重开相机：HUD 层改样式只要 invalidate，闪光灯只要重新下发
        // 一次 repeating request。否则拖一下进度条就「画面黑一下 + 重新握手 400ms」。
        // torchChanged / contrastChanged 必须在 style 被覆盖前算好。
        val torchChanged = target.torchWithPreview != style.torchWithPreview
        val contrastChanged = target.contrastLevel != style.contrastLevel
        if (isShowing && rootView?.parent != null
            && target.mode == style.mode
            && target.opacity == style.opacity
            && target.corner == style.corner
            && target.enhanceContrast == style.enhanceContrast
            && (contrastChanged || torchChanged)
        ) {
            style = target
            applyHudStyle()
            if (torchChanged) refreshTorchInSession()
            return
        }
        // 除了参数未变，还要确认视图真的还挂在 WindowManager 上；
        // 被系统回收（切用户/内存压力）后 parent 会变 null，此时必须重建。
        // 【新增对比度 UI】容器化后窗口根是 rootView，判它的 parent 而不是
        // tv 的（tv 是容器子层，parent 恒为容器，非空，用它判断会永远误判「还挂着」）。
        if (isShowing && target == style && rootView?.parent != null) {
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
        val viewDetached = rootView != null && rootView?.parent == null
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
        // 【新增对比度 UI】窗口根是容器，removeView 要移除容器而不是 tv。
        rootView?.let {
            if (it.parent != null) {
                try { wm.removeView(it) } catch (_: Throwable) {}
            }
        }
        rootView = null
        hudView = null
        tv = null
        surfaceTexture = null
    }

    private fun apply() {
        // 【新增对比度 UI】窗口根从「单个 TextureView」换成「FrameLayout 容器」。
        // 容器里两层：
        //   [0] tv       相机画面层 —— 继续吃透明度 alpha（唯一受 opacity 影响的层）
        //   [1] hudView  状态文字层 —— alpha 恒 1，带描边/阴影/深色衬底
        // 透明度只作用于画面层，文字层永远清晰，正是「看得清路况 + 看得清文字」的关键。
        if (tv == null) tv = buildTextureView()
        val texture = tv!!
        if (rootView == null) {
            // 防御：TextureView 若还残留旧父容器（ROM 回收窗口后对象没清干净），
            // 直接 addView 会抛 "already has a parent"。先安全摘除，再挂新容器。
            (texture.parent as? ViewGroup)?.removeView(texture)
            rootView = FrameLayout(ctx.applicationContext).apply {
                // 画面层铺满容器；MATCH_PARENT 但窗口尺寸由 buildParams 用真实像素给定，
                // 所以 TextureView 一定能布局出正数尺寸（沿用原全屏不黑屏的结论）。
                addView(texture, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))
                // HUD 文字层：底部居中，带描边/阴影/半透明深色衬底
                hudView = buildHudView()
                val hudLp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = dp(16) }
                addView(hudView, hudLp)
            }
        }

        val root = rootView!!
        // 圆角裁剪放到容器上：小窗模式下把画面和 HUD 一起裁成圆角，不会露方角。
        root.outlineProvider = roundOutline()
        root.clipToOutline = true
        root.invalidateOutline()

        // 透明度只作用于画面层（第一版实测可用的做法，不引入变量）。
        //
        // 上限刻意压在 0.99，永远不给 1.0：
        // 真机实测「全屏 + alpha=1.0」画面出不来，alpha<1 正常。
        // 全屏铺满且像素 alpha 全是 255 时，系统会把这个 layer 判成不透明，
        // 与窗口声明的 TRANSLUCENT 冲突，合成路径选错 → 黑屏；
        // alpha<1 时明确需要 GPU 混合，走的是正常路径。
        // 99% 与 100% 肉眼无差，但能稳定出画面。
        // 注意：只对 texture 设 alpha，root/hudView 都不设 —— 文字层永不被调淡。
        val renderAlpha = minOf(style.opacity / 100f, 0.99f)
        texture.alpha = renderAlpha

        // 增强对比度：开关决定 HUD 层可见性，强度决定描边/阴影/衬底，
        // 都只作用于文字层，不重建视图、不碰相机、不改变画面层 alpha。
        applyHudStyle()

        val lp = buildParams()
        try {
            if (root.parent == null) {
                wm.addView(root, lp)
                stage("窗口已挂载: ${style.mode} ${lp.width}x${lp.height} alpha=$renderAlpha contrast=${style.enhanceContrast}/${style.contrastLevel}% torch=${style.torchWithPreview}")
            } else {
                wm.updateViewLayout(root, lp)
                stage("窗口已更新: ${style.mode} ${lp.width}x${lp.height} alpha=$renderAlpha contrast=${style.enhanceContrast}/${style.contrastLevel}% torch=${style.torchWithPreview}")
            }
            armFrameWatchdog()
        } catch (t: Throwable) {
            Log.e(TAG, "❌ 显示悬浮窗失败", t)
            stage("❌ 窗口挂载失败: ${t.message}")
            isShowing = false
            // 没挂上去的 View 必须丢掉：留着它，下一轮 show 会发现
            // 「tv 非空但 parent 为空」又去重建，每 2 秒抖一次，白折腾
            rootView = null
            tv = null
            hudView = null
            surfaceTexture = null
        }
    }

    /**
     * 【对比度强度进度条】可动态调强度的 HUD 状态文字层。
     *
     * 覆写 onDraw 画「黑色外描边 + 白色实心字」，再叠一层半透明深色圆角衬底
     * 和阴影。强度 [level] 0~100 线性映射到：
     *   · 描边宽度 1dp → 5dp
     *   · 阴影半径 1dp → 4dp
     *   · 衬底不透明度 约 16% → 86%
     * 强度调低时更接近原始画面、少遮挡路况；调高时文字从花哨背景里被拎出来。
     * 无论怎么调，画面层 alpha 只由 style.opacity 决定，两者互不干扰。
     */
    // 这块文字只在 WindowManager 悬浮层里自绘，不在 AppCompat 布局树中，
    // 换成 AppCompatTextView 没有意义（也不可用，它要求 Theme.AppCompat 上下文）
    @SuppressLint("AppCompatCustomView")
    private inner class HudTextView(context: Context) : TextView(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
        }
        private val bg = GradientDrawable()

        /** 0~100；写入时立即重算描边/阴影/衬底并 invalidate */
        var level: Int = 50
            set(value) {
                field = value.coerceIn(0, 100)
                applyLevel()
            }

        init {
            background = bg
            setTextColor(Color.WHITE)
            textSize = 13f
            isClickable = false
            isFocusable = false
            setPadding(dp(14), dp(8), dp(14), dp(8))
            text = "路况预览中 · 增强对比度"
            applyLevel()
        }

        private fun applyLevel() {
            val t = level / 100f
            strokePaint.strokeWidth = dp(1).toFloat() + dp(4).toFloat() * t
            bg.setColor(Color.argb((40 + 180 * t).toInt(), 0, 0, 0))
            bg.cornerRadius = dp(12).toFloat()
            setShadowLayer(dp(1).toFloat() + dp(3).toFloat() * t, 0f, dp(1).toFloat(), Color.BLACK)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            // 第一遍：黑色描边形成外轮廓；第二遍交给 super 画白色实心字
            strokePaint.textSize = paint.textSize
            strokePaint.typeface = paint.typeface
            strokePaint.textAlign = Paint.Align.CENTER
            canvas.drawText(text.toString(), width / 2f, baseline.toFloat(), strokePaint)
            super.onDraw(canvas)
        }
    }

    private fun buildHudView(): HudTextView = HudTextView(ctx.applicationContext)

    /**
     * 【对比度强度进度条】把当前 style 应用到 HUD 文字层：开关决定可见性，
     * 强度决定描边/阴影/衬底。可反复调用，不重建 View、不碰相机。
     */
    private fun applyHudStyle() {
        val hud = hudView ?: return
        hud.visibility = if (style.enhanceContrast) View.VISIBLE else View.GONE
        hud.level = style.contrastLevel
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
                    // 相机能出画面 = 之前若因策略禁用进过静默期，现在已恢复，解除它
                    hardFailureBackoffUntil = 0L
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

    private fun teardown() {
        closeCamera()
        // 【新增对比度 UI】窗口根是容器，removeView 移除容器。
        rootView?.let {
            if (it.parent != null) {
                try { wm.removeView(it) } catch (_: Throwable) {}
            }
        }
        rootView = null
        hudView = null
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
            // 顺序很重要：必须先 hide() 再 reportFailure()。
            //
            // reportFailure 会回调外层（WalkPreviewService）去 reportPreviewState()，
            // 而那个回执读的是 overlay.getShowing()。原来是「先报告后隐藏」，
            // 于是回执发出去的是 showing=true，紧接着窗口才真正收起、却再也没有回执——
            // Hook 侧的 lastSentShow 就永久卡在 true，之后每一条「该显示了」都被
            // 去重吞掉。表现就是：预览黑过一次以后再也不自动回来，必须手动关开开关。
            val reason = if (hasCameraPermission()) "相机没有返回画面" else "应用没有相机权限"
            hide()
            reportFailure(reason)
        }
        frameWatchdog = r
        mainHandler.postDelayed(r, FRAME_TIMEOUT_MS)
    }

    /**
     * 【BUG 修复 · 锁屏/后台策略禁用不再误报】
     *
     * HyperOS / MIUI 在锁屏、灭屏、省电或后台策略下会临时回收相机：
     * openCamera 同步抛 CAMERA_DISABLED，或已打开的会话回调
     * ERROR_CAMERA_DISABLED。这是「现在不让用」，不是「相机坏了」——
     * 亮屏解锁后立刻恢复。用户锁屏截图里的那条通知正是这么来的：
     * 日志同一秒还打着 ✅ 已收到画面，下一秒就弹「后视画面打不开 /
     * 相机被系统或策略禁用」，还附带一句误导的「6 秒内没有收到画面」。
     *
     * 旧逻辑把它当不可恢复故障：hide + 弹常驻通知 + 5 分钟硬静默，
     * 于是锁屏时反复弹、解锁后还要等满 5 分钟才恢复，等于功能被自己锁死。
     *
     * 现在：静默收起窗口、留一行诊断、不通知、不封禁；
     * WalkPreviewService 的亮屏解锁闸门会在解锁后重新拉起预览。
     */
    private fun handlePolicyDisabled(where: String) {
        diag("⏸ 系统暂时禁用相机（$where，多为锁屏/后台/省电策略）→ 静默收起，解锁后自动恢复")
        stage("⏸ 系统暂时禁用相机（锁屏/后台策略），等待亮屏解锁后自动恢复")
        // 关键：绝不调 reportFailure —— 那会触发 onFailure 弹通知并写进 5 分钟封禁。
        // 也不设置 hardFailureBackoffUntil，解锁后 Hook 的下一次放行即可重开。
        cancelWatchdog()
        hide()
    }

    private fun reportFailure(reason: String) {
        // 只有「应用没有相机权限」是不可恢复的，才进入长静默期。
        // 「相机没有返回画面」可能只是偶发黑屏/被占用，属于可恢复，不能一封就是 5 分钟。
        // 系统/策略禁用（锁屏、灭屏、后台省电）是暂时不可用，已由
        // handlePolicyDisabled 静默处理，不会再走到这里。
        if (reason == "应用没有相机权限") {
            hardFailureBackoffUntil = System.currentTimeMillis() + HARD_FAILURE_BACKOFF_MS
            diag("⏸ 检测到不可恢复的相机失败（$reason），${HARD_FAILURE_BACKOFF_MS / 60_000} 分钟内暂停自动拉起；用户主动预览可提前解除")
        }
        if (failureReported) return
        failureReported = true
        diag("❌ 预览失败: $reason")
        // 只有「超时没等到帧」才配这句说明。权限缺失等其它原因硬套
        // 「6 秒内没有收到画面」会误导排查（日志里明明已有 ✅ 已收到画面）。
        val suffix = if (reason == "相机没有返回画面")
            "（${FRAME_TIMEOUT_MS / 1000} 秒内没有收到画面）" else ""
        stage("❌ $reason$suffix")
        try { onFailure?.invoke(reason) } catch (_: Throwable) {}
    }

    /**
     * 解除硬失败静默期。供 App 侧「用户主动点击预览 / 重新授权后重试」时调用，
     * 让用户在系统里放开相机后能立刻重试，不必等满 5 分钟。
     */
    fun clearHardFailureBackoff() {
        hardFailureBackoffUntil = 0L
        failureReported = false
    }

    /**
     * 【BUG 修复 · 小窗联动闪光灯】构建预览 CaptureRequest。
     *
     * 用户澄清：「夜间手电也就是闪光灯是跟角落小窗一起打开，联动的是角落小窗」。
     * 闪光灯必须开在**预览会话内**（FLASH_MODE_TORCH），而不是
     * CameraManager.setTorchMode()：后者会和预览抢同一颗后置摄像头并抛
     * CAMERA_IN_USE，两者物理上无法同时存在。会话关闭时灯自然熄灭，正好实现
     * 「小窗显示即亮、收起即灭」，也不需要新增任何跨进程广播或额外的手电开关链路。
     *
     * 注意 FLASH_MODE_TORCH 要求 AE 不在 AUTO_FLASH 系列，这里显式固定
     * CONTROL_AE_MODE_ON，避免部分 HAL 拒绝整条 request 导致预览黑屏。
     */
    private fun buildPreviewRequest(d: CameraDevice, surface: Surface): CaptureRequest {
        val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (style.torchWithPreview && flashAvailable) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        }
        return builder.build()
    }

    /**
     * 联动开关切换时：窗口与相机会话都还在跑，不重建视图、不重开相机，
     * 只重新下发一次 repeating request 即可开/关闪光灯（无黑屏、无 400ms 握手）。
     */
    private fun refreshTorchInSession() {
        val d = camera
        val cs = session
        val surface = previewSurface
        if (d == null || cs == null || surface == null) {
            diag("[torch:overlay] 相机会话未就绪，联动开关将在下次打开预览时生效")
            return
        }
        try {
            val torchOn = style.torchWithPreview && flashAvailable
            cs.setRepeatingRequest(buildPreviewRequest(d, surface), null, mainHandler)
            diag("[torch:overlay] 预览会话内闪光灯 → ${if (torchOn) "常亮" else "熄灭"}"
                    + "（flashAvailable=$flashAvailable）")
        } catch (t: Throwable) {
            // 相机正处于重连窗口时 createCaptureRequest 会抛异常：只记日志，
            // 下一轮 onConfigured 会用最新 style 重新构建，功能不会丢。
            diag("❌ 预览会话内闪光灯切换失败: ${t.message}")
        }
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
            // 【小窗联动闪光灯】能力探测：不带闪光灯的机型绝不能下发
            // FLASH_MODE_TORCH（HAL 会拒掉整条 preview request → 预览黑屏），自动降级。
            flashAvailable = try {
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } catch (_: Throwable) {
                false
            }
            if (style.torchWithPreview && !flashAvailable) {
                diag("⚠️ 本机后置相机无闪光灯，小窗联动闪光灯自动降级（预览照常显示）")
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
                                        // 【小窗联动闪光灯】request 统一由 buildPreviewRequest
                                        // 生成，是否带 FLASH_MODE_TORCH 由当前 style 决定。
                                        cs.setRepeatingRequest(
                                            buildPreviewRequest(d, surface), null, mainHandler
                                        )
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
                    if (error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED) {
                        // 【BUG 修复 · 锁屏误报】见 handlePolicyDisabled：
                        // 这不是故障，是系统现在不允许用相机，静默等解锁即可。
                        handlePolicyDisabled("onError($error)")
                        return
                    }
                    recoverCamera(gen, "相机错误 $error")
                }
            }, mainHandler)
        } catch (e: CameraAccessException) {
            // cm.openCamera 在相机被系统/策略禁用时会同步抛 CAMERA_DISABLED
            // （企业策略、屏幕使用时间、家长控制等）。这种失败重试也没用，
            // 直接报出准确原因，不要走 recoverCamera 空转几轮再弹误导信息。
            opening = false
            Log.e(TAG, "打开相机被拒绝 reason=${e.reason}", e)
            diag("❌ 打开相机被系统拒绝: reason=${e.reason}")
            // openCamera 抛异常时 Surface 已经建好了，不释放就永远挂在那里
            try { previewSurface?.release() } catch (_: Throwable) {}
            previewSurface = null
            lastCloseAt = android.os.SystemClock.uptimeMillis()
            if (e.reason == CameraAccessException.CAMERA_DISABLED) {
                handlePolicyDisabled("openCamera(reason=${e.reason})")
            } else {
                // 被占用（CAMERA_IN_USE / MAX_CAMERAS_IN_USE）等仍可重试的错误
                recoverCamera(cameraGen, "打开相机被拒(${e.reason})")
            }
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
            // 同 armFrameWatchdog：先隐藏再报告，回执才不会把 showing=true 发出去
            hide()
            reportFailure("相机没有返回画面")
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
