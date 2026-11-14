package io.github.wumisc.safewalk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
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
import android.util.TypedValue
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

        /** 画面增强实际生效路径（仅用于日志与诊断，不参与判定） */
        private const val PATH_AGSL = "AGSL"
        private const val PATH_MATRIX = "COLOR_MATRIX"

        const val CORNER_BR = "bottom_right"
        const val CORNER_BL = "bottom_left"
        const val CORNER_TR = "top_right"
        const val CORNER_TL = "top_left"

        /** 角落小窗宽:高 = 150:260。所有尺寸都按这个比例换算，避免画面被拉变形 */
        const val SMALL_ASPECT = 260f / 150f

        /** 小窗宽度取值范围，与设置页滑块保持一致 */
        const val SMALL_WIDTH_MIN_DP = 100
        const val SMALL_WIDTH_MAX_DP = 200
        const val SMALL_WIDTH_DEFAULT_DP = 150

        /** 小窗边距取值范围 */
        const val SMALL_MARGIN_MIN_DP = 0
        const val SMALL_MARGIN_MAX_DP = 40
        const val SMALL_MARGIN_DEFAULT_DP = 12

        /** HUD 字号取值范围 */
        const val HUD_TEXT_SIZE_MIN_SP = 11
        const val HUD_TEXT_SIZE_MAX_SP = 20
        const val HUD_TEXT_SIZE_DEFAULT_SP = 13

        /** 取景范围 0~100：见 [Style.framingPct] */
        const val FRAMING_MIN_PCT = 0
        const val FRAMING_MAX_PCT = 100
        const val FRAMING_DEFAULT_PCT = 0

        /** 小窗描边 0~4dp：0 = 不画 */
        const val BORDER_MIN_DP = 0
        const val BORDER_MAX_DP = 4
        const val BORDER_DEFAULT_DP = 0

        /** 画面增强三参数取值范围：见 [Style.screenContrast] 等 */
        const val SCREEN_CONTRAST_DEFAULT = 45
        const val SCREEN_SHADOW_DEFAULT = 40
        const val SCREEN_SATURATION_DEFAULT = 50

        /**
         * 【5.1.0 画面对比度】AGSL 运行时着色器。
         *
         * 为什么不用 ColorMatrix：ColorMatrix 只能做**线性**变换
         * （`out = s * in + o`），也就是整体乘一个系数再加一个偏移。
         * 线性拉对比度有两个副作用同时出现：
         *  · 中间调一起被推亮/推暗，画面整体发灰或发白；
         *  · 暗部被压得更暗，路边台阶这种「本来就黑」的东西直接消失 ——
         *    而这正是用户最需要看清的部分。
         *
         * 所以这里走 S 曲线（以 0.5 为支点的线性拉伸 + 暗部回抬）：
         *  · 亮的地方继续亮，暗的地方先被拉开再被提回来一点；
         *  · 台阶、路沿、坑洼的边缘因此有了明暗差，轮廓才看得见。
         *
         * AGSL 从 Android 13（API 33）起可用，本应用 minSdk 就是 33，
         * 不需要版本分支；万一某个 ROM 的实现有坑，[applyScreenEnhance]
         * 会退到 ColorMatrix 并在日志里说明。
         */
        private const val ENHANCE_AGSL = """
            uniform shader content;
            uniform float contrast;   // 0..1  S 曲线增益
            uniform float lift;       // 0..1  暗部回抬
            uniform float sat;        // -1..1 饱和度偏移

            half4 main(float2 xy) {
                half4 src = content.eval(xy);
                half3 c = src.rgb;

                // ① 以 0.5 为支点拉伸：k 最大 1.2 倍，够明显但不会一开就成黑白照
                half3 s = half3(0.5) + (c - half3(0.5)) * half(1.0 + contrast * 1.2);

                // ② 暗部回抬：权重在中灰处最大、纯黑纯白处为 0，
                //    所以高光不会被压平（那是「发白」的来源），暗部也不会被抬成灰雾
                half3 w = half3(1.0) - abs(s * half3(2.0) - half3(1.0));
                s = s + (half3(0.5) - s) * w * half(lift * 0.45);

                // ③ 饱和度：夜间噪点在灰度里更不显眼，降饱和等于免费降噪
                half l = dot(s, half3(0.299, 0.587, 0.114));
                s = mix(half3(l), s, half(1.0 + sat));

                return half4(clamp(s, half3(0.0), half3(1.0)), src.a);
            }
        """

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
        val torchWithPreview: Boolean,
        /**
         * 【4.9.0 小窗自定义】HUD 文案。null 或空白 = 不显示文字层。
         *
         * 与 [enhanceContrast] 的关系从「绑定」改成「依赖」：对比度只决定
         * **文字怎么画**，文字内容由本字段决定。没有文字时对比度开关不产生任何视觉。
         */
        val hudText: String?,
        /** HUD 字号 sp。只在 [hudText] 非空时生效 */
        val hudTextSizeSp: Int,
        /** 角落小窗宽度 dp；高度按 [SMALL_ASPECT] 换算 */
        val smallWidthDp: Int,
        /** 角落小窗边距 dp */
        val smallMarginDp: Int,
        /**
         * 【5.0.0 取景范围】0~100。
         *
         * 0 = 完整画面（默认）；100 = 只保留画面下半部分，等比放大 2 倍铺满。
         * 越往后路面占比越大、能看清更近的障碍，代价是横向视野变窄。
         */
        val framingPct: Int,
        /** 【5.0.0 小窗描边】宽度 dp，0 = 不描边。亮背景下靠它分出小窗边界 */
        val borderDp: Int,
        /** 【5.0.0 低光增强】预览请求里下发正向曝光补偿，夜间画面不发黑 */
        val lowLight: Boolean,
        /**
         * 【5.1.0 画面增强】总开关。开启后对**画面像素**做处理，不是给文字加描边。
         *
         * 这是用户明确纠偏的一点：上一版把「增强对比度」做成了给 HUD 文字加黑边黑底，
         * 文字确实更清楚了，可路面上的台阶、路沿、积水反光和柏油仍然是糊成一片 ——
         * 那不是「看得清路」，只是「看得清那行字」。
         */
        val screenEnhance: Boolean,
        /** 【5.1.0】画面对比度 0~100（S 曲线增益） */
        val screenContrast: Int,
        /** 【5.1.0】暗部提亮 0~100（只抬阴影，不压高光） */
        val screenShadowLift: Int,
        /** 【5.1.0】饱和度 0~100，50 = 不变 */
        val screenSaturation: Int,
        /**
         * 【5.3.0 画面方向】自动 / 固定竖屏 / 翻转 180°。
         *
         * 为什么这个参数必须由外部传进来、而不是在这里读配置：本类运行在
         * SystemUI 进程，读不到 App 的私有 SharedPreferences。所有用户设置
         * 都是 WalkPreviewService 读完再当参数传下来的，这里保持同一条路。
         */
        val rotationMode: String
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

    /**
     * 【5.1.0 画面增强】色矩阵兜底路径用的 Paint。
     *
     * 只在 AGSL 不可用时才被 `setLayerType(LAYER_TYPE_HARDWARE, enhancePaint)` 使用；
     * 走 AGSL 时它保持空白，不产生任何开销。
     */
    private val enhancePaint = Paint()

    /** 画面增强当前实际走通的路：""（关）/ [PATH_AGSL] / [PATH_MATRIX] */
    private var screenEnhancePath = ""

    /** AGSL 是否可用。出错一次就永久降级，避免每次拖滑块重复抛异常刷日志 */
    private var agslAvailable = true

    private var isShowing = false
    private var style = Style(
        MODE_FULLSCREEN, 50, CORNER_BR, false, 50, false,
        hudText = null,
        hudTextSizeSp = HUD_TEXT_SIZE_DEFAULT_SP,
        smallWidthDp = SMALL_WIDTH_DEFAULT_DP,
        smallMarginDp = SMALL_MARGIN_DEFAULT_DP,
        framingPct = FRAMING_DEFAULT_PCT,
        borderDp = BORDER_DEFAULT_DP,
        lowLight = false,
        screenEnhance = false,
        screenContrast = SCREEN_CONTRAST_DEFAULT,
        screenShadowLift = SCREEN_SHADOW_DEFAULT,
        screenSaturation = SCREEN_SATURATION_DEFAULT,
        rotationMode = PreviewOrientationPolicy.MODE_AUTO
    )

    /**
     * 【小窗联动闪光灯】后置相机是否带闪光灯。
     * 由 openCamera() 读 CameraCharacteristics.FLASH_INFO_AVAILABLE 得到；
     * 不带闪光灯的机型不能下发 FLASH_MODE_TORCH（否则整条 preview request 会被 HAL 拒绝，
     * 表现为预览黑屏），所以这里做能力探测 + 自动降级。
     */
    private var flashAvailable = false

    /**
     * 【5.0.0 低光增强】要下发的曝光补偿档数。
     *
     * 由 openCamera 按机型读取：`CONTROL_AE_COMPENSATION_RANGE` 给上限，
     * `CONTROL_AE_COMPENSATION_STEP` 给每档多少 EV（常见 1/3 或 1/6）。
     * 目标是 +2 EV，按步长折算成档数后再与上限取小。
     * 0 表示本机不支持曝光补偿（或读不到），此时开关不产生任何请求改动。
     */
    private var aeCompTarget = 0

    /**
     * 【5.3.0 画面方向】后置传感器的安装角度。
     *
     * 绝大多数手机后摄是「横装在机身里」的（SENSOR_ORIENTATION = 90），
     * 所以读不到时按 90 兜底：猜错的代价只是画面转 90°，而猜 0 会让竖屏直接侧躺。
     */
    private var sensorOrientation = 90

    /** 预览输出缓冲尺寸（决定源画面宽高比）。0 = 还没选过，按窗口尺寸兜底 */
    private var previewSrcW = 0
    private var previewSrcH = 0

    /**
     * 上一次实际应用的旋转角。
     *
     * 单独存一份而不是每次现算：屏幕旋转、用户改模式、相机重开三条路径都会改它，
     * 存下来才能判断「这次到底变没变」，避免每帧重复写日志。
     */
    private var appliedRotDeg = -1

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
        contrastLevel: Int = 50,
        /** 【4.9.0】HUD 文案；null/空白 = 完全不显示文字层 */
        hudText: String? = null,
        /** 【4.9.0】HUD 字号 sp */
        hudTextSizeSp: Int = HUD_TEXT_SIZE_DEFAULT_SP,
        /** 【4.9.0】角落小窗宽度 dp */
        smallWidthDp: Int = SMALL_WIDTH_DEFAULT_DP,
        /** 【4.9.0】角落小窗边距 dp */
        smallMarginDp: Int = SMALL_MARGIN_DEFAULT_DP,
        /** 【5.0.0】取景范围 0~100（0=完整画面，100=只留下半） */
        framingPct: Int = FRAMING_DEFAULT_PCT,
        /** 【5.0.0】小窗描边宽度 dp（0=不描边） */
        borderDp: Int = BORDER_DEFAULT_DP,
        /** 【5.0.0】低光增强：预览请求下发正向曝光补偿 */
        lowLight: Boolean = false,
        /** 【5.1.0】画面增强总开关（动画面像素，不是给文字描边） */
        screenEnhance: Boolean = false,
        /** 【5.1.0】画面对比度 0~100 */
        screenContrast: Int = SCREEN_CONTRAST_DEFAULT,
        /** 【5.1.0】暗部提亮 0~100 */
        screenShadowLift: Int = SCREEN_SHADOW_DEFAULT,
        /** 【5.1.0】饱和度 0~100，50 = 不变 */
        screenSaturation: Int = SCREEN_SATURATION_DEFAULT,
        /** 【5.3.0】画面方向：auto / fixed / flip（见 PreviewOrientationPolicy） */
        rotationMode: String = PreviewOrientationPolicy.MODE_AUTO
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
            torchWithPreview,
            hudText?.takeIf { it.isNotBlank() },
            hudTextSizeSp.coerceIn(HUD_TEXT_SIZE_MIN_SP, HUD_TEXT_SIZE_MAX_SP),
            smallWidthDp.coerceIn(SMALL_WIDTH_MIN_DP, SMALL_WIDTH_MAX_DP),
            smallMarginDp.coerceIn(SMALL_MARGIN_MIN_DP, SMALL_MARGIN_MAX_DP),
            framingPct.coerceIn(FRAMING_MIN_PCT, FRAMING_MAX_PCT),
            borderDp.coerceIn(BORDER_MIN_DP, BORDER_MAX_DP),
            lowLight,
            screenEnhance,
            screenContrast.coerceIn(0, 100),
            screenShadowLift.coerceIn(0, 100),
            screenSaturation.coerceIn(0, 100),
            PreviewOrientationPolicy.normalizeMode(rotationMode)
        )
        // 【原地快刷】只改「对比度强度 / 闪光灯联动」这类不需要重建视图的参数时，
        // 不重建窗口、不重开相机：HUD 层改样式只要 invalidate，闪光灯只要重新下发
        // 一次 repeating request。否则拖一下进度条就「画面黑一下 + 重新握手 400ms」。
        // torchChanged / contrastChanged 必须在 style 被覆盖前算好。
        val torchChanged = target.torchWithPreview != style.torchWithPreview
        // 【4.9.0】文字相关的任何变化都只影响 HUD 层，连窗口都不用重排。
        // 单独拎出来是为了让「改字号/改文案/开关文字」不至于掉进下面的重建路径。
        val hudChanged = target.hudText != style.hudText
                || target.hudTextSizeSp != style.hudTextSizeSp
                || target.enhanceContrast != style.enhanceContrast
                || target.contrastLevel != style.contrastLevel
        // 尺寸/边距变化只需要 updateViewLayout，相机与 Surface 完全不用动。
        val geometryChanged = target.smallWidthDp != style.smallWidthDp
                || target.smallMarginDp != style.smallMarginDp
        // 【5.0.0】取景裁剪与描边都只是 View 层的事：改矩阵 / 改前景，不动相机。
        val visualChanged = target.framingPct != style.framingPct
                || target.borderDp != style.borderDp
        // 【5.1.0 画面增强】改的是 RenderEffect / colorFilter，同样是纯绘制层的事：
        // 不重建窗口、不重开相机，拖滑块能逐帧看到变化。
        val enhanceChanged = target.screenEnhance != style.screenEnhance
                || target.screenContrast != style.screenContrast
                || target.screenShadowLift != style.screenShadowLift
                || target.screenSaturation != style.screenSaturation
        // 低光增强要重下发一次 repeating request（曝光补偿在 CaptureRequest 里）
        val lowLightChanged = target.lowLight != style.lowLight
        // 【5.3.0 方向】改方向只重算矩阵，不动相机：缓冲尺寸与旋转角解耦，
        // 源画面比例不变，只是「怎么摆」变了。重开相机会黑一下，没必要。
        val rotationChanged = target.rotationMode != style.rotationMode
        if (isShowing && rootView?.parent != null
            && target.mode == style.mode
            && target.opacity == style.opacity
            && target.corner == style.corner
            && (hudChanged || geometryChanged || torchChanged || visualChanged
                || lowLightChanged || enhanceChanged || rotationChanged)
        ) {
            style = target
            if (hudChanged) applyHudStyle()
            if (enhanceChanged) applyScreenEnhance()
            if (geometryChanged) {
                rootView?.let {
                    try { wm.updateViewLayout(it, buildParams()) } catch (_: Throwable) {}
                }
            }
            // 窗口尺寸变了，transform 的基准也变了，必须跟着重算
            if (visualChanged || geometryChanged || rotationChanged) {
                if (rotationChanged) refreshRotation()
                applyFraming()
                applyBorder()
            }
            if (torchChanged || lowLightChanged) refreshRequestInSession()
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
        // 老 View 连同它的 RenderEffect 一起被丢掉，记录也跟着清空，
        // 下一次 apply 会按新 View 重新判断「要不要启用、走哪条路」
        screenEnhancePath = ""
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
        // 【5.1.0】画面增强：作用在 tv 上，与 HUD 层互不影响。
        // 必须放在窗口挂载之前 —— 否则第一帧会先以原始画质闪一下再变成增强画质。
        applyScreenEnhance()
        // 取景与描边都在 View 层：drawable / 矩阵独立于相机，重复调用是幂等的。
        // 复用旧窗口时宽高已经就绪，这里能立刻生效；新建窗口则在 Surface 就绪时再算一次。
        applyFraming()
        applyBorder()

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
            // 文案与字号统一由 applyHudStyle() 按 Style 写入，这里不再硬编码
            // 「路况预览中 · 增强对比度」—— 那句实现细节已经删掉。
            isClickable = false
            isFocusable = false
            setPadding(dp(14), dp(8), dp(14), dp(8))
            applyLevel()
        }

        private fun applyLevel() {
            val t = level / 100f
            // level=0 表示「不加任何修饰」：无描边、无衬底、无阴影。
            // 这样「文字开关」与「对比度开关」才能彼此独立 —— 想只要一行白字
            // 也可以，不必被迫接受黑边黑底。
            if (level <= 0) {
                strokePaint.strokeWidth = 0f
                bg.setColor(Color.TRANSPARENT)
                setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                invalidate()
                return
            }
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
     * 把当前 style 应用到 HUD 文字层。
     *
     * 【4.9.0 语义变更】可见性只由 [Style.hudText] 决定，不再由 [Style.enhanceContrast]
     * 决定。对比度从「文字层的总闸」降级为「文字层的画法」：
     *   · hudText 为空        → GONE，画面上一个字都没有（默认状态）；
     *   · hudText 非空 + 对比度关 → 显示纯白字，无描边无衬底；
     *   · hudText 非空 + 对比度开 → 显示带描边/阴影/衬底的字，强度由 level 决定。
     * 这样「不想看那句废话」和「想要字更清楚」就不再绑在一起。
     */
    private fun applyHudStyle() {
        val hud = hudView ?: return
        val text = style.hudText
        if (text.isNullOrBlank()) {
            hud.visibility = View.GONE
            return
        }
        hud.visibility = View.VISIBLE
        if (hud.text?.toString() != text) hud.text = text
        val currentSp = hud.textSize / ctx.resources.displayMetrics.scaledDensity
        if (Math.abs(currentSp - style.hudTextSizeSp) > 0.5f) {
            hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.hudTextSizeSp.toFloat())
        }
        hud.level = if (style.enhanceContrast) style.contrastLevel else 0
    }

    /**
     * 【5.0.0 取景范围 / 5.3.0 画面方向】把「源画面」映射到「窗口」。
     *
     * 参数：`PreviewOrientationPolicy.transform` 负责算 3×3 矩阵，这里只负责
     * 拿当前真实宽高、把矩阵塞进 TextureView。
     *
     * 【为什么用 transform 而不是相机裁剪】
     * `SCALER_CROP_REGION` 属于 CaptureRequest，改一次要重下发、部分 HAL 还会
     * 重新对焦，改个滑块就闪一下。TextureView 的 transform 是纯 View 层矩阵，
     * 拖滑块时逐帧生效，相机完全不知道。
     *
     * 【为什么是等比放大而不是只拉伸纵向】
     * 纵向拉长会让前后距离看起来比实际更远，看路的人会误判「还早」，反而更危险。
     * 等比放大只是把两侧裁掉，比例仍然可信。
     *
     * 【5.3.0 的这件事】
     * 老实现只做「纵向裁 + 放大」，等于默认「画面方向本来就是对的」。
     * 竖屏时靠窗口比例勉强凑合，一横过来就侧躺 —— 用户看到的就是「方向不对」。
     * 现在旋转、铺满、取景三件事一起算，见 [PreviewOrientationPolicy.transform]。
     */
    private fun applyFraming() {
        val view = tv ?: return
        val w = view.width
        val h = view.height
        // 还没布局完（宽高为 0）时算不出矩阵，onSurfaceTextureAvailable/
        // SizeChanged 里会再来一次；此时 setTransform 也会被系统忽略。
        if (w <= 0 || h <= 0) return

        val srcW = if (previewSrcW > 0) previewSrcW else w
        val srcH = if (previewSrcH > 0) previewSrcH else h
        // 三项都「不需要处理」时清空矩阵，走回旧版那条最简路径：
        // 少一层合成开销，也避免在某些 ROM 上 setTransform 与硬件叠层互相干扰。
        if (appliedRotDeg <= 0 && style.framingPct <= 0 && srcW == w && srcH == h) {
            view.setTransform(null)
            return
        }
        val values = PreviewOrientationPolicy.transform(
            w, h, srcW, srcH, appliedRotDeg, style.framingPct
        )
        val m = Matrix()
        m.setValues(values)
        view.setTransform(m)
    }

    /** 当前屏幕旋转角度（0/90/180/270）。取不到时按 0 处理，等价于「跟着竖屏」 */
    private fun currentDisplayDegrees(): Int = try {
        @Suppress("DEPRECATION")
        PreviewOrientationPolicy.displayDegrees(wm.defaultDisplay.rotation)
    } catch (_: Throwable) {
        0
    }

    /**
     * 只重算「该转多少度」，不动缓冲、不重建会话。
     *
     * 屏幕旋转与用户切换方向模式都走这里：源画面没变，只是摆法变了。
     */
    private fun refreshRotation() {
        val autoDeg = PreviewOrientationPolicy.rotationDegrees(
            sensorOrientation, currentDisplayDegrees(), false
        )
        val newDeg = PreviewOrientationPolicy.applyMode(autoDeg, style.rotationMode)
        if (newDeg == appliedRotDeg) return
        appliedRotDeg = newDeg
        diag(
            "方向切换 → 实转 " + newDeg + "°（传感器 " + sensorOrientation
                + "° 屏幕 " + currentDisplayDegrees() + "° 模式 " + style.rotationMode + "）"
        )
    }

    /**
     * 屏幕方向变化后的重排（由 WalkPreviewService.onConfigurationChanged 调用）。
     *
     * 刻意不检查任何用户开关：方向错了就是看不了路，不能因为「智能方案没开」
     * 而不处理。全屏窗口要换宽高、小窗高度上限要跟着屏高变、矩阵基准也变了。
     */
    fun onDisplayChanged() {
        if (!isShowing || rootView?.parent == null) return
        val view = tv ?: return
        if (view.width <= 0 || view.height <= 0) return
        refreshRotation()
        // 排序不能反：先重排窗口，布局完成后 SizeChanged 会再算一次矩阵；
        // 这里先按旧尺寸算一版，避免旋转瞬间出现一帧拉伸画面。
        rootView?.let {
            try { wm.updateViewLayout(it, buildParams()) } catch (_: Throwable) {}
        }
        applyFraming()
    }

    /**
     * 【5.0.0 小窗描边】给窗口加一圈半透明白边。
     *
     * 白天小窗叠在浅色路面上时，画面边界与背景亮度接近，看不出小窗到哪儿为止；
     * 一条 1~2dp 的白边就能把范围交代清楚。用 foreground 而不是背景：
     * 背景会被画面层完全盖住，foreground 画在最上层。
     */
    private fun applyBorder() {
        val root = rootView ?: return
        // 只给角落小窗描边：全屏铺满整块屏幕，边框会贴在屏幕边缘，
        // 看起来像「画面没铺满」，反而像故障。
        if (style.mode != MODE_SMALLWINDOW || style.borderDp <= 0) {
            root.foreground = null
            return
        }
        val d = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // 圆角跟窗口裁剪保持一致，否则描边会在圆角处露出尖角
            cornerRadius = if (style.mode == MODE_SMALLWINDOW) dp(24).toFloat() else 0f
            setStroke(dp(style.borderDp), Color.argb(204, 255, 255, 255))
        }
        root.foreground = d
    }

    /**
     * 【5.1.0 画面增强】把 [style] 里的对比度 / 暗部提亮 / 饱和度作用到**画面本身**。
     *
     * 这是这一版最重要的修正。上一版那个名叫「增强对比度」的开关，实际做的是
     * 给 HUD 文字加描边、阴影和深色衬底 —— 字是清楚了，可要看的从来不是那行字，
     * 是路面、台阶、路沿、井盖。用户反馈得很准：「不是一句增强对比度文字激励就可以了」。
     *
     * 实现上走 [RenderEffect] + AGSL 运行时着色器（见 [ENHANCE_AGSL]）：
     *  · 纯 GPU 后处理，TextureView 的 Surface 直通不变，相机侧零改动；
     *  · 只影响绘制，不影响相机采集与会话，拖滑块时逐帧生效，不掉帧不黑屏；
     *  · 关掉时把 RenderEffect 置 null、layerType 恢复 NONE，画面回到原始状态，
     *    不留任何常驻开销。
     *
     * AGSL 万一在某个 ROM 上抛异常，退到 [ColorMatrix]（只能线性拉伸，效果打折
     * 但绝不黑屏），并在日志里写清走的是哪条路 —— 用户反馈画面不对时，
     * 一眼就能看出是「着色器没生效」还是「参数不合适」。
     */
    private fun applyScreenEnhance() {
        val view = tv ?: return
        if (!style.screenEnhance) {
            if (screenEnhancePath.isNotEmpty()) {
                view.setRenderEffect(null)
                view.setLayerType(View.LAYER_TYPE_NONE, null)
                enhancePaint.colorFilter = null
                screenEnhancePath = ""
                diag("画面增强 → 关，恢复相机原始画面")
            }
            return
        }
        val k = style.screenContrast / 100f
        val lift = style.screenShadowLift / 100f
        val sat = (style.screenSaturation - 50) / 50f

        if (agslAvailable) {
            try {
                val shader = RuntimeShader(ENHANCE_AGSL)
                shader.setFloatUniform("contrast", k)
                shader.setFloatUniform("lift", lift)
                shader.setFloatUniform("sat", sat)
                view.setRenderEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, "content")
                )
                if (screenEnhancePath != PATH_AGSL) {
                    screenEnhancePath = PATH_AGSL
                    diag("画面增强 → 开（AGSL S 曲线）对比度=${style.screenContrast}%"
                            + " 暗部=${style.screenShadowLift}% 饱和=${style.screenSaturation}%")
                }
                return
            } catch (t: Throwable) {
                // 一次性永久降级：留着它会让每次拖滑块都抛一次，日志被刷满
                agslAvailable = false
                diag("画面增强 AGSL 不可用（${t.javaClass.simpleName}: ${t.message}）"
                        + " → 降级线性 ColorMatrix")
            }
        }

        // 兜底：线性对比度 + 饱和度，加一点整体提亮近似「暗部回抬」。
        // 效果不如 S 曲线（中灰会被一起推亮），但这是能保证不黑屏的最后一道。
        val s = 1f + k * 0.9f
        val offset = 127.5f * (1f - s) + lift * 22f
        val cm = ColorMatrix()
        cm.setSaturation(1f + sat)
        cm.postConcat(ColorMatrix(floatArrayOf(
            s, 0f, 0f, 0f, offset,
            0f, s, 0f, 0f, offset,
            0f, 0f, s, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )))
        enhancePaint.colorFilter = ColorMatrixColorFilter(cm)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, enhancePaint)
        if (screenEnhancePath != PATH_MATRIX) {
            screenEnhancePath = PATH_MATRIX
            diag("画面增强 → 开（ColorMatrix 兜底）对比度=${style.screenContrast}%")
        }
    }

    private fun buildTextureView(): TextureView {
        return TextureView(ctx.applicationContext).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    // apply 作用域内 surfaceTexture 会解析到 TextureView 的只读属性，必须显式限定
                    this@RearCameraOverlay.surfaceTexture = st
                    stage("画面缓冲就绪 ${w}x${h}")
                    // 取景矩阵依赖真实宽高，必须在这里算；show() 里算时窗口还没布局
                    this@RearCameraOverlay.applyFraming()
                    openCamera()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    // 小窗尺寸滑块拖动 / 横竖屏切换都会走到这里，矩阵基准随之变化
                    this@RearCameraOverlay.applyFraming()
                }

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
            // 【4.9.0】尺寸与边距改为用户可调。高度按 SMALL_ASPECT 换算而不是
            // 单独给一个值：宽高比固定，画面才不会在任意尺寸下被拉变形。
            val w = dp(style.smallWidthDp)
            // 先按比例四舍五入再转像素：`.toInt()` 是截断，180dp×1.7333 会算成 311 而不是 312，
            // 每次换算都少 1dp，调大宽度时能明显看出比例在飘。
            var h = dp(Math.round(style.smallWidthDp * SMALL_ASPECT))
            // 【5.3.0 横屏】横过来之后屏幕高度只剩竖屏的一半多（约 411dp 变成 200dp 出头
            // 的可用高度），200dp 宽的小窗换算出来 346dp 高，会直接顶出屏幕外。
            // 宽度不动（用户调的是宽度），只压高度，上限取屏高的 62%：
            // 留出状态栏与一点余量，画面本身是等比铺满，压扁一点只会多裁上下，不变形。
            val (_, screenH) = screenSize()
            val maxH = (screenH * 0.62f).toInt()
            if (maxH > 0 && h > maxH) h = maxH
            val margin = dp(style.smallMarginDp)
            WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        // 同上：TextureView 没有硬件加速就是一整块黑
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = vGrav or hGrav
                x = margin
                y = margin
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
        // 老 View 连同它的 RenderEffect 一起被丢掉，记录也跟着清空，
        // 下一次 apply 会按新 View 重新判断「要不要启用、走哪条路」
        screenEnhancePath = ""
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
        // 【5.0.0 低光增强】夜间路面几乎没有光，AUTO 曝光会把画面压成一片黑，
        // 看得见轮廓才谈得上「看路」。这里按机型支持范围给正向补偿，不做固定写死：
        // 不同 HAL 的补偿步长不同（1/3 EV 或 1/6 EV），固定值在部分机型上等于没设。
        if (style.lowLight && aeCompTarget > 0) {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeCompTarget)
        }
        return builder.build()
    }

    /**
     * 会话语义内的参数切换：窗口与相机会话都还在跑，不重建视图、不重开相机，
     * 只重新下发一次 repeating request。
     *
     * 当前走这条路径的只有两项，都在 CaptureRequest 里：闪光灯联动（FLASH_MODE_TORCH）
     * 与低光增强（CONTROL_AE_EXPOSURE_COMPENSATION）。
     */
    private fun refreshRequestInSession() {
        val d = camera
        val cs = session
        val surface = previewSurface
        if (d == null || cs == null || surface == null) {
            diag("[overlay] 相机会话未就绪，参数将在下次打开预览时生效")
            return
        }
        try {
            cs.setRepeatingRequest(buildPreviewRequest(d, surface), null, mainHandler)
            val torchOn = style.torchWithPreview && flashAvailable
            diag("[overlay] 预览请求已重下发 | 闪光灯=" + (if (torchOn) "常亮" else "熄灭")
                    + "(flashAvailable=$flashAvailable)"
                    + " 低光增强=" + (if (style.lowLight) "+${aeCompTarget}档" else "关"))
        } catch (t: Throwable) {
            // 相机正处于重连窗口时 createCaptureRequest 会抛异常：只记日志，
            // 下一轮 onConfigured 会用最新 style 重新构建，功能不会丢。
            diag("❌ 预览请求重下发失败: ${t.message}")
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
            // 【5.3.0 方向】顺序很关键：先拿到相机 id → 算旋转角与缓冲尺寸 →
            // 再把尺寸写给 SurfaceTexture → 最后建 Surface。
            // 反过来的话，会话会按「窗口宽高」这个错误比例配起来，
            // 中途改只能重建会话（画面黑一下），而横屏时用户往往正在走路。
            val id = backCameraId()
            resolveOrientation(id, view.width, view.height)
            st.setDefaultBufferSize(
                if (previewSrcW > 0) previewSrcW else view.width,
                if (previewSrcH > 0) previewSrcH else view.height
            )
            val surface = Surface(st)
            previewSurface = surface
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
            // 【5.0.0 低光增强】能力探测：补偿范围与步长都读得到才算支持。
            // 部分 ROM 的 HAL 会返回 null 或 0 上限，此时保持 aeCompTarget=0，
            // 开关开着也不下发——下发超出范围的档数会让 HAL 拒掉整条 request（预览黑屏）。
            aeCompTarget = try {
                val chars = cm.getCameraCharacteristics(id)
                val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                val stepEv = step?.toFloat() ?: 0f
                val max = range?.upper ?: 0
                if (max > 0 && stepEv > 0f) minOf(max, Math.round(2f / stepEv)) else 0
            } catch (_: Throwable) {
                0
            }
            if (style.lowLight && aeCompTarget <= 0) {
                diag("⚠️ 本机不支持曝光补偿，低光增强自动降级（预览照常显示）")
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

    /**
     * 【5.3.0 画面方向】把「转多少度、缓冲开多大」一次算清。
     *
     * 三件事必须一起做，分开做一定会出问题：
     *  · 只转角度不改缓冲 → HAL 把 4:3 的画面硬拉成小窗的长条，方向对了但路被压扁；
     *  · 只改缓冲不转角度 → 竖屏侧躺，就是这一版要修的那个 bug；
     *  · 两者都对但取景矩阵没跟上 → 一旋转，取景就从「只留下半」跳到别处。
     *
     * 尺寸一律从 `SCALER_STREAM_CONFIGURATION_MAP` 里挑，不能直接用窗口宽高：
     * SurfaceTexture 的输出尺寸必须是 HAL 支持的档位，否则 createCaptureSession
     * 会直接失败（画面全黑），而日志里只会看到一句「会话配置失败」。
     */
    private fun resolveOrientation(cameraId: String?, viewW: Int, viewH: Int) {
        var sensor = sensorOrientation
        var front = false
        var sizes: Array<IntArray>? = null
        if (cameraId != null) {
            try {
                val cc = cm.getCameraCharacteristics(cameraId)
                sensor = cc.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: sensor
                front = cc.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val arr = map?.getOutputSizes(SurfaceTexture::class.java)
                if (arr != null && arr.isNotEmpty()) {
                    sizes = Array(arr.size) { intArrayOf(arr[it].width, arr[it].height) }
                }
            } catch (t: Throwable) {
                diag("⚠️ 读取相机方向失败，按 90° 兜底: " + t.message)
            }
        }
        sensorOrientation = sensor
        val displayDeg = currentDisplayDegrees()
        val autoDeg = PreviewOrientationPolicy.rotationDegrees(sensor, displayDeg, front)
        val mode = PreviewOrientationPolicy.normalizeMode(style.rotationMode)
        appliedRotDeg = PreviewOrientationPolicy.applyMode(autoDeg, mode)

        val picked = PreviewOrientationPolicy.pickPreviewSize(sizes, viewW, viewH, appliedRotDeg)
        if (picked != null && picked.size >= 2) {
            previewSrcW = picked[0]
            previewSrcH = picked[1]
        } else {
            // 没有任何可用尺寸：退回窗口宽高。此时比例会被 HAL 拉过，
            // 但方向仍然是对的 —— 方向错是「没法用」，比例差只是「有点变形」。
            previewSrcW = viewW
            previewSrcH = viewH
            diag("⚠️ 相机未上报可用预览尺寸，按窗口尺寸兜底（画面可能略变形）")
        }
        diag(
            PreviewOrientationPolicy.describe(
                sensor, displayDeg, mode, autoDeg, appliedRotDeg, previewSrcW, previewSrcH
            )
        )
    }

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
