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
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.os.SystemClock
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
import android.widget.LinearLayout
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
 * @param onTorchLit 小窗联动闪光灯**真实点亮状态**变化回调（true=已下发 TORCH）。
 *                  只在这一刻才知道灯到底亮没亮，夜间照明的岛通知据此挂/撤。
 */
class RearCameraOverlay @JvmOverloads constructor(
    private val ctx: Context,
    private val onFailure: ((String) -> Unit)? = null,
    private val onStage: ((String) -> Unit)? = null,
    /** 仅测试用：注入一个可记录的 WindowManager，验证窗口参数是否合法 */
    wmOverride: WindowManager? = null,
    private val onTorchLit: ((Boolean) -> Unit)? = null,
    /**
     * 【5.7】预览会话被其他应用抢占（系统相机打开取景 / 扫码 / 视频通话）时回调。
     * 窗口已经自行收起，服务据此在几秒内不要再把它拉起来，避免两边来回抢设备。
     */
    private val onPreempted: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "SafeWalk-Cam"
        const val MODE_FULLSCREEN = "fullscreen"
        const val MODE_SMALLWINDOW = "smallwindow"

        /**
         * 【5.4.6 方案B · 横屏正立方向校准（单一取值点，单位：度）】
         *
         * 方案B在系统方向翻转时走「整条放开重算」：窗口变横条 845x488，角度由
         * PreviewOrientationPolicy.alignToWindow 重算。历史正立帧（日志2560）为
         * 横条845x488 + appliedRotDeg 0 + deg 0 + 缓冲488x845 —— 竖条横躺画面变成
         * 横条正立画面，用户肉眼所见正是「逆时针转正90°」。故默认 0 = 额外补偿 0，
         * 即完全采用 alignToWindow 的历史正立帧，与 plan.md 拍板一致。
         *
         * ⚠️ 真机校准（唯一取值点）：若横屏画面上下颠倒，把本值改为 90 或 180。
         * 它会在横条窗重算后叠加到 appliedRotDeg；alignToWindow(x, 横条=true) 再把
         * 0/90/180/270 归一成 deg∈{0,180}（横条窗不交换宽高），从而翻回正立。
         * 只改这一处即可，不碰任何公式、alignToWindow 原文与硬边界。
         */
        private const val LANDSCAPE_UPRIGHT_ROT_DEG = 0

        /**
         * 【5.6.0】横条小窗「自动/跟随系统」档的画面旋转角。
         *
         * 真机实测唯一正立值：270（左 270°）。历史 90 是在另一台传感器标定相反的
         * 机型上定的，搬到本机就是上下颠倒。手动档（上/右/下/左）仍可直接覆盖它。
         */
        private const val LANDSCAPE_SMALLWINDOW_DEG = 270

        /**
         * 【5.6.0 省电】角落小窗的预览短边上限（px）。
         *
         * 窗口本身只有 150~260dp，1080p 源在上面显示与 720p 肉眼无差，
         * 但像素搬运量差 2.25 倍，全部落在相机与 GPU 的高功耗区间。
         */
        private const val SMALLWINDOW_MAX_SHORT_SIDE = 720

        /** 【5.6.0 省电】预览锁 30fps：路况观察 30fps 完全够，60fps 白烧一倍电 */
        private const val PREFERRED_FPS = 30

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

        /** 【5.6.5】步数徽章默认字号（sp）：比 HUD 标签小一档，不抢主体 */
        const val STEP_BADGE_TEXT_SIZE_DEFAULT_SP = 12
        /** 【5.6.5】步数徽章字号下限（sp）：再小在高 DPI 屏上读不清 */
        const val STEP_BADGE_TEXT_SIZE_MIN_SP = 9

        /** 【5.6.5 步数奖励】小红旗尺寸（dp）：够小不挡路况，够大能看出是旗子 */
        const val STEP_FLAG_WIDTH_DP = 30
        const val STEP_FLAG_HEIGHT_DP = 22

        /** 小红旗 View 的 tag：UI 自动化 / 单测用它定位，不依赖子节点下标 */
        const val TAG_STEP_FLAG = "stepFlag"

        /** 取景范围 0~100：见 [Style.framingPct] */
        const val FRAMING_MIN_PCT = 0
        const val FRAMING_MAX_PCT = 100
        const val FRAMING_DEFAULT_PCT = 0

        /**
         * 【5.7.1】按源画面真实宽高比算竖条窗「折中填充」缩放（纯函数，无实例状态）。
         *
         * fill = 铺满竖条槽需要放大几倍（横画幅内容按宽度放进 boxW 后，高度 = boxW×srcH/srcW，
         * fill = boxH / 该高度）；折中取「1:1 与铺满的中点」= 1 + (fill-1)/2。
         *
         * · 16:9 源（1920×1080 档）：fill ≈ 3.08 → 2.04，与 5.4.0 的固定 2.0 等价，老画面不变；
         * · 4:3 源（隐藏超广角 id=3 的 3264×2448 / 960×720 档）：fill ≈ 2.31 → 1.65，
         *   横向多留约 15% 视野 —— 这正是 5.7.0 日志「放大=2.00x」把超广角白裁一半、
         *   用户感觉「超广角像没开」的直接原因（固定 2.0 是按 16:9 定死的，套到 4:3 上裁得过多）。
         *
         * 横条窗 / 参数非法一律 1f，不做任何裁切。
         */
        fun compromiseZoomFor(boxW: Int, boxH: Int, srcW: Int, srcH: Int): Float {
            if (boxH <= boxW || boxW <= 0 || srcW <= 0 || srcH <= 0) return 1f
            val fill = boxH.toFloat() * srcW.toFloat() / (boxW.toFloat() * srcH.toFloat())
            if (fill <= 1f) return 1f
            return 1f + (fill - 1f) * 0.5f
        }

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

                // ① gamma 提亮：g 由 contrast 驱动（沿用原 1.2 增益），保端点 c=0→0 / c=1→1，
                //    幂律天然不吹高光，取代原来把 c<0.5 全部压死的「以 0.5 为支点拉伸」
                half  g = half(1.0 + contrast * 1.2);
                half3 s = pow(c, half3(half(1.0) / g));

                // ② 暗部回抬：权重改为「越黑越大」pow(1-s,2)，高光处≈0 不动；
                //    B_CONST 统一 0.30（白天系数 0.12 / 夜间 0.21），lift 驱动，不新增 uniform
                const half B_CONST = half(0.30);
                half3 w = pow(half3(1.0) - s, half3(2.0));
                s = s + (half3(1.0) - s) * w * half(lift * B_CONST);

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

        /**
         * 相机遇忙（被别的应用占着 / 连续几轮开不出来）后的短静默期。
         *
         * 真机日志 2026-11-15 12:37 一分钟里窗口被重挂了 25 次，每一次都是
         * 「打开相机被拒(4) → 重试 → 被拒(4) → 6 秒看门狗收窗 → 回执 false →
         * 步态仍判走在走 → 立刻再 show」——用户看到的就是「一个大框来回变」。
         *
         * 15 秒而不是 5 分钟：相机被占用通常几十秒就结束（用户切走那个应用），
         * 静默太久会把「正常走路该显示」也一起拖住。静默期内 show() 直接返回，
         * 窗口保持未显示，回执稳定为 false，乒乓自然消失。
         */
        private const val CAMERA_BUSY_BACKOFF_MS = 15_000L

        /**
         * 【5.7.3】独立子相机（隐藏超广角）打不开后的冷却时长。
         *
         * 失败多半是瞬时的相机占用，不是这台机器的 HAL 不支持；
         * 冷却太短会「失败→立刻重试→再失败」刷屏，太长则等于永久拉黑。
         * 10 分钟：用户走一段路就自动恢复正常选镜，期间稳定走主摄不出黑屏。
         */
        private const val LENS_RETRY_COOLDOWN_MS = 10 * 60 * 1000L

        /** 【5.7.3】同一镜头连续失败几次才进冷却（用户要求：先试 2 次） */
        private const val LENS_MAX_ATTEMPTS = 2
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
        val rotationMode: String,
        /**
         * 【5.4.15 手动方向】系统横屏时用户指定的窗口形状（auto/landscape/portrait）。
         * auto = 不覆盖，窗口形状继续由 [isLandscapeWindowNow] 的系统/姿态判据决定。
         */
        val landscapeWindowShape: String = ManualOrientation.SHAPE_AUTO,
        /** 【5.4.15】系统竖屏时的窗口形状，取值同上，默认 auto（不覆盖）。 */
        val portraitWindowShape: String = ManualOrientation.SHAPE_AUTO,
        /**
         * 【5.4.15】后摄画面转正方向（auto/up/right/down/left）。
         * auto = 沿用 5.4.14 的 deg when 结果；显式选择只在横条小窗生效，竖条恒 0、全屏不套用。
         */
        val rearCameraFacing: String = ManualOrientation.FACING_AUTO,
        /**
         * 【路况预览 · 镜头】用哪颗后置摄像头（auto/main/ultrawide，见 CameraLensPolicy）。
         * 变化要触发相机重新打开（换 id），不进任何「原地快刷」分支。
         */
        val cameraLens: String = CameraLensPolicy.MODE_AUTO
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

    /**
     * 【5.3.6】旋转层：专门负责「把画面转到正立」。
     *
     * 之前旋转是算一个 3×3 矩阵交给 [TextureView.setTransform]，但那条路的坐标
     * 语义在真机上始终对不上：5.3.3 不加逆拉伸 → 画面缩成四成贴左上；5.3.4/5.3.5
     * 加了逆拉伸 → 视野被横向放大近 4 倍，只剩中间一条。同一个公式两种结果，
     * 说明「系统把 buffer 拉伸到 View 之后，transform 到底作用在哪套坐标」在
     * 这个 ROM 上不可预期 —— 继续猜没有意义。
     *
     * 现在改成：布局框按「旋转前」的长宽摆好（长边为宽），再用 [View.setRotation]
     * 转 90°。旋转后正好等于窗口尺寸，是纯 View 层行为，没有任何坐标歧义。
     * 矩阵彻底退出（[TextureView.setTransform] 传 null），系统默认的「buffer 铺满
     * 布局框」就是我们要的效果。
     */
    private var rotLayer: FrameLayout? = null
    /**
     * 【5.3.6】上一次算对的「窗口」尺寸（不是旋转层尺寸）。
     *
     * 旋转之后 TextureView 自身的宽高是**交换过**的，直接拿它当窗口尺寸会再交换
     * 一次，画面就转回去了。布局完成前 rootView 的宽高可能还是 0，这里留一份
     * 最后一次可信的窗口尺寸兜底。
     */
    private var lastWindowW = 0
    private var lastWindowH = 0
    /** 【5.3.7】真正写给 SurfaceTexture 的缓冲尺寸，与选中的 previewSrcW/H 可能不同 */
    private var actualBufferW = 0
    private var actualBufferH = 0
    /** 增强对比度开启时显示的 HUD 文字层；关闭时置 GONE，不移除（避免频繁重建） */
    private var hudView: HudTextView? = null
    /**
     * 【5.6.5 今日步数】HUD 文字下方的一行「今日 N,NNN 步」徽章。
     *
     * 与 [hudView] 同款描边/衬底（[HudTextView]），字号小一档；放在同一个垂直列里，
     * 静止时由服务层下发空文案隐藏，走路时才出现。
     */
    private var stepBadge: HudTextView? = null
    /**
     * HUD 垂直列（文字 + 步数徽章）。
     *
     * 旋转只转这一列，而不是分别转两个子 View —— 列内相对位置（步数在文字下方）
     * 在画面旋转后依然成立，不会出现「文字转了、步数没转」的错位。
     */
    private var hudColumn: LinearLayout? = null

    // 【无障碍】HUD 播报状态：上一次播出去的整句、时间戳、当时的步数快照。
    // 只在「文案变了」且过了节流窗（或走够 50 步里程碑）时才主动播，见 HudA11yPolicy。
    private var lastHudDesc: String? = null
    private var lastHudAnnounceTs: Long = 0L
    private var stepsAtLastHudAnnounce: Long = 0L
    /** 今日步数文案；null / 空串 = 隐藏徽章。由 [setStepText] 写入 */
    private var stepText: String? = null
    /**
     * 【5.6.5 步数奖励】今日步数本身（0 = 还没数据）。
     *
     * 与 [stepText] 分开存：文案会因「静止 60 秒」被服务层下发成 null 而隐藏，
     * 但**描边颜色是全天成绩**，不该跟着一起退回白色 —— 停下歇会儿就把奖励色收走，
     * 反而像「走了一天白走了」。[setTodaySteps] 只更新这个，不碰文案可见性。
     */
    private var todaySteps: Long = 0L
    /** ≥ 2 万步的奖励小红旗（窗口左上角内侧，自绘） */
    private var stepFlag: StepFlagView? = null
    /**
     * 【5.6.5】「步数奖励」总开关（小窗自定义里可关）。
     *
     * 关掉 = 描边退回原来的白色、不挂小红旗；步数徽章本身不受影响 ——
     * 那是「看今天走了多少」的信息，奖励只是额外的颜色反馈，
     * 不能因为不想要奖励就把数字也一起拿掉。
     */
    private var stepRewardEnabled: Boolean = true
    /**
     * 【5.6.7 功耗】描边 drawable 缓存。
     *
     * 旧写法每次 applyBorder 都 new 一个 GradientDrawable 并赋给 foreground：
     * 步数每涨一步就要重建一次（走路时 1~2 次/秒），一天几千次无意义分配，
     * 还会让 WindowManager 重新合成前景层。现在只在「宽度 / 颜色」真变了才重建。
     */
    private var borderDrawable: GradientDrawable? = null
    /** 缓存键：`宽度dp#颜色`，任一项变化才重建 */
    private var borderKey: String = ""

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
     * 【5.6.16】上一次向外部报告的「闪光灯真实点亮」状态。
     *
     * 只在 false↔true 翻转时回调一次：夜间照明的岛通知是「状态型」的，
     * 反复重建 request 时不能重复发，收起时还必须撤掉。
     */
    private var torchLitReported = false

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
     * 【路况预览 · 超广角】逻辑摄像头要下发的 zoom ratio。
     *
     * 现代机型只暴露一颗后置 id，超广角靠 `CONTROL_ZOOM_RATIO=0.6` 切换（见
     * [CameraLensPolicy.zoomForLogicalUltrawide]）。由 [backCameraId] 在选中相机时
     * 读该 id 的 zoom range 决定；null = 不下发该 key，保持原生 1.0（主摄 / 无广角机型）。
     */
    private var requestedZoomRatio: Float? = null

    /** 【5.6.0 省电】本次会话锁定的 AE 目标帧率区间；null=不干预（HAL 未上报可用区间） */
    private var aeFpsRange: android.util.Range<Int>? = null

    /**
     * 【省电专项】当前已打开相机对应的物理 id，以及它是否支持逻辑 zoom（下限<1）。
     * 镜头切换时据此判断是否只需原地切 zoom、而不用 close→open 相机。
     * 仅当 [CameraLensPolicy.needsReopenOnLensChange] 判定「物理 id 变了」才重开。
     */
    private var openedCameraId: String? = null
    private var openedCameraSupportsZoom = false

    /**
     * 【5.7.1】本次枚举到底有没有拿到「真正的超广角」。
     *
     * 判据与 [CameraLensPolicy.select] 一致：等效焦距 ≤ 主摄的 [CameraLensPolicy.ULTRAWIDE_EFL_RATIO]
     * 倍；逻辑摄像头机型（id 不变、靠 CONTROL_ZOOM_RATIO<1 实现）也算真广角。
     * 覆盖安装 / 冷启动后第一次枚举时，HyperOS 的隐藏子相机（id=2/3/4）有时还没就绪，
     * 于是「UI 明明选着超广角、开出来却是主摄」，再点一次超广角才恢复 —— 用户反馈的正是这个。
     */
    private var lastPickRealUltrawide = false

    /** 【5.7.1】进程内只做一次「超广角自愈重开」：真没广角的机型不该每次显示都闪一下 */
    private var ultrawideHealAttempted = false

    /**
     * 【5.7.3 冷却重试】打不开的非主摄镜头 id → 冷却截止时刻（elapsedRealtime）。
     *
     * 5.3.0 用的是「一次失败永久拉黑到进程结束」，但失败常常只是瞬时占用
     * （真机 15:35:19 id=3 打不开，这段时间相机正被别的应用拿着），
     * 之后整段会话都再没试过超广角。现在失败只写冷却时间戳，
     * [isLensCooling] 到点后自动放行，主摄本身永不入表。
     */
    private val lensCooldownUntil = HashMap<String, Long>()

    /**
     * 【5.7.3】同一镜头的连续失败次数：先给 2 次机会，第 2 次仍失败才进冷却。
     *
     * 用户明确要求「超广角可以尝试 2 次，实在获取不到再加入（黑名单）」——
     * 一次失败可能是相机被占用或被 HAL 拒了一次，立刻冷却等于白白损失整个步行时段。
     * 收到画面即清零，下一次出问题仍然有完整的 2 次额度。
     */
    private val lensFailCount = HashMap<String, Int>()

    private fun isLensCooling(id: String): Boolean {
        val until = lensCooldownUntil[id] ?: return false
        if (SystemClock.elapsedRealtime() < until) return true
        lensCooldownUntil.remove(id)
        return false
    }

    /** 【超广角兜底】当前选中超广角独立镜头时，记下对应的主摄 id，供失败回退 */
    private var fallbackMainId: String? = null

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

    // ==================== 【5.4.5 方向锁定】====================

    /**
     * 小窗首帧构图快照。
     *
     * 【5.4.5 方向锁定】小窗第一次真正把方向算出来之后（openCamera 内 applyFraming
     * 已落盘、setDefaultBufferSize 之前），把这一帧的构图成组冻结下来；此后小窗
     * 无论姿态/系统方向/framing 怎么变，都幂等重放这份快照，绝不重新推导。
     *
     * 必须**成组**冻结：只锁角度不锁窗口 W/H，回小窗时会出现「竖条却转 90°」；
     * 只锁角度不锁 HUD，HUD 会歪（5.4.2 回退）；只锁角度不锁 zoom/pivot，深色模式
     * 一切换 framing 0↔55 又会「变样」。所以下述 9 组字段缺一不可。
     */
    private data class LockedComposition(
        /** 旋转角真值（appliedRotDeg），小窗冻结后 refreshRotation 直接取它 */
        val rotDeg: Int,
        /** 窗口宽高（lastWindowW/H），buildParams 小窗分支用它替代实时姿态尺寸 */
        val winW: Int, val winH: Int,
        /** applyFraming 判定出来的展示角（竖屏恒 0） */
        val deg: Int,
        /** 旋转层布局框：旋转前长宽摆法 */
        val boxW: Int, val boxH: Int,
        /** 旋转层角度与 HUD 反向角（layer.rotation / hudView.rotation） */
        val layerRot: Float, val hudRot: Float,
        /** 取景锚点（view.pivotX/Y） */
        val pivotX: Float, val pivotY: Float,
        /** 取景缩放（view.scaleX/Y） */
        val scaleX: Float, val scaleY: Float,
        /** 相机 buffer（actualBufferW/H），回小窗重开时钉死首帧值 */
        val bufferW: Int, val bufferH: Int,
        /** 构图参数快照：判断是否为用户主动改设置的依据（本版小窗一律忽略） */
        val framingPct: Int, val rotationMode: String
    )

    /**
     * 小窗锁定构图。null = 还没建锁（首帧未定型 / 一直全屏）。
     *
     * 【Q-A】跨 hide/show 保留：teardown() 刻意不清本字段，只有进程重启（对象重建）
     * 才回到 null 重新建锁——用户诉求是「小窗永远一个样」。
     */
    private var smallLock: LockedComposition? = null

    /**
     * 【5.4.6 方案B】系统方向切换「放行一次 + 重锁一次」的防重布尔标志。
     *
     * onConfigurationChanged 同一轮翻转会先调 onDisplayChanged()、尾部 doShow() 再走 show() 快路径；
     * 姿态传感器回调也会并发推进 poseDeg。三者都必须只认这「一次」系统方向翻转，
     * 不能被重复放行重算、更不能重复建锁/漏建锁（否则退回「转一次缺一行」）。
     * 本标志在 onDisplayChanged 检测到真翻转时置位，本轮重算+重锁完成后复位。
     */
    private var orientationSwitchInProgress = false

    /**
     * 【5.4.6 方案B】建锁时记录的系统屏幕朝向（true=横屏）。
     * 用作「真翻转」判据：只有 newConfig 朝向与建锁时不同，才算系统横竖屏真变了，
     * 才放行整条重算；同一朝向内的重复回调（姿态晃动 / 深色模式 / 宽度变更）一律维持锁定。
     */
    private var lockScreenLandscape: Boolean? = null

    /**
     * 【5.4.7 真机修复】最近一次由系统方向回调下传的可靠屏幕朝向（true=横屏）。
     * 每次方向回调开头刷新，专供重锁基准使用——杜绝再依赖 Service Context 下
     * 不随 configuration 更新的 ctx.resources 判据。null = 进程启动后尚未收到方向回调。
     */
    private var lastKnownScreenLandscape: Boolean? = null

    // ==================== 【5.3.1 姿态】====================

    /**
     * 重力姿态跟踪器。见 [DevicePosePolicy]。
     *
     * 为什么不再用 `Display.getRotation()`：用户手机开着旋转锁定，那个值恒为 0，
     * 横过来也不变 —— 真机日志 2.5 小时里只有 SCREEN_ON/OFF，一次方向变化都没有。
     */
    private val poseTracker = DevicePosePolicy.PoseTracker()

    /** 当前窗口朝向（0/90/180/270），由重力样本驱动，与系统旋转开关无关 */
    private var poseDeg = DevicePosePolicy.NO_SAMPLE

    private val sensorManager: SensorManager? by lazy {
        try {
            ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        } catch (_: Throwable) {
            null
        }
    }

    private var poseTracking = false

    /** 已收到的重力样本数：用来区分「注册了但 ROM 不给数据」和「数据正常」 */
    private var poseSamples = 0

    private val poseListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // 首个样本单独回执。5.3.1 只在朝向**变化**时打日志，竖着拿时
            // 首样本就是 0°、与初值相同，于是整个日志里一条姿态记录都没有 ——
            // 「没注册上」和「注册好了但一直竖着」看起来完全一样。
            poseSamples++
            if (poseSamples == 1) {
                diag(
                    "姿态首个样本 ax=${fmt(event.values[0])} ay=${fmt(event.values[1])}" +
                        " az=${fmt(event.values[2])} 传感器=${event.sensor?.name}"
                )
            }
            val deg = poseTracker.update(
                event.values[0], event.values[1], event.values[2],
                SystemClock.elapsedRealtime()
            )
            if (deg == DevicePosePolicy.NO_SAMPLE || deg == poseDeg) return
            poseDeg = deg
            val landscape = DevicePosePolicy.isLandscapeWindow(deg, style.rotationMode)
            diag(
                "手机姿态 → " + deg + "°" + (if (landscape) "（横条）" else "（竖条）")
                + " 样本=$poseSamples"
            )
            // 传感器回调在别的线程，窗口操作必须回主线程
            mainHandler.post { applyPoseChange() }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    private fun startPoseTracking() {
        if (poseTracking) return
        val sm = sensorManager ?: run {
            diag("⚠️ 没有传感器服务，方向退化为按屏幕旋转判断")
            return
        }
        // 【5.3.3】优先用 TYPE_GRAVITY。
        //
        // 真机日志（2026-11-15，HyperOS 4 / Redmi）里 5.3.1 的姿态监听从未
        // 产出过一个样本：没有「手机姿态 →」，也没有任何注册失败的告警 ——
        // 说明 registerListener 返回了 true，但 ROM 一帧都没回调。
        // 加速度计在这些 ROM 上会被按进程限流（SystemUI 属于低优先级后台），
        // 而 TYPE_GRAVITY 是系统融合出来的虚拟传感器，不直接排他的硬件通道，
        // 拿到的同样是重力向量（世界坐标下的 g），语义与 ACCELEROMETER 一致。
        // 拿不到再退回 ACCELEROMETER，两级都失败才降级为屏幕旋转。
        val gravity = try {
            sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        } catch (_: Throwable) {
            null
        }
        val accel = try {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } catch (_: Throwable) {
            null
        }
        val sensor = gravity ?: accel
        if (sensor == null) {
            diag("⚠️ 本机没有重力/加速度计，方向退化为按屏幕旋转判断")
            return
        }
        try {
            // SENSOR_DELAY_UI≈60ms：姿态判定只需要「横还是竖」，不需要高精度；
            // 用 GAME 档在行走时白耗电。迟滞与驻留在策略层做，不靠采样率。
            val ok = sm.registerListener(poseListener, sensor, SensorManager.SENSOR_DELAY_UI)
            poseTracking = ok
            // 注册结果必须回执：只有「注册成功」和「一个样本都没来」分开记，
            // 下次回传日志才能判断是没注册上，还是注册了但 ROM 不给数据。
            // （5.3.1 只打了姿态变化，没打注册结果，真机上一片空白时无从下手。）
            if (ok) {
                diag("姿态监听已启动（${sensor.name} type=${sensor.type}），等待首个样本")
                // 3 秒还没有一帧就明确告警：这是「注册成功但 ROM 不给数据」，
                // 与「注册被拒」是两种完全不同的修法，日志里必须分开。
                mainHandler.postDelayed({
                    if (poseTracking && poseSamples == 0) {
                        diag("⚠️ 姿态传感器 3 秒内没有回调（${sensor.name}），" +
                            "方向暂按屏幕旋转判断；请回传此日志")
                    }
                }, 3000L)
            } else {
                diag("⚠️ ${sensor.name} 注册被拒，方向退化为按屏幕旋转判断")
            }
        } catch (t: Throwable) {
            diag("⚠️ 注册姿态传感器失败: " + t.message)
        }
    }

    private fun stopPoseTracking() {
        if (!poseTracking) return
        try {
            sensorManager?.unregisterListener(poseListener)
        } catch (_: Throwable) {
        }
        poseTracking = false
        poseTracker.reset()
        poseDeg = DevicePosePolicy.NO_SAMPLE
    }

    /**
     * 姿态变化后重排窗口。
     *
     * 顺序：先换窗口宽高（横条 ↔ 竖条），布局完成后 SizeChanged 会再算一次矩阵，
     * 这里先算一版避免旋转瞬间闪一帧拉伸画面。
     */
    private fun applyPoseChange() {
        if (!isShowing) return
        val view = tv ?: return
        if (view.width <= 0 || view.height <= 0) return
        var relocking = false
        // 【5.4.5 方向锁定】同一朝向内的姿态抖动不重建窗口：小窗已锁定构图时忽略。
        //
        // 【5.6.1 横屏必横】但「横 ↔ 竖」这种真翻转必须放行一次——原来这里对 any 姿态变化
        // 一刀切 return，于是在游戏里（display rotation 不跟着变、只有重力显示横着）小窗
        // 会被首帧的竖条锁死，后摄画面在竖条里横躺，用户看到的就是「路里预览变竖了」。
        // 判据用同一份 [liveWindowLandscape]，与窗口尺寸、画面角度同源；
        // 姿态本身已过 PoseTracker 的 45° 容忍带 + 驻留去抖，不会来回跳。
        if (style.mode == MODE_SMALLWINDOW && smallLock != null) {
            val lock0 = smallLock!!
            val lockedLandscape = lock0.winW > lock0.winH
            val wantLandscape = liveWindowLandscape()
            if (wantLandscape == lockedLandscape) return
            diag(
                "姿态真翻转 → 解锁重排 | 锁定=" + (if (lockedLandscape) "横条" else "竖条")
                    + " " + lock0.winW + "x" + lock0.winH
                    + " 目标=" + (if (wantLandscape) "横条" else "竖条")
                    + " 姿态=" + poseDeg + "°"
            )
            smallLock = null
            lockScreenLandscape = wantLandscape
            relocking = true
        }
        val lp = buildParams()
        rootView?.let { root ->
            try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {}
            // 5.4.3 旋转重建后重算圆角轮廓：post 等 updateViewLayout 的异步布局完成、view 宽高刷新
            root.post { root.invalidateOutline() }
        }
        refreshRotation()
        // 用目标尺寸喂 framing：updateViewLayout 异步，此刻 view 宽高还是旧的
        applyFraming(lp.width, lp.height)
        if (relocking) {
            // 重算完成后在新朝向下重新建锁，否则下一次姿态抖动会把画面写回旧构图
            lockFirstFrame()
            diag("🔄 姿态翻转已重算并重锁 | 窗口="
                + (if ((smallLock?.winW ?: 0) > (smallLock?.winH ?: 0)) "横条" else "竖条")
                + " deg=" + (smallLock?.deg ?: -1))
        }
    }

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

    /**
     * 相机遇忙的短静默截止时刻（见 [CAMERA_BUSY_BACKOFF_MS]）。
     *
     * 与 [hardFailureBackoffUntil] 分开是有意的：那个是「权限没了，5 分钟别试」，
     * 这个是「相机被占着，等一会儿再看」。合成一个字段就只能取一种时长，
     * 要么把偶发占用封 5 分钟，要么让权限缺失每 2 秒重试一次。
     */
    @Volatile
    private var cameraBusyUntil = 0L

    /**
     * 方向链路摘要：一行说清「窗口形状 / 传感器读数 / 姿态 / 实转角 / 缓冲尺寸」。
     *
     * 这五个数是判断画面朝向所需的全部输入。有了它，「画面侧躺」到底是
     * 传感器读歪了、姿态没样本、还是矩阵没跟上，一眼分得出来，不必再靠截图反推。
     */
    private fun orientationSummary(): String {
        // 【5.3.7】这里必须用「窗口」尺寸，不能用 tv 的尺寸：旋转之后 tv 的宽高
        // 是交换过的，5.3.6 的日志因此把 488x845 的竖条窗口打成了「横条 845x488」，
        // 看日志的人会被直接带偏。
        val w = if (lastWindowW > 0) lastWindowW else (tv?.width ?: 0)
        val h = if (lastWindowH > 0) lastWindowH else (tv?.height ?: 0)
        val layer = rotLayer
        return "窗口=" + (if (w > h) "横条" else "竖条") + " " + w + "x" + h +
            " 传感器=" + sensorOrientation + "°" +
            " 姿态=" + (if (poseDeg == DevicePosePolicy.NO_SAMPLE) "无" else poseDeg.toString() + "°") +
            // 【5.3.7】竖条窗口根本不转，这里必须打「实际生效」的角，
            // 否则日志里写着「实转=90°」而画面纹丝不动，排查时会被带偏。
            " 实转=" + (if (h >= w) 0 else appliedRotDeg) + "°" +
            (if (h >= w && appliedRotDeg > 0) "（竖屏固定不转，算得 " + appliedRotDeg + "°）" else "") +
            " 旋转层=" + (layer?.width ?: 0) + "x" + (layer?.height ?: 0) +
            "@" + (layer?.rotation?.toInt() ?: 0) + "°" +
            " 放大=" + "%.2f".format(tv?.scaleX ?: 1f) + "x" +
            " 缓冲=" + (if (actualBufferW > 0) actualBufferW else previewSrcW) + "x" + (if (actualBufferH > 0) actualBufferH else previewSrcH) +
            // 【5.4.5 方向锁定】真机日志直接看出锁定是否生效、锁的是横条还是竖条
            (if (smallLock != null) {
                val lk = smallLock!!
                " 🔒锁定=" + (if (lk.winW > lk.winH) "横条" else "竖条") +
                    " ${lk.winW}x${lk.winH} deg=${lk.deg}" +
                    " 缓冲=${lk.bufferW}x${lk.bufferH}"
            } else "")
    }

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
        rotationMode: String = PreviewOrientationPolicy.MODE_AUTO,
        /** 【5.4.15】系统横屏时的窗口形状：auto / landscape / portrait */
        landscapeWindowShape: String = ManualOrientation.SHAPE_AUTO,
        /** 【5.4.15】系统竖屏时的窗口形状：auto / landscape / portrait */
        portraitWindowShape: String = ManualOrientation.SHAPE_AUTO,
        /** 【5.4.15】后摄转正方向：auto / up / right / down / left */
        rearCameraFacing: String = ManualOrientation.FACING_AUTO,
        /** 【路况预览 · 镜头】auto / main / ultrawide（见 CameraLensPolicy） */
        cameraLens: String = CameraLensPolicy.MODE_AUTO
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
        // 相机遇忙的短静默期：同样是「先别重建窗口」，但只等 15 秒。
        // 这一条就是 12:37 那个「一分钟 25 次挂载」的直接修复：
        // 失败后不再每次 show 都 addView，而是在静默期内原地不动。
        if (System.currentTimeMillis() < cameraBusyUntil) {
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
            PreviewOrientationPolicy.normalizeMode(rotationMode),
            ManualOrientation.normalizeShape(landscapeWindowShape),
            ManualOrientation.normalizeShape(portraitWindowShape),
            ManualOrientation.normalizeFacing(rearCameraFacing),
            CameraLensPolicy.normalize(cameraLens)
        )
        // 【5.3.3】姿态监听提前到「挂窗口之前」。
        //
        // 5.3.1 是挂完窗口再注册（见 apply 末尾），结果第一次挂出来的窗口
        // 必然是按 display.rotation 猜的：竖着拿、系统却报 90° 时，
        // 窗口直接就是 845×488 的横条（真机 13:37~13:41 连续五次都是横条）。
        // 传感器样本要几百毫秒才到，而这段空窗期正是用户第一眼看到的画面。
        // 提前注册不改变「样本到了再改形状」的机制，只是把空窗期挪到挂窗口之前。
        startPoseTracking()
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
        // 【5.4.15 手动方向】三项手动值任一变化都要走「清锁 + 重算 + 重锁」：
        // 小窗首帧锁（5.4.5）会忽略普通的方向/取景改动，不清锁就会出现「点了不生效」。
        val manualOrientChanged = target.landscapeWindowShape != style.landscapeWindowShape
                || target.portraitWindowShape != style.portraitWindowShape
                || target.rearCameraFacing != style.rearCameraFacing
        // 【路况预览 · 镜头】换镜头 = 换相机 id，必须重新 openCamera，不能走任何原地快刷。
        // 它不触发窗口/视图重建，只需在 style 落盘后主动 close → open（带 HAL 冷却）。
        val lensChanged = target.cameraLens != style.cameraLens
        // 预览没开时也会带着旧的 smallLock 回到这里（锁跨 hide/show 保留）。先在这里清掉，
        // 让「重建路径」按新值实时重算；下方快路径再清一次是幂等的，随后会重新建锁。
        if (manualOrientChanged) smallLock = null
        if (isShowing && rootView?.parent != null
            && target.mode == style.mode
            && target.opacity == style.opacity
            && target.corner == style.corner
            && (hudChanged || geometryChanged || torchChanged || visualChanged
                || lowLightChanged || enhanceChanged || rotationChanged || manualOrientChanged
                || lensChanged)
        ) {
            style = target
            // 【省电专项 · 路况预览镜头】换镜头不再一律关相机重开。
            // 现代机型只有一颗后置逻辑摄像头，超广角靠 CONTROL_ZOOM_RATIO=0.6 实现，
            // 同一颗 id 原地重下发 repeating request 就能换视野，省掉一次 HAL 大电流握手。
            // 只有独立多摄（物理 id 真的变了）才 close→open。判定见 CameraLensPolicy。
            if (lensChanged && isShowing) {
                val targetId = backCameraId()   // 按新 style.cameraLens 算出目标 id 并刷新 requestedZoomRatio
                // 第三参用「当前相机记录的 zoom 能力」：策略只在 oldId==newId 时读它，
                // 那一刻当前相机就是目标相机，能力一致，省一次 CameraManager 查询。
                val mustReopen = CameraLensPolicy.needsReopenOnLensChange(
                    openedCameraId, targetId, openedCameraSupportsZoom)
                if (mustReopen) {
                    diag("[overlay] 镜头切换 → ${target.cameraLens}，物理 id 变化"
                            + "($openedCameraId→$targetId)，关闭旧相机并重开")
                    closeCamera()
                    openCamera()
                } else {
                    // 同一颗逻辑摄像头：只重下发一次 CaptureRequest，0.6x/1.0x 立即生效。
                    diag("[overlay] 镜头切换 → ${target.cameraLens}，同一逻辑 id=$targetId"
                            + " 原地切 zoom=${requestedZoomRatio ?: 1.0f}，不重开相机（省电）")
                    refreshRequestInSession()
                }
            }
            if (hudChanged) applyHudStyle()
            if (enhanceChanged) applyScreenEnhance()
            if (geometryChanged) {
                rootView?.let {
                    try { wm.updateViewLayout(it, buildParams()) } catch (_: Throwable) {}
                }
            }
            // 【5.4.15】手动方向改动：清旧锁 → 按新值重算 → 重锁，改完立刻看得见。
            // 必须在上面 style = target 之后调用，确保重算读到的是新档位。
            if (manualOrientChanged) reapplyManualOrientation()
            // 窗口尺寸变了，transform 的基准也变了，必须跟着重算
            if (visualChanged || geometryChanged || rotationChanged) {
                if (style.mode == MODE_SMALLWINDOW && smallLock != null) {
                    // 【5.4.5 方向锁定】【Q-B】小窗显示期间忽略用户主动改取景/宽度/方向模式：
                    // 不 refreshRotation、不 applyFraming，构图永远是首帧那一份；只更新描边。
                    applyBorder()
                } else {
                    if (rotationChanged) refreshRotation()
                    applyFraming()
                    applyBorder()
                }
            }
            diag("[overlay] torchChanged=$torchChanged lowLightChanged=$lowLightChanged → ${if (torchChanged || lowLightChanged) "refresh" else "skip"}")
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
        // 【5.6.16】窗口收起 = 预览会话结束 = 常亮闪光灯随之失效，
        // 立刻撤掉「夜间照明已点亮」的岛通知，不能留一条过时状态挂着。
        if (torchLitReported) {
            torchLitReported = false
            try { onTorchLit?.invoke(false) } catch (_: Throwable) {}
        }
        cancelWatchdog()
        teardown()
        Log.i(TAG, "隐藏悬浮窗")
    }

    private fun releaseView() {
        closeCamera()
        stopPoseTracking()
        // 【新增对比度 UI】窗口根是容器，removeView 要移除容器而不是 tv。
        rootView?.let {
            if (it.parent != null) {
                try { wm.removeView(it) } catch (_: Throwable) {}
            }
        }
        rootView = null
        stepFlag = null
        borderDrawable = null
        borderKey = ""
        rotLayer = null
        hudView = null
        stepBadge = null
        hudColumn = null
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
                // 【5.3.6】画面放进「旋转层」，旋转层再放进窗口根。
                // 旋转层的布局框是「旋转前」的尺寸（长边当宽），View.rotation 一转，
                // 投影正好是窗口大小；root 不裁子 View，圆角仍由 clipToOutline 负责。
                clipChildren = false
                clipToPadding = false
                // HUD 文字层：底部居中，带描边/阴影/半透明深色衬底
                hudView = buildHudView()
                // 【5.6.5】步数徽章：HUD 下方独立一行，独占一个小圆角框
                stepBadge = buildStepBadge()
                hudColumn = LinearLayout(ctx.applicationContext).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    addView(hudView, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(stepBadge, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                }
                val hudLp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = dp(16) }
                rotLayer = FrameLayout(ctx.applicationContext).apply {
                    clipChildren = false
                    addView(texture, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT))
                    // HUD 列放进旋转层，跟随画面一起转（5.4.2）
                    addView(hudColumn, hudLp)
                }
                addView(rotLayer, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER))
                // 【5.6.5 步数奖励】小红旗挂在左上角内侧：贴边太近会被圆角裁掉一截，
                // 所以留 dp(10) 内缩。刻意挂在 root 而不是 rotLayer ——
                // 它要「直立」，跟着画面旋转 90° 就成了一面躺着的旗。
                // 挂在 rotLayer 之后，保证 root 第 0 个子仍是 rotLayer（契约测试依赖这个顺序）。
                stepFlag = StepFlagView(ctx.applicationContext).apply {
                    visibility = View.GONE
                    tag = TAG_STEP_FLAG
                }
                addView(stepFlag, FrameLayout.LayoutParams(
                    dp(STEP_FLAG_WIDTH_DP), dp(STEP_FLAG_HEIGHT_DP),
                    Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(10)
                    topMargin = dp(10)
                })
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
        // 取景与描边都在 View 层：drawable / 变换独立于相机，重复调用是幂等的。
        // 复用旧窗口时宽高已经就绪，这里能立刻生效；新建窗口则在 Surface 就绪时再算一次。
        applyBorder()

        val lp = buildParams()
        // 【5.3.6】把即将挂上去的窗口尺寸显式传进去：挂载前 rootView/texture 的
        // width/height 都还是 0，读它们会直接 return，旋转层就一直停在 MATCH_PARENT。
        applyFraming(lp.width, lp.height)
        try {
            if (root.parent == null) {
                wm.addView(root, lp)
                stage("窗口已挂载: ${style.mode} ${lp.width}x${lp.height} alpha=$renderAlpha contrast=${style.enhanceContrast}/${style.contrastLevel}% torch=${style.torchWithPreview} ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            } else {
                wm.updateViewLayout(root, lp)
                stage("窗口已更新: ${style.mode} ${lp.width}x${lp.height} alpha=$renderAlpha contrast=${style.enhanceContrast}/${style.contrastLevel}% torch=${style.torchWithPreview} ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            }
            armFrameWatchdog()
        } catch (t: Throwable) {
            Log.e(TAG, "❌ 显示悬浮窗失败", t)
            stage("❌ 窗口挂载失败: ${t.message}")
            isShowing = false
            // 没挂上去的 View 必须丢掉：留着它，下一轮 show 会发现
            // 「tv 非空但 parent 为空」又去重建，每 2 秒抖一次，白折腾
            rootView = null
            stepFlag = null
            rotLayer = null
            tv = null
            hudView = null
            stepBadge = null
            hudColumn = null
            surfaceTexture = null
        }
        // 【5.3.1 姿态】窗口挂上之后才开始测姿态：挂之前测出来也改不了形状，
        // 只是让传感器白开一段时间。
        if (isShowing) startPoseTracking()
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
                val v = value.coerceIn(0, 100)
                // 【5.6.7 功耗】同值早退：步数每变一次就会重设 level，不比较的话
                // 每一步都要重算描边/衬底并 invalidate 一次，静止看画面时纯属白烧电。
                if (field == v) return
                field = v
                applyLevel()
            }

        init {
            background = bg
            setTextColor(Color.WHITE)
            // 文案与字号统一由 applyHudStyle() 按 Style 写入，这里不再硬编码
            // 「路况预览中 · 增强对比度」—— 那句实现细节已经删掉。
            isClickable = false
            isFocusable = false
            // 【无障碍】浮层里的文字必须进无障碍树，否则读屏用户听不到 HUD 与步数。
            // 真正对外的节点在 hudColumn（整列合成一句），这里允许被读、但不单独念。
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
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
     * 【5.6.5】步数徽章：和 HUD 文字同款描边/衬底，字号小一档、内边距更紧凑，
     * 视觉上像「挂在标签下面的一个小框」，而不是第二行正文。
     */
    private fun buildStepBadge(): HudTextView = HudTextView(ctx.applicationContext).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, STEP_BADGE_TEXT_SIZE_DEFAULT_SP.toFloat())
        setPadding(dp(10), dp(5), dp(10), dp(5))
        visibility = View.GONE
    }

    /**
     * 【5.6.5 步数奖励】小红旗：≥ 2 万步时挂在窗口左上角，与「国旗红」描边配套。
     *
     * 为什么自绘而不是写 "🇨🇳"：区域旗帜符号在部分 ROM 的 emoji 字体里没有字形，
     * 会退化成「CN」两个字母或豆腐块；它挂在成绩奖励上，显示错比不显示更糟。
     * 自绘只有杆、旗面、五颗星三笔，任何机型都一样。
     *
     * 尺寸小（30×22dp）时不做严格制式比例，只保证「一眼认得出是红旗」。
     */
    private inner class StepFlagView(context: Context) : View(context) {
        private val polePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBDBDBD.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dpf(1.5f)
            strokeCap = Paint.Cap.ROUND
        }
        private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = StepBorderPolicy.COLOR_FLAG_RED
        }
        private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFDE00.toInt()
        }
        private val flagRect = RectF()
        private val star = Path()

        override fun onDraw(canvas: Canvas) {
            val poleX = dpf(2.0f)
            val top = dpf(2.0f)
            val bottom = height.toFloat() - dpf(2.0f)
            canvas.drawLine(poleX, top, poleX, bottom, polePaint)
            // 旗面从杆右侧铺到右边界，底部留出下缘（旗子下半截是空的，像真的飘着）
            flagRect.set(poleX, top, width.toFloat() - dpf(1.0f),
                    height.toFloat() - dpf(6.0f))
            canvas.drawRoundRect(flagRect, dpf(2.0f), dpf(2.0f), flagPaint)
            val fw = flagRect.width()
            val fh = flagRect.height()
            // 一大四小：位置按国旗构图简化
            drawStar(canvas, flagRect.left + fw * 0.24f, flagRect.top + fh * 0.32f, fh * 0.20f)
            val sr = fh * 0.10f
            drawStar(canvas, flagRect.left + fw * 0.46f, flagRect.top + fh * 0.12f, sr)
            drawStar(canvas, flagRect.left + fw * 0.56f, flagRect.top + fh * 0.28f, sr)
            drawStar(canvas, flagRect.left + fw * 0.56f, flagRect.top + fh * 0.54f, sr)
            drawStar(canvas, flagRect.left + fw * 0.46f, flagRect.top + fh * 0.70f, sr)
        }

        /** 五角星：外接圆半径 [r]，内半径按 0.38 取，起点朝上 */
        private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            star.reset()
            val inner = r * 0.38f
            for (i in 0 until 10) {
                val rad = Math.toRadians((-90 + i * 36).toDouble())
                val rr = if (i % 2 == 0) r else inner
                val x = cx + (rr * Math.cos(rad)).toFloat()
                val y = cy + (rr * Math.sin(rad)).toFloat()
                if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
            canvas.drawPath(star, starPaint)
        }
    }

    /**
     * 【5.6.5 步数奖励】服务层下发今日步数（0 = 无数据 / 还不到档）。
     *
     * 只影响**描边颜色**与小红旗，不影响步数徽章的显隐 —— 后者仍由 [setStepText] 控制。
     * 档位变了才写一条日志，逐步调用不刷屏。
     */
    fun setStepRewardEnabled(enabled: Boolean) {
        if (stepRewardEnabled == enabled) return
        stepRewardEnabled = enabled
        applyBorder()
        DiagLog.log("[step] 步数奖励 → " + (if (enabled) "开（描边按步数换色）" else "关（描边保持白色）"))
    }

    fun setTodaySteps(steps: Long) {
        val v = if (steps < 0L) 0L else steps
        if (todaySteps == v) return
        val oldTier = StepBorderPolicy.tier(todaySteps)
        todaySteps = v
        val newTier = StepBorderPolicy.tier(v)
        // 【5.6.7 功耗】档位没变就不碰描边：步数每涨一步都重建一次 GradientDrawable
        // 是纯浪费。颜色只在跨过 3000/8000/20000/30000 时才会变。
        if (newTier != oldTier) {
            applyBorder()
            DiagLog.log("[step] 描边奖励档位 | 今日=" + TodayStepPolicy.format(v)
                    + " → " + StepBorderPolicy.tierName(newTier))
        }
    }

    /** 按当前 [todaySteps] 决定小红旗显隐；[allowed]=false 表示当前模式不放奖（全屏 / 无描边） */
    private fun applyStepFlag(allowed: Boolean) {
        val flag = stepFlag ?: return
        val show = allowed && StepBorderPolicy.showFlag(todaySteps, stepRewardEnabled)
        val vis = if (show) View.VISIBLE else View.GONE
        if (flag.visibility != vis) flag.visibility = vis
    }

    /**
     * 【5.6.5】服务层下发今日步数文案（null / 空串 = 隐藏）。
     *
     * 只存值并立即刷新样式，不重建视图、不碰相机；窗口若还没挂上，
     * 下次 [apply] 里的 [applyHudStyle] 会把它一起应用。
     */
    fun setStepText(text: String?) {
        if (stepText == text) return
        stepText = text
        applyHudStyle()
    }

    /** 把 [stepText] 应用到徽章：可见性、文案、字号（跟随 HUD 字号小一档）与描边强度 */
    private fun applyStepBadge() {
        val badge = stepBadge ?: return
        val text = stepText
        if (text.isNullOrBlank()) {
            if (badge.visibility != View.GONE) badge.visibility = View.GONE
            return
        }
        if (badge.visibility != View.VISIBLE) badge.visibility = View.VISIBLE
        if (badge.text?.toString() != text) badge.text = text
        val sp = (style.hudTextSizeSp - 1).coerceAtLeast(STEP_BADGE_TEXT_SIZE_MIN_SP)
        val currentSp = badge.textSize / ctx.resources.displayMetrics.scaledDensity
        if (Math.abs(currentSp - sp) > 0.5f) {
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        }
        badge.level = if (style.enhanceContrast) style.contrastLevel else 0
    }

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
        // 【5.6.5】文字与步数是同一个列：文字关掉时整列一起收，不留一个孤零零的步数框。
        if (text.isNullOrBlank()) {
            hud.visibility = View.GONE
            stepBadge?.visibility = View.GONE
            hudColumn?.visibility = View.GONE
            // 【无障碍】整列收起时清掉语义，避免读屏聚焦到已隐藏的旧文案
            hudColumn?.contentDescription = null
            return
        }
        hud.visibility = View.VISIBLE
        hudColumn?.visibility = View.VISIBLE
        if (hud.text?.toString() != text) hud.text = text
        val currentSp = hud.textSize / ctx.resources.displayMetrics.scaledDensity
        if (Math.abs(currentSp - style.hudTextSizeSp) > 0.5f) {
            hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.hudTextSizeSp.toFloat())
        }
        hud.level = if (style.enhanceContrast) style.contrastLevel else 0
        applyStepBadge()
        syncHudA11y()
    }

    /**
     * 【无障碍】把 HUD 与步数徽章合成**一个**无障碍节点，并在必要时主动播一次。
     *
     * · 两行各念一句会被读屏拆成「路况预览中」「今日 12 步」两条碎片，所以整列挂
     *   contentDescription，子 TextView 标 NO 让它们退出无障碍树。
     * · 主动播报要节流（见 [HudA11yPolicy]）：步数每变一次就播 = 走路时读屏被刷屏。
     * · 窗口没显示时不播，避免退出后还在念。
     */
    private fun syncHudA11y() {
        val col = hudColumn ?: return
        val desc = HudA11yPolicy.compose(
            if (col.visibility == View.VISIBLE) style.hudText else null,
            if (stepBadge?.visibility == View.VISIBLE) stepText else null
        )
        if (desc.isBlank()) {
            HudA11yBinder.clear(col)
            return
        }
        HudA11yBinder.bind(col, hudView, stepBadge, desc)

        val now = System.currentTimeMillis()
        val stepsDelta = (todaySteps - stepsAtLastHudAnnounce).coerceAtLeast(0L)
        if (isShowing && HudA11yPolicy.shouldAnnounce(
                lastHudDesc, desc, now, lastHudAnnounceTs, stepsDelta
            )
        ) {
            col.announceForAccessibility(desc)
            lastHudDesc = desc
            lastHudAnnounceTs = now
            stepsAtLastHudAnnounce = todaySteps
        }
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
    private fun applyFraming(windowW: Int = 0, windowH: Int = 0) {
        val view = tv ?: return
        val layer = rotLayer ?: return
        // 窗口尺寸的四个来源，按可信度排序：
        //   1) 调用方显式传进来的（apply() 里刚从 buildParams 算出来，挂载前唯一可信值）
        //   2) 已经布局完成的 rootView
        //   3) 上一次算对并缓存的 lastWindow
        //   4) 视图自身
        // 【5.4.1】宽高同源：原实现 w、h 各走一个独立 when，会出现 w 取自 rootView、
        // h 回退到 lastWindow 的错配；横竖切换中间态一旦错配出 w==h，就会被判竖屏、
        // 写回 lastWindow 并固化成正方形。现在选中第一个「宽高都有效」的来源后一并取，
        // 优先级与 w<=0||h<=0 的防御语义不变。
        val rv = rootView
        val (w, h) = when {
            windowW > 0 && windowH > 0 -> windowW to windowH
            rv != null && rv.width > 0 && rv.height > 0 -> rv.width to rv.height
            lastWindowW > 0 && lastWindowH > 0 -> lastWindowW to lastWindowH
            view.width > 0 && view.height > 0 -> view.width to view.height
            else -> 0 to 0
        }
        if (w <= 0 || h <= 0) return

        // 【5.4.1 正方形防御】稳态窗口比例固定（小窗 150:260、全屏真实 bounds），
        // w==h 只可能是切换中间态的伪尺寸。若放它继续：landscapeWindow=w>h 判 false →
        // deg=0 不交换 → boxW==boxH；openCamera 的 lastWindowH>=lastWindowW（含等号）
        // 还会把缓冲也设成正方形，写回 lastWindow 后久不自愈。这里直接跳过本帧，
        // 等下一次带稳定尺寸的回调重算；DiagThrottle 低频日志留痕，真机可确认命中次数。
        if (w == h) {
            try {
                DiagLog.logThrottled("cam.square", "w=h=$w", "[cam] 正方形中间态已拦截 w=h=$w")
            } catch (_: Throwable) {
            }
            return
        }

        // 【5.4.5 方向锁定】小窗已有锁定构图：成组幂等重放首帧快照（窗口框/角度/HUD/
        // 锚点/缩放/buffer 一次恢复），跳过下面全部实时推导。正方形防御已在上方通过。
        // 重放逻辑放在独立方法里，applyFraming 的实时计算路径一字未改。
        if (style.mode == MODE_SMALLWINDOW && smallLock != null) {
            replayLockedComposition(view, layer)
            return
        }

        lastWindowW = w
        lastWindowH = h

        // 方向还没算过（openCamera 之前那次布局）就不转：拿窗口尺寸冒充画面朝向，
        // 只会转出一张歪图，等 resolveOrientation 之后会再算一次。
        //
        // 【5.3.7】竖条窗口一律不转 —— 这就是「没动路况预览之前」（4.8.0）的行为：
        // 不旋转、不变换，相机缓冲直接按窗口尺寸出，铺满即可。当时用户从没抱怨过
        // 竖屏画面，是 5.3.0 为了修横屏才把整套旋转引进来，结果把竖屏一起带歪了。
        // 现在只有横条窗口才走旋转这条路。
        //
        // 【5.4.14】用户以有头尾的鱼给出精确判据：横条小窗画面顺时针转 90°，鱼头朝右。
        // 5.4.8 的问题不是"转90°"本身错，而是 alignToWindow 把 90 折成 180 导致倒立。
        // 本轮小窗横条绕过该规整直接给 deg=90；竖条仍 0、全屏横条不受本规则控制。
        // alignToWindow(x, true) 输出域恒为 {0,180}，此处仅作全屏横条兜底。
        val landscapeWindow = w > h
        val deg = when {
            appliedRotDeg < 0 -> 0
            !landscapeWindow -> 0
            // 【5.4.14】小窗横条：画面顺时针转 90°（鱼头朝右），绕过 alignToWindow 的{0,180}输出域。
            // 全屏横条仍走原 alignToWindow，保证"最大化/分屏不受本规则控制"。
            //
            // 【5.6.0 真机修正】这里从 90 改成 270。用户实测：手机横屏时只有「左 270°」
            // 是正立的，90 会上下颠倒。5.4.14 的 90 来自一张「鱼头朝右」的截图，但
            // 那台机器后摄是 SENSOR_ORIENTATION=270 的横装模组，与本机相反；
            // 把机身转到横屏后，传感器方向与窗口形状的组合只留下 270 这一个正解。
            // 因此「自动（跟随系统）」档的横条小窗值统一落到 270，手动档仍可覆盖。
            style.mode == MODE_SMALLWINDOW -> LANDSCAPE_SMALLWINDOW_DEG
            else -> PreviewOrientationPolicy.alignToWindow(appliedRotDeg, true)
        }

        // 【5.4.15 手动转正】用户显式选了方向时，横条小窗直接用该角度作最终展示角；
        // 竖条窗口恒 0（写死契约）、全屏 modal 不套用，两种情况都沿用什么都不改的 deg。
        // 注意：上面 deg when 的四分支文本/token 与 5.4.14 注释被契约逐字锁死，一行不动。
        val manualFacing = ManualOrientation.normalizeFacing(style.rearCameraFacing)
        val finalDeg = if (style.mode == MODE_SMALLWINDOW && landscapeWindow
            && manualFacing != ManualOrientation.FACING_AUTO
        ) ManualOrientation.facingDegrees(manualFacing) else deg
        // 用局部同名变量遮蔽 when 的 deg：下方既有字面（layer.rotation = deg 等）原样保留，
        // 实际吃到的是手动覆盖后的 finalDeg；角度靠变量随动，不写死 90/-90。
        run {
            val deg = finalDeg

            // 旋转层用「旋转前」的长宽摆：90/270 时长边当宽。
            // 这样 View.rotation 转 90° 之后的投影，正好是 w×h 的窗口。
            val swap = deg == 90 || deg == 270
            val boxW = if (swap) h else w
            val boxH = if (swap) w else h

            val cur = layer.layoutParams as? FrameLayout.LayoutParams
            if (cur == null || cur.width != boxW || cur.height != boxH) {
                layer.layoutParams = FrameLayout.LayoutParams(boxW, boxH, Gravity.CENTER)
                rootView?.requestLayout()
            }
            layer.rotation = deg.toFloat()
            // 5.4.3 HUD 位置跟随旋转层换边，但文字反向旋转保持正立可读
            hudColumn?.rotation = -deg.toFloat()
            // 矩阵一律不碰。系统把 buffer 拉伸铺满布局框，铺满比例由
            // (boxW/srcW, boxH/srcH) 决定；源与框的比例本来就接近（1920:1080 对 845:488），
            // 2~3% 的非等比拉伸肉眼看不出来，换来的是一条没有任何坐标歧义的渲染路径。
            view.setTransform(null)
            applyFramingZoom(view, boxW, boxH, deg, landscapeWindow)
        }
    }

    /**
     * 【5.4.5 方向锁定】把首帧快照成组重放回当前 View。
     *
     * 只做「赋值恢复」，不重新推导角度/尺寸/取景：这正是幂等的保证——
     * onSurfaceTextureSizeChanged 等回调每次都调 applyFraming，走到这里
     * 无论姿态转了几圈，写回去的都是同一份首帧值。
     *
     * 9 组字段一次性恢复；HUD 反向角用快照里的 hudRot（= -deg），保证
     * layer.rotation + hudView.rotation 恒为 0（5.4.3 净朝向不变）。
     * buffer 只恢复字段值供日志/决策读取，真正的 setDefaultBufferSize 仍在
     * openCamera 唯一调用点，用锁定值下发（全文仍仅一处）。
     */
    private fun replayLockedComposition(view: TextureView, layer: FrameLayout) {
        val lock = smallLock ?: return
        lastWindowW = lock.winW
        lastWindowH = lock.winH
        appliedRotDeg = lock.rotDeg

        val cur = layer.layoutParams as? FrameLayout.LayoutParams
        if (cur == null || cur.width != lock.boxW || cur.height != lock.boxH) {
            layer.layoutParams = FrameLayout.LayoutParams(lock.boxW, lock.boxH, Gravity.CENTER)
            rootView?.requestLayout()
        }
        layer.rotation = lock.layerRot
        hudColumn?.rotation = lock.hudRot
        view.setTransform(null)
        view.pivotX = lock.pivotX
        view.pivotY = lock.pivotY
        view.scaleX = lock.scaleX
        view.scaleY = lock.scaleY

        actualBufferW = lock.bufferW
        actualBufferH = lock.bufferH
    }

    /**
     * 【5.4.5 方向锁定】首帧建锁：在 openCamera 内方向已算真值、构图已落盘、buffer
     * 已决策之后调用，把这一帧成组冻结为 [smallLock]。
     *
     * 捕获时机是唯一正确点：
     *  · apply():917 那次 applyFraming 跑在 resolveOrientation 之前，appliedRotDeg
     *    还是 -1，锁的是「未转正」的假构图；
     *  · 这里 appliedRotDeg 已是真值（>= 0），applyFraming 已按它把角度/框/锚点/缩放
     *    全部落盘，buffer 也刚算完，读回即可，不重复任何推导。
     *
     * 四个守卫缺一不可：已有锁不重建（幂等）、只锁小窗、方向已定型、非正方形。
     */
    private fun lockFirstFrame() {
        if (smallLock != null) return
        if (style.mode != MODE_SMALLWINDOW) return
        if (appliedRotDeg < 0) return
        val w = lastWindowW
        val h = lastWindowH
        // 正方形防御之后才建锁：w==h 是切换中间态，锁死会永久不自愈
        if (w <= 0 || h <= 0 || w == h) return
        val layer = rotLayer ?: return
        val view = tv ?: return
        val lp = layer.layoutParams as? FrameLayout.LayoutParams ?: return
        smallLock = LockedComposition(
            rotDeg = appliedRotDeg,
            winW = w, winH = h,
            deg = layer.rotation.toInt(),
            boxW = lp.width, boxH = lp.height,
            layerRot = layer.rotation,
            hudRot = hudColumn?.rotation ?: 0f,
            pivotX = view.pivotX, pivotY = view.pivotY,
            scaleX = view.scaleX, scaleY = view.scaleY,
            bufferW = actualBufferW, bufferH = actualBufferH,
            framingPct = style.framingPct,
            rotationMode = style.rotationMode
        )
        diag(
            "🔒 方向锁定已建立 | 窗口=" + (if (w > h) "横条" else "竖条") + " " + w + "x" + h
                + " 实转=" + appliedRotDeg + "° 缓冲=" + actualBufferW + "x" + actualBufferH
        )
        // 【5.4.6 方案B】记录本次建锁时的系统屏幕朝向，作为「横竖屏真翻转」判据基准。
        // 首帧竖屏建锁记 false；系统切横屏 → onDisplayChanged 比对为 true → 放行一次重算并重锁。
        lockScreenLandscape = lastKnownScreenLandscape ?: screenIsLandscape()
    }

    /**
     * 竖屏「折中填充」缩放系数（5.4.0）：保留约一半水平视野，同时把上下黑边收窄。
     *
     * 几何推导（buffer 488×845 竖条槽，横画幅 16:9 等比 letterbox 居中）：
     *   · 横画幅内容进 488 宽 → 内容高 ≈ 488×9/16 ≈ 274，上下各留 ≈(845−274)/2 ≈ 285 黑边
     *     —— 这正是当前「放大=1.00x」的大留白（调查报告 A2）。
     *   · 以中心为支点等比放大 Z：内容高 = 274×Z；水平保留率 = 可见宽 488 / 放大后宽 488Z = 1/Z。
     *   · Z=2.0 → 水平保留 50% 视野（少裁），上下窄边 (845−274×2)/2 ≈ 148px ≈ 17%，即折中方案。
     *   · 对照：Z=3.08 才是「铺满裁左右」（水平只剩 32%），Z=1.0 是大留白；折中取中间 2.0。
     *
     * 纯函数：只按窗口形状门控（确为竖条才生效），缩放值固定为折中常数，
     * 不读取 HAL / 传感器等运行时状态，不做猜测。16:9 是相机横画幅输出的实证比例。
     */
    private fun portraitCompromiseZoom(boxW: Int, boxH: Int): Float {
        if (boxH <= boxW) return 1f   // 防御：横屏不会走到这里，保持 1:1
        return 2.0f
    }

    /**
     * 取景（[Style.framingPct]）：只保留画面「靠近脚下」的那一段，放大铺满。
     *
     * 在 5.3.6 之前这一步是混在旋转矩阵里算的，现在旋转归 View，它退化成一次
     * 纯缩放 + 换支点。难点在支点：View 的 pivot 同时被 rotation 和 scale 使用，
     * 但**旋转只作用在 [rotLayer] 上、缩放只作用在 [TextureView] 上**，两者不在
     * 同一层，所以可以各用各的支点，互不干扰。
     *
     * 方向换算（布局框是未旋转坐标系，y 向下）：
     *   · 0°   屏幕上边 = 框上边   → 保留下半 → 支点靠下
     *   · 180° 屏幕上下颠倒        → 保留屏幕下半 = 框上半 → 支点靠上
     *   · 90°  x' = -y，屏幕下方 = 框右侧 → 支点靠右
     *   · 270° x' = y， 屏幕下方 = 框左侧 → 支点靠左
     *
     * 【5.4.14】未取景（f<=0）的折中填充门控：用窗口真实朝向 [landscapeWindow] 判定，
     * 而非旋转后的 box 形状（boxH > boxW）。deg=90 会把横条窗的框翻成竖条
     * （boxH>boxW 成立），只看框形状会误判成竖条窗而放大 2 倍、视野砍半。
     * 只有真正竖条窗（!landscapeWindow）才保留 2.0 折中填充；横条窗（deg=0/90）一律 1:1。
     */
    private fun applyFramingZoom(view: TextureView, boxW: Int, boxH: Int, deg: Int, landscapeWindow: Boolean) {
        val f = style.framingPct.coerceIn(0, 100)
        if (f <= 0) {
            // 【5.4.0 折中填充】竖条窗口 + 未主动取景：横画幅内容等比放大 Z，以中心为支点，
            // 水平保留约 50% 视野、上下黑边收窄到约 17%（几何推导见 portraitCompromiseZoom）。
            // 【5.4.14】用窗口真实朝向判定，而非旋转后 box 形状：deg=90 翻框后横条窗虽 boxH>boxW，
            // 仍是横条窗，走 1:1；只有真竖条窗（!landscapeWindow）才折中填充。f>0 分支在下方，未动。
            if (!landscapeWindow && boxH > boxW) {
                // 【5.7.2 超广角不裁】选中的是真超广角镜头（等效焦距 ≤ 主摄 0.8 倍）时，
                // 任何放大都等于把广角宽度丢回去：15.6mm 配 2.04 倍 = 31.8mm，比系统相机
                // 0.6x（15.6mm）窄一倍，用户看到的就是「选着超广角、画面还是主摄」。
                // 所以这里**整幅给全**（1:1，水平 100% 视野），上下留白交给取景滑杆补：
                // 取景 >0 走下面 f>0 分支，照常按用户设定裁切放大。
                if (lastPickRealUltrawide && style.cameraLens == CameraLensPolicy.MODE_ULTRAWIDE) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.pivotX = boxW / 2f
                    view.pivotY = boxH / 2f
                    DiagLog.logThrottled(
                        "framing.ultrawide", "dump",
                        "[cam] 超广角完整取景：不做折中裁切（1:1），保留整幅横向视野"
                            + "（真机日志里 2.04x 会把 15.6mm 放大成约 31.8mm，等于白开广角）",
                        5_000L, 120_000L
                    )
                    return
                }
                // 【5.7.1】主摄等非广角镜头：有真实源尺寸时按源宽高比算（16:9 ≈2.04 与旧值等价；
                // 4:3 ≈1.65）；源尺寸未知（极端兜底/单测）时退回 5.4.0 的固定 2.0。
                val z = if (previewSrcW > 0 && previewSrcH > 0) {
                    compromiseZoomFor(boxW, boxH, previewSrcW, previewSrcH)
                } else {
                    portraitCompromiseZoom(boxW, boxH)
                }
                view.pivotX = boxW / 2f
                view.pivotY = boxH / 2f
                view.scaleX = z
                view.scaleY = z
                return
            }
            view.scaleX = 1f
            view.scaleY = 1f
            view.pivotX = boxW / 2f
            view.pivotY = boxH / 2f
            return
        }
        val k = f / 200f                  // 裁掉的比例：0 → 不裁，0.5 → 只留下半
        val zoom = 1f / (1f - k)          // 裁完再放大才能铺满
        view.pivotX = boxW / 2f
        view.pivotY = boxH / 2f
        when (((deg % 360) + 360) % 360) {
            90 -> view.pivotX = boxW * (1f - k / 2f)
            270 -> view.pivotX = boxW * (k / 2f)
            180 -> view.pivotY = boxH * (k / 2f)
            else -> view.pivotY = boxH * (1f - k / 2f)
        }
        view.scaleX = zoom
        view.scaleY = zoom
    }

    /**
     * 当前朝向（0/90/180/270）。
     *
     * **优先取重力姿态**：用户开着旋转锁定时 `Display.getRotation()` 恒为 0，
     * 手机横过来系统不转、预览也不转，画面就侧躺。只有拿不到传感器样本时
     * （首帧、没有加速度计、一直平放）才退回屏幕旋转，那只是「比没有强」的兜底。
     */
    private fun currentDisplayDegrees(): Int {
        if (poseDeg != DevicePosePolicy.NO_SAMPLE) return poseDeg
        return try {
            @Suppress("DEPRECATION")
            PreviewOrientationPolicy.displayDegrees(wm.defaultDisplay.rotation)
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * 当前窗口是不是横条。
     *
     * 窗口形状与画面旋转**必须用同一个判定结果**：一个来自姿态、一个来自
     * 传感器方向公式，两处各调一次，中间姿态一变就会各说各话，画面就差 90°。
     * 所以只留这一个入口，[buildParams] 与 [refreshRotation] 都调它。
     *
     * 【5.4.11 姿态滞后修复】系统方向优先：一旦收到可靠系统方向回调，一律以它为准
     * （横屏→横条 / 竖屏→竖条），滞后姿态不得盖过已明确的竖屏；姿态只在尚无
     * 回调（首帧）时兜底，与系统方向参考角的判定哲学同源。
     *
     * 【5.6.1】上面这条「系统方向优先」被反转成**姿态优先**，理由见 [liveWindowLandscape]：
     * 系统方向在游戏与旋转锁定场景下会撒谎，而重力不会。5.4.11 要治的「姿态滞后盖过
     * 已明确的竖屏」由 PoseTracker 的 45° 容忍带 + 驻留时间本身解决，不再需要靠优先级压制。
     */
    private fun isLandscapeWindowNow(): Boolean {
        // 【5.4.15 手动窗口形状】只作用于小窗：全屏 modal 不套任何手动覆盖。
        // 按当前（实时）横/竖屏选对应那一组配置；显式选了横/竖就用它定窗口框形状，
        // auto 则返回 null 落回下方原判据，行为与 5.4.14 完全一致。
        if (style.mode == MODE_SMALLWINDOW) {
            val shapeCfg = if (liveWindowLandscape()) {
                style.landscapeWindowShape
            } else {
                style.portraitWindowShape
            }
            val manual = ManualOrientation.shapeIsLandscape(shapeCfg)
            if (manual != null) return manual
        }
        return if (PreviewOrientationPolicy.normalizeMode(style.rotationMode)
            == PreviewOrientationPolicy.MODE_FIXED
        ) false
        else liveWindowLandscape()
    }

    /**
     * 【5.6.4 方向严格跟随界面】窗口该不该是横条 —— 只认**系统界面方向**。
     *
     * 【5.6.1 的并集判据已被真机证伪，这里反转】
     * 5.6.1 把姿态并入判据（「任一为横即横」），理由是游戏场景下 config 可能仍是 portrait。
     * 真机日志（2026-11-22 15:44:35~15:50:05，Redmi / HyperOS 4）证明这个并集会被**手持姿态**
     * 骗：竖屏界面下姿态报 270°，窗口被建成横条 845x488 并锁死，
     * `applyPoseChange` 拿同一份判据比较也永远是「没翻转」，于是**再也回不到竖条**，
     * 用户整整 5 分钟只能对着一个横条小窗，直到手动重开服务才恢复。
     * 「手机转两下就变成横条、还倒不回来」正是这个机制的产物。
     *
     * 用户对方向的定义也很明确：**横屏就是横屏，竖屏就是竖屏**——横屏玩游戏/看视频用横条，
     * 竖屏用竖条，两者不能因为手的姿势互相串台。窗口贴在屏幕上，用户看到的是界面，
     * 不是重力向量。
     *
     * 所以判据改回系统方向，且取两路**系统**信号（都与手怎么拿无关）：
     *  · [screenIsLandscape]：实时 Configuration；
     *  · [displayRotationLandscape]：Display.rotation 为 90/270。
     * 任一为横即横，两路同时竖才竖 —— 游戏/视频这类真横屏场景，这两路至少有一路会跟着变。
     * 姿态不参与窗口形状，只继续参与**画面旋转角**（[referenceDegrees]/[refreshRotation]），
     * 保证竖条里的画面不躺。
     */
    private fun liveWindowLandscape(): Boolean {
        return screenIsLandscape() || displayRotationLandscape()
    }

    /**
     * 系统屏幕的物理旋转是否为横（rotation 90/270）。
     *
     * 与 [screenIsLandscape] 互补：Configuration 描述「界面按什么方向排」，
     * Display.rotation 描述「屏幕相对机身转了多少」。强制横屏的 Activity 两者都会变，
     * 而个别自绘 Surface 的游戏可能只改其中一路，所以取并集。
     * 两者都与「手怎么拿」无关，因此不会出现 5.6.1 那种「手一横、窗口就再也回不来」。
     */
    private fun displayRotationLandscape(): Boolean = try {
        val r = wm.defaultDisplay.rotation
        r == android.view.Surface.ROTATION_90 || r == android.view.Surface.ROTATION_270
    } catch (_: Throwable) {
        false
    }

    /**
     * 【5.4.6 方案B】系统屏幕当前是否横屏。
     *
     * 读 Configuration.orientation —— 与 WalkPreviewService.onConfigurationChanged 判 rotated 同源；
     * 且 onConfigurationChanged 里 super 已先行更新 resources，此处拿到的就是新朝向。
     * 与 [isLandscapeWindowNow]（姿态/旋转，决定窗口横竖条）刻意分开：
     * 后者管「窗口形状」，本函数只管「系统有没有真的翻转」，作为放行重算 + 重锁的判据。
     */
    private fun screenIsLandscape(): Boolean =
        ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /**
     * 算旋转角时用的「参考朝向」。
     *
     * 「锁定竖屏」档要把它钉死成 0：此时窗口不跟着转，画面也应当按竖屏姿势摆，
     * 否则横着拿会出现「竖条窗口里放一张横图」。
     */
    private fun referenceDegrees(): Int =
        if (PreviewOrientationPolicy.normalizeMode(style.rotationMode)
            == PreviewOrientationPolicy.MODE_FIXED
        ) 0
        else if (lastKnownScreenLandscape != null)
            (if (lastKnownScreenLandscape == true) 90
             else DevicePosePolicy.normalize(currentDisplayDegrees()))
        else currentDisplayDegrees()

    /**
     * 只重算「该转多少度」，不动缓冲、不重建会话。
     *
     * 屏幕旋转与用户切换方向模式都走这里：源画面没变，只是摆法变了。
     */
    private fun refreshRotation() {
        // 【5.4.5 方向锁定】小窗已锁定构图：旋转角冻结为首帧值，不被姿态/模式变化改写。
        // 进全屏时 show() 已先把 style.mode 切成 FULLSCREEN，守卫自然放行，全屏照旧随方向旋转。
        if (style.mode == MODE_SMALLWINDOW && smallLock != null) {
            appliedRotDeg = smallLock!!.rotDeg
            return
        }
        val refDeg = referenceDegrees()
        val landscape = isLandscapeWindowNow()
        val rawDeg = PreviewOrientationPolicy.applyMode(
            PreviewOrientationPolicy.rotationDegrees(sensorOrientation, refDeg, false),
            style.rotationMode
        )
        // 【5.3.4】原始值与窗口形状对齐。窗口竖条却算出「不交换宽高」（0/180），
        // 就是「竖着拿、画面横躺」——真机上用户得歪头看路。见 alignToWindow 注释。
        val newDeg = PreviewOrientationPolicy.alignToWindow(rawDeg, landscape)
        if (newDeg == appliedRotDeg) return
        appliedRotDeg = newDeg
        diag(
            "方向切换 → 实转 " + newDeg + "°（传感器 " + sensorOrientation
                + "° 姿态 " + refDeg + "° 模式 " + style.rotationMode
                + " 窗口=" + (if (landscape) "横条" else "竖条")
                + (if (rawDeg != newDeg) " 校正=${rawDeg}°→${newDeg}°" else "") + "）"
        )
    }

    /**
     * 屏幕方向变化后的重排（由 WalkPreviewService.onConfigurationChanged 调用）。
     *
     * 刻意不检查任何用户开关：方向错了就是看不了路，不能因为「智能方案没开」
     * 而不处理。全屏窗口要换宽高、小窗高度上限要跟着屏高变、矩阵基准也变了。
     */
    fun onDisplayChanged(screenLandscape: Boolean) {
        // 【5.4.7 真机修复】先刷新可靠朝向缓存：判据与重锁基准都改用回调下传的值。
        lastKnownScreenLandscape = screenLandscape
        if (!isShowing || rootView?.parent == null) return
        // view 尺寸检查必须在清锁之前：确保能走完整条重算 + 重锁，绝不出现「清了锁却提前 return」。
        val view = tv ?: return
        if (view.width <= 0 || view.height <= 0) return

        // 【5.4.6 方案B】系统横竖屏真翻转时，放开被 5.4.5 一刀切锁死的整条重算链路。
        // 与「姿态晃动」的关键区分：姿态走 applyPoseChange（:568 仍短路），系统方向走这里。
        // 判据 = 当前系统朝向 != 建锁时记录的朝向；同一朝向内的重复回调一律维持锁定。
        // 防重：orientationSwitchInProgress 保证一次翻转只放一次、重锁一次，不缺行。
        var willRelock = false
        if (style.mode == MODE_SMALLWINDOW && smallLock != null) {
            val flipped = lockScreenLandscape != null && lockScreenLandscape != screenLandscape
            if (flipped && !orientationSwitchInProgress) {
                orientationSwitchInProgress = true
                willRelock = true
                // 【5.4.10 诊断 D1】只在真翻转放行路径打，且必须赶在下方清锁之前读旧锁：
                // 记录入口各字段真值，坐实 flipped 是否放行、判据是否不同源（次因 S）。
                stage("方向诊断 IN screenLandscape=$screenLandscape lastKnown=$lastKnownScreenLandscape lockSL=$lockScreenLandscape flag=$orientationSwitchInProgress lock=${smallLock?.winW}x${smallLock?.winH}@${smallLock?.deg} root=${rootView?.width}x${rootView?.height} pose=$poseDeg")
                // 清旧锁：让下方 refreshRotation / buildParams / applyFraming / buffer 的
                // 5.4.5 守卫（均判 smallLock != null）运行期自然失效，走完整实时重算——
                // 窗口随方向变横条/竖条、角度按 alignToWindow 重算、buffer 按新窗口重算。
                smallLock = null
            }
            // 同朝向（姿态/深色模式/宽度变更/重复 configuration）时不清锁，落回下方原短路。
        }

        // 【5.4.8 防重标志兜底】：上面一旦放行置位标志，下面整条重算/重锁都必须保证标志复位。
        // refreshRotation / applyFraming / lockFirstFrame 任一环抛异常，finally 都会清掉标志，
        // 否则它会永久残留 true，此后所有系统方向翻转被 !orientationSwitchInProgress 吞掉。
        try {
            // 5.4.5 原短路文本原样保留：真翻转已清锁 → 穿透到整条重算；同朝向仍有锁 → 维持锁定。
            if (style.mode == MODE_SMALLWINDOW && smallLock != null) return

            // ↓ 5.4.5 之前的原样整条重算路径，一字未改（全屏每次都走；小窗仅真翻转时走）。
            refreshRotation()
            // 【5.4.6 方案B 真机校准】仅横屏、且本次是方向翻转重算时叠加单一常量（默认 0 = 不叠加，
            // 与历史正立帧一致）。带 MODE_SMALLWINDOW 守卫，全屏路径不触碰。
            if (willRelock && style.mode == MODE_SMALLWINDOW && LANDSCAPE_UPRIGHT_ROT_DEG != 0
                && isLandscapeWindowNow()
            ) {
                appliedRotDeg = (appliedRotDeg + LANDSCAPE_UPRIGHT_ROT_DEG + 360) % 360
                diag("🔧 横屏正立方向校准 +" + LANDSCAPE_UPRIGHT_ROT_DEG + "° → appliedRotDeg=" + appliedRotDeg)
            }
            // 排序不能反：先重排窗口，布局完成后 SizeChanged 会再算一次矩阵；
            // 这里先按旧尺寸算一版，避免旋转瞬间出现一帧拉伸画面。
            // 【5.4.10 修复】用 buildParams() 的「清锁后目标尺寸」喂 framing：updateViewLayout 异步，
            // 此刻 view/root 宽高仍是旧横条或 w==h 中间态；smallLock 已在本轮翻转分支（上方）清空，
            // buildParams 走 :1782-1783 返回新朝向目标尺寸，applyFraming 带参优先级 :1174 直接采用，
            // 绕开陈旧尺寸 → lastWindow 写成目标竖条 → lockFirstFrame 建成竖条锁。
            // 与 :1014-1017 挂载先例同构；buildParams 为纯函数、空 root 调用也安全，
            // applyFraming 仍在 let 外无条件执行，保留「空 root 不跳过 framing」原语义。
            val lp = buildParams()
            rootView?.let { root ->
                try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {}
                // 5.4.3 旋转重建后重算圆角轮廓：post 等 updateViewLayout 的异步布局完成、view 宽高刷新
                root.post { root.invalidateOutline() }
            }
            applyFraming(lp.width, lp.height)
            // 【5.4.10 诊断 D2】只在真翻转路径打，坐实 framing 是否读到目标尺寸、是否命中 w==h。
            if (willRelock) {
                stage("方向诊断 FRAMED lastWindow=${lastWindowW}x${lastWindowH} appliedRot=$appliedRotDeg layer=${rotLayer?.layoutParams?.width}x${rotLayer?.layoutParams?.height}@${rotLayer?.rotation}")
            }

            if (willRelock) {
                // 重算完成后在新朝向下重新建锁：新朝向内的后续姿态晃动仍被 applyPoseChange:568 锁死。
                // 这是不退回「转一次缺一行」的关键——不能只放角度而不重锁。
                lockFirstFrame()
                // 【5.4.10 诊断 D3】只在真翻转路径打，坐实新锁形状（横条/竖条），
                // 区分「没重锁」与「重锁成横条」。另起诊断文本，不改既有锁定摘要日志 token。
                stage("方向诊断 LOCKED lock=${smallLock?.winW}x${smallLock?.winH}@${smallLock?.deg} rotDeg=${smallLock?.rotDeg} lockSL=$lockScreenLandscape")
                orientationSwitchInProgress = false
                diag("🔄 系统方向翻转已重算并重锁 | 窗口="
                    + (if ((smallLock?.winW ?: 0) > (smallLock?.winH ?: 0)) "横条" else "竖条")
                    + " deg=" + (smallLock?.deg ?: -1))
            }
        } finally {
            // 只有本次真正放行过（置位过标志）才复位：同朝向/全屏回调 willRelock=false，
            // 不能在 finally 里误清掉另一次正在进行的翻转所持有的防重标志。
            if (willRelock) orientationSwitchInProgress = false
        }
    }

    /**
     * 【5.4.15 手动方向立即生效】清锁 → 重算 → 重锁。
     *
     * 复用 onDisplayChanged 真翻转路径的核心顺序：清 smallLock 让 5.4.5 的守卫失效，
     * 再 refreshRotation → buildParams → applyFraming → lockFirstFrame 走完整实时重算。
     * 刻意不碰 orientationSwitchInProgress 防重标志与 D1-D3 诊断——手动改值不是系统翻转，
     * 不该被防重吞掉，也不该伪造方向诊断日志。
     *
     * 全屏 modal 不套任何手动覆盖，只补一次角度刷新，不清锁（全屏本就没有小窗锁）。
     */
    private fun reapplyManualOrientation() {
        if (style.mode != MODE_SMALLWINDOW) {
            refreshRotation()
            applyFraming()
            return
        }
        val view = tv ?: return
        // 尺寸检查必须在清锁之前：确保清了锁就能走完整条重算，绝不「清了锁却提前 return」。
        if (view.width <= 0 || view.height <= 0) return
        smallLock = null
        refreshRotation()
        val lp = buildParams()
        rootView?.let { root ->
            try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {}
        }
        applyFraming(lp.width, lp.height)
        lockFirstFrame()
        diag("🔄 手动方向已重算并重锁 | 窗口="
            + (if ((smallLock?.winW ?: 0) > (smallLock?.winH ?: 0)) "横条" else "竖条")
            + " 转正=" + ManualOrientation.normalizeFacing(style.rearCameraFacing))
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
            borderDrawable = null
            borderKey = ""
            applyStepFlag(false)
            return
        }
        // 【5.6.5 步数奖励】描边颜色跟今日步数走（<3000 仍是原来的白色）。
        // 颜色集中在 [StepBorderPolicy]，这里只问「今天该是什么色」，不在渲染层写阈值。
        val color = StepBorderPolicy.borderColor(todaySteps, stepRewardEnabled)
        val key = style.borderDp.toString() + "#" + color
        val d = borderDrawable
        if (d == null || key != borderKey) {
            val fresh = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // 圆角跟窗口裁剪保持一致，否则描边会在圆角处露出尖角
                cornerRadius = if (style.mode == MODE_SMALLWINDOW) dp(24).toFloat() else 0f
                setStroke(dp(style.borderDp), color)
            }
            borderDrawable = fresh
            borderKey = key
            root.foreground = fresh
        } else if (root.foreground !== d) {
            // drawable 没变但窗口重建过（root 换了新对象），只需重新挂一次
            root.foreground = d
        }
        applyStepFlag(true)
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

        // 兜底：高光锚定的线性提亮 out=s*in+offset（in=255 → 255 不吹白、in=0 抬到 offset），
        // 再叠饱和度。ColorMatrix 只能线性变换，效果不如 AGSL 幂律提亮，
        // 但这是 AGSL 不可用时能保证不黑屏的最后一道。
        // 量纲口径：本仓为 0~255（原 offset=127.5f*(1-s)+lift*22f 即 8bit 平移量），故取 255f*(1-s)。
        val s = (1f - 0.10f * k - 0.23f * lift).coerceIn(0.5f, 1f)
        val offset = 255f * (1f - s)
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
                    // 真出画面了，说明「相机遇忙」已经过去，静默期立刻作废：
                    // 否则用户接下来 15 秒里正常走路也不会弹窗。
                    cameraBusyUntil = 0L
                    // 通了就把失败计数清零：下次再出问题仍然有完整的重试额度
                    retryCount = 0
                    openedCameraId?.let { lensFailCount.remove(it) }
                    if (!firstFrameReported) {
                        firstFrameReported = true
                        // 【5.3.5】方向摘要必须和「已收到画面」同一行落盘。
                        // 上一版把它单独打一行（`方向自洽 | …`），真机日志里
                        // 一次都没出现过 —— 那一行和窗口挂载在同一时刻，赶上
                        // 日志文件被轮转/截断就整段没了。这一行是用户回传时
                        // 一定看得到的锚点，把方向参数挂上去，下次不用再猜。
                        stage("✅ 已收到画面 | " + orientationSummary())
                        // 【5.7.1】超广角自愈：首帧通了但本次枚举没拿到真广角时重开一次
                        maybeSelfHealUltrawide()
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
            //
            // 【5.3.1 姿态】窗口形状跟着手机拿法走：竖着拿是 150×260 的竖条，
            // 横着拿是 260×150 的横条。以前窗口永远竖着，横向画面塞进去只剩中间
            // 一条，两侧大片空 —— 用户说的「一小块，空余很大」就是这件事。
            val landscape = isLandscapeWindowNow()
            val size = DevicePosePolicy.windowSize(style.smallWidthDp, SMALL_ASPECT, landscape)
            val w = dp(size[0])
            var h = dp(size[1])
            // 高度上限：横条在矮屏（横屏可用高度约 411dp）上仍然可能顶出去，
            // 宽度不动（用户调的是短边），只压高度。
            val (_, screenH) = screenSize()
            val maxH = (screenH * 0.62f).toInt()
            // 5.4.3 防止高度裁剪把竖条压成正方形：maxH<=w 时宁可顶到边也不裁
            if (maxH > 0 && h > maxH && maxH > w) h = maxH
            // 【5.4.5 方向锁定】小窗已锁定构图：窗口形状/尺寸取首帧锁定值，忽略实时姿态
            // 与用户改小窗宽度。上面的 landscape/size/maxH 原文全保留供全屏与首帧建锁使用；
            // 锁定值本身就是首帧裁好的窗口尺寸（来自 lastWindow），不会二次裁剪。
            val finalW: Int
            val finalH: Int
            if (style.mode == MODE_SMALLWINDOW && smallLock != null) {
                finalW = smallLock!!.winW
                finalH = smallLock!!.winH
            } else {
                finalW = w
                finalH = h
            }
            val margin = dp(style.smallMarginDp)
            WindowManager.LayoutParams(
                finalW, finalH,
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

    /** 浮点 dp：自绘小图标的线宽、圆角需要小数，[dp] 的整数四舍五入会把 1.5dp 变成 2dp */
    private fun dpf(v: Float): Float = v * ctx.resources.displayMetrics.density

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
        stepFlag = null
        rotLayer = null
        hudView = null
        stepBadge = null
        hudColumn = null
        tv = null
        surfaceTexture = null
        // 【5.4.1】切断跨会话污染：overlay 在服务里是单例，hide() 只清 View。
        // 若上一次会话被中间态污染成正方形，lastWindowW/H（及同族的缓冲尺寸）
        // 会带到下一次 show，让无参 applyFraming 继续采信方尺寸。这里一并清零。
        lastWindowW = 0
        lastWindowH = 0
        actualBufferW = 0
        actualBufferH = 0
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
        // 用户主动点预览 / 重新授权 / 拖滑块时，连「相机遇忙」的等待一起解除：
        // 这是人的明确意图，比定时器可靠。若相机确实仍被别的应用占着，
        // 接下来的 open 会再失败一次，重新进入静默期，不会有害。
        cameraBusyUntil = 0L
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
        // 【5.6.0 省电】锁帧率。null 表示 HAL 没报可用区间，保持原生（不干预）。
        aeFpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        if (style.torchWithPreview && flashAvailable) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        }
        // 【5.0.0 低光增强】夜间路面几乎没有光，AUTO 曝光会把画面压成一片黑，
        // 看得见轮廓才谈得上「看路」。这里按机型支持范围给正向补偿，不做固定写死：
        // 不同 HAL 的补偿步长不同（1/3 EV 或 1/6 EV），固定值在部分机型上等于没设。
        if (style.lowLight && aeCompTarget > 0 && !(style.torchWithPreview && flashAvailable)) {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeCompTarget)
        }
        // 【路况预览 · 超广角】逻辑摄像头机型：同一颗后置 id 上把 zoom 拉到 0.6x，
        // HAL 自动切到超广角物理镜头，拿到用户要的「0.6 倍」视野。null 时不下发，
        // 保持原生 1.0（独立超广角 id 机型 / 无广角机型 / 主摄档）。
        val zoom = requestedZoomRatio
        if (zoom != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        }
        val torchOn = style.torchWithPreview && flashAvailable
        // 【5.6.16】真实点亮状态变化才回调：buildPreviewRequest 会因切 zoom / 换参数
        // 反复重建，不能每次都发通知；只有 false→true / true→false 这一下才有意义。
        if (torchOn != torchLitReported) {
            torchLitReported = torchOn
            try { onTorchLit?.invoke(torchOn) } catch (_: Throwable) {}
        }
        val aeCompApplied = style.lowLight && aeCompTarget > 0 && !torchOn
        // 实际下发的 zoom 走独立节流通道，避免被高频计步日志挤出环形缓冲后看不到
        DiagLog.logThrottled(
            "lens.req", "dump",
            "[cam] preview req | torch=$torchOn flash=${if (torchOn) "TORCH" else "-"}"
                + " aeComp=${if (aeCompApplied) aeCompTarget else 0} lowLight=${style.lowLight}"
                + " zoom=${zoom ?: 1.0f}",
            5_000L, 120_000L
        )
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
    /**
     * 【5.7.1】「UI 选着超广角、开出来却是主摄」的自愈。
     *
     * 真机现象（用户反馈）：覆盖安装后第一次打开，开关明明停在「超广角」，画面却是主摄，
     * 再点一次「超广角」才变成广角。原因是首轮枚举时 HyperOS 的隐藏物理子相机
     * （id=2/3/4）还没就绪，[backCameraId] 只看到 id=0 → 选到主摄；
     * 等会儿再枚举它们才出现（日志里 `隐藏子相机=2,3,4` 就是这么来的）。
     *
     * 处理：首帧已经通了、但 [lastPickRealUltrawide] 为 false 时，1 秒后关掉重开一次，
     * 让第二轮枚举重新按焦距挑。**进程内只试一次**：真没有超广角的机型不该每次显示都闪一下。
     * 重试后仍不是广角，就明确落一条「本机按主摄出图」，不再循环。
     */
    private fun maybeSelfHealUltrawide() {
        if (style.cameraLens != CameraLensPolicy.MODE_ULTRAWIDE) return
        if (lastPickRealUltrawide) return
        if (ultrawideHealAttempted) {
            diag("[cam] ⚠️ 重试后仍未拿到超广角，本机按主摄出图（选项保持=${style.cameraLens}）")
            return
        }
        ultrawideHealAttempted = true
        diag("[cam] ⚠️ 选了超广角但本次只枚举到主摄(id=" + openedCameraId
                + ") → 1 秒后重开重试一次（覆盖安装/冷启动时隐藏子相机可能未就绪）")
        mainHandler.postDelayed({
            try {
                if (isShowing) {
                    closeCamera()
                    // 重开后重新落一条「已收到画面」，顺带把最终结论写进日志
                    firstFrameReported = false
                    openCamera()
                }
            } catch (_: Throwable) {
            }
        }, 1_000L)
    }

    private fun openCamera() {
        try {
            if (!isShowing) return
            // 相机遇忙静默期内不要再发起 open：上一次刚被拒(4)，立刻再开只会再被拒，
            // 然后触发看门狗收窗、再有下一次 show……就是那个 2 秒一轮的闪烁。
            if (System.currentTimeMillis() < cameraBusyUntil) return
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
            // 【省电专项】记下本次要打开的相机 id 与 zoom 能力，供镜头切换时判断能否原地切 zoom
            openedCameraId = id
            openedCameraSupportsZoom = id != null && hasLogicalZoom(id)
            resolveOrientation(id, view.width, view.height)
            // 方向刚算出来，立刻把矩阵落下去。
            // 上面 onSurfaceTextureAvailable 里那次 applyFraming 跑在 openCamera 之前，
            // 当时 appliedRotDeg 还是 -1（不旋转）；此后尺寸不变就不会再触发回调，
            // 于是「会转但没转」会一直挂到下次窗口 resize —— 正是「改了方向没反应」。
            applyFraming()
            // 【5.4.4】固定 buffer 档位：与首帧窗口形状/姿态解耦，消除"每次打开裁切不一样"。
            // 取当前窗口尺寸的短边×长边，横竖首帧都得到同一个竖条 buffer（小窗 488×845）。
            // 若尺寸尚未量出，回退到 view 的短边×长边。
            val bw0 = if (lastWindowW > 0) lastWindowW else view.width
            val bh0 = if (lastWindowH > 0) lastWindowH else view.height
            actualBufferW = minOf(bw0, bh0)
            actualBufferH = maxOf(bw0, bh0)
            // 【5.4.5 方向锁定】小窗已有锁定构图：buffer 钉死首帧值，不随本次窗口尺寸重算。
            // 回小窗会重开相机；此刻 lastWindow 已被重放分支恢复成首帧值，本段 min/max 结果
            // 与锁定值一致，这里再显式覆盖一层，保证幂等且不依赖重放是否已执行。
            if (style.mode == MODE_SMALLWINDOW && smallLock != null) {
                actualBufferW = smallLock!!.bufferW
                actualBufferH = smallLock!!.bufferH
            }
            // 【5.4.5 方向锁定】首帧建锁：方向已算真值、构图已由上面的 applyFraming 落盘、
            // buffer 刚决策完，是唯一正确的捕获点（apply():917 那次太早，deg 还是 0）。
            lockFirstFrame()
            st.setDefaultBufferSize(actualBufferW, actualBufferH)
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
            // 【5.6.0 省电】帧率锁 30。相机是整套预览里最大的单项功耗，帧率直接乘在上面：
            // 不锁的话 HAL 常给 [15,60] 的变范围，白天路面亮就自动顶到 60fps，
            // 对「看清脚下路」毫无帮助，只是把 ISP + GPU 的功耗翻倍。
            // 选档顺序：恰好 [30,30] → 上限 30 且下限最高 → 任意上限 30 → 不锁。
            aeFpsRange = try {
                val chars = cm.getCameraCharacteristics(id)
                val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                if (ranges == null) null else {
                    val exact = ranges.firstOrNull { it.lower == PREFERRED_FPS && it.upper == PREFERRED_FPS }
                    if (exact != null) exact else {
                        val capped = ranges.filter { it.upper == PREFERRED_FPS }
                        capped.maxByOrNull { it.lower }
                    }
                }
            } catch (_: Throwable) {
                null
            }
            diag("[cam] 能力探测 | flashAvailable=$flashAvailable aeCompTarget=$aeCompTarget"
                    + " fpsRange=" + (aeFpsRange?.let { "${it.lower}~${it.upper}" } ?: "不锁"))
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
                    // 【5.7 修复 · 避让兜底】相机被别的应用抢占（系统相机打开取景、
                    // 扫码、视频通话）时，我们这边拿到的是 onDisconnected。旧实现只把
                    // 设备句柄置空，窗口继续挂着 —— 用户看到的就是「相机界面右上角
                    // 还压着一个不动的小窗」。这里直接收窗，并给 5 秒冷静期，
                    // 避免下一轮 show 立刻又把相机抢回来，形成来回抢设备的死循环。
                    cameraBusyUntil = System.currentTimeMillis() + 5_000L
                    diag("⚠️ 相机被其他应用抢占 → 立即收起小窗（5 秒内不重开）")
                    // 回调跑在相机线程，removeView 必须回主线程（窗口归主线程所有）
                    mainHandler.post {
                        try {
                            hide()
                            try { onPreempted?.invoke() } catch (_: Throwable) {}
                        } catch (_: Throwable) {
                        }
                    }
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
        // 【5.7.3 冷却重试】独立子相机（被裁掉的隐藏超广角）打不开：**冷却**而不是永久拉黑。
        // 5.3.0 起这里是「一次失败写进本会话黑名单」，结果真机上被别的应用短暂占着相机
        // （15:35:19 id=3 打不开）就把超广角整段封死，用户后面再看还是主摄。
        // 现在改成带冷却的软禁用：失败后 LENS_RETRY_COOLDOWN_MS 内不再尝试，
        // 冷却结束后重新参与选择；主摄本身永不入表。
        val cur = openedCameraId
        if (cur != null && fallbackMainId != null && cur != fallbackMainId) {
            val nowElapsed = SystemClock.elapsedRealtime()
            val n = (lensFailCount[cur] ?: 0) + 1
            lensFailCount[cur] = n
            if (n >= LENS_MAX_ATTEMPTS) {
                lensFailCount.remove(cur)
                lensCooldownUntil[cur] = nowElapsed + LENS_RETRY_COOLDOWN_MS
                diag("⚠️ 镜头 id=$cur 连续 $n 次打不开，冷却 ${LENS_RETRY_COOLDOWN_MS / 1000}s 后再试，本次回退主摄 id=$fallbackMainId")
            } else {
                diag("⚠️ 镜头 id=$cur 第 $n 次打不开，再试一次（共 $LENS_MAX_ATTEMPTS 次），本次回退主摄 id=$fallbackMainId")
            }
        }
        closeCamera()
        if (retryCount >= MAX_CAMERA_RETRY) {
            retryCount = 0
            diag("❌ $why，已重试 $MAX_CAMERA_RETRY 次仍失败，放弃")
            // 放弃之后必须静默一会儿。不设这段静默的话，下一次 show 会立刻重开，
            // 又是「拒 → 收窗 → 再挂」的循环（真机 12:37 一分钟 25 次）。
            cameraBusyUntil = System.currentTimeMillis() + CAMERA_BUSY_BACKOFF_MS
            diag("⏸ 相机遇忙：${CAMERA_BUSY_BACKOFF_MS / 1000} 秒内不再自动拉起"
                + "（多半是别的应用占着相机）")
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
        val displayDeg = referenceDegrees()
        val autoDeg = PreviewOrientationPolicy.rotationDegrees(sensor, displayDeg, front)
        val mode = PreviewOrientationPolicy.normalizeMode(style.rotationMode)
        val rawDeg = PreviewOrientationPolicy.applyMode(autoDeg, mode)
        // 窗口形状就用**已经量出来的视图尺寸**判断，不回头问传感器：
        // 这里拿到的是真实宽高，是整条链路里唯一不需要推导的事实。
        appliedRotDeg = PreviewOrientationPolicy.alignToWindow(rawDeg, viewW > viewH)
        // 【5.4.5 方向锁定】小窗已有锁定构图：方向角钉回首帧值，不让回小窗重开相机
        // 时的实时姿态覆盖它（上方 alignToWindow 原文保留，全屏照常走实时计算）。
        if (style.mode == MODE_SMALLWINDOW && smallLock != null) {
            appliedRotDeg = smallLock!!.rotDeg
        }

        // 【5.6.0 省电】小窗只要 720p：短边 ≤720 的等价档位。全屏保持 1080p 预算。
        val picked = PreviewOrientationPolicy.pickPreviewSize(
            sizes, viewW, viewH, appliedRotDeg,
            if (style.mode == MODE_SMALLWINDOW) SMALLWINDOW_MAX_SHORT_SIDE else 0
        )
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
        diag(
            "方向自洽 | 窗口=" + (if (viewW > viewH) "横条" else "竖条")
                + " " + viewW + "x" + viewH
                + " 传感器=" + sensor + "°"
                + " 原始=" + rawDeg + "° 实转=" + appliedRotDeg + "°"
                + (if (rawDeg != appliedRotDeg) "（已按窗口形状校正）" else "（无需校正）")
        )
    }

    /**
     * 【省电专项】某颗相机是否支持「逻辑 zoom」（CONTROL_ZOOM_RATIO_RANGE 下限 < 1）。
     * 支持则超广角靠下发 0.6x 实现，切镜头无需重开相机。读不到按不支持处理（保守重开）。
     */
    private fun hasLogicalZoom(id: String): Boolean = try {
        val range = cm.getCameraCharacteristics(id)
            .get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        range != null && range.lower < 1f && range.upper > range.lower
    } catch (_: Throwable) {
        false
    }

    /**
     * 【超广角 · 兼容探测】被 HAL 裁剪的隐藏物理子相机，按序号试探的最大下标。
     *
     * 小米 / HyperOS 对未进相机白名单的第三方应用，`cameraIdList` 往往只枚举主摄 +
     * 前摄，超广角 / 长焦以序号 id 2、3… 挂在 HAL 里但不公开列出。真机实测（K90
     * 至尊版）：id=0 主摄虽上报 zoom 0.6~10.0，但下发 0.6 并不切物理广角；真正的
     * 超广角是隐藏的后置物理镜头（等效 15.6mm）。因此这里按序号试探，**后置物理镜头
     * 纳入选择候选**，交策略层按等效焦距判据选中直开；隐藏前摄只落诊断。
     */
    private val hiddenCameraProbeMaxId = 6

    /**
     * 读取一颗相机的候选数据与诊断摘要。读不到（id 不存在 / HAL 拒绝访问）时
     * 返回 `(null, null)`，调用方按「无此相机」静默跳过。
     *
     * @param hidden 该 id 是否为按序号试探的隐藏子相机（仅影响诊断标注）
     */
    private fun readCameraCandidate(id: String, hidden: Boolean): Pair<CameraLensPolicy.Candidate?, String?> {
        val cc = try { cm.getCameraCharacteristics(id) } catch (_: Throwable) { return null to null }
        val back = cc.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        // 焦距列表：超广角判据。拿不到就留 null，策略层自动回退第一颗后置。
        val focal = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        var minF: Float? = null
        var maxF: Float? = null
        if (focal != null && focal.isNotEmpty()) {
            var lo = Float.MAX_VALUE
            var hi = -1f
            for (f in focal) {
                if (f <= 0f) continue
                if (f < lo) lo = f
                if (f > hi) hi = f
            }
            if (lo != Float.MAX_VALUE) minF = lo
            if (hi > 0f) maxF = hi
        }
        // 【5.6.0 等效焦距】传感器物理尺寸（mm）。有了它才能把「物理焦距」
        // 换算成 35mm 等效焦距，消掉不同 sensor 尺寸带来的不可比性
        // （见 CameraLensPolicy.ULTRAWIDE_EFL_RATIO 的推导）。
        // 读不到的机型（部分魅族 / 类原生精简 HAL）留 null，策略层自动降级。
        var diagMm: Float? = null
        try {
            val size = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (size != null && size.width > 0f && size.height > 0f) {
                diagMm = kotlin.math.sqrt(
                    size.width * size.width + size.height * size.height
                )
            }
        } catch (_: Throwable) {
        }
        val zoomRange = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        // 传感器原生像素阵列：广角小底（800 万级）与主摄大底（5000 万级）阵列尺寸
        // 差异明显，用于从日志佐证选中镜头的流确实来自对应传感器（而非被降级成主摄流）。
        val arraySize = try {
            cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        } catch (_: Throwable) { null }
        val cand = CameraLensPolicy.Candidate(id, back, minF, maxF, diagMm)
        val note = buildString {
            append("id=").append(id).append("(").append(if (back) "后" else "前")
            if (hidden) append("·隐藏")
            append(") 焦距=").append(minF ?: "?").append("mm")
            append(" 对角=").append(diagMm ?: "?").append("mm")
            append(" 等效=").append(cand.eflMm?.let {
                String.format(java.util.Locale.US, "%.1fmm", it)
            } ?: "?")
            append(" 阵列=").append(arraySize?.let { "${it.width}x${it.height}" } ?: "?")
            append(" zoom=").append(zoomRange?.let { "${it.lower}~${it.upper}" } ?: "无")
            append(" cap=").append(caps?.joinToString(",") ?: "?")
        }
        return cand to note
    }

    /**
     * 按用户选的镜头挑后置摄像头。
     *
     * 每颗后置的焦距读出来交给 [CameraLensPolicy]：选超广角就挑焦距最短那颗
     * （短到阈值以下才算真有广角），选主摄挑最接近标准焦距那颗；没有焦距信息时
     * 退回第一颗后置。「无超广角自动用主摄代替」由策略层保证，不会开出黑屏。
     * 被 HAL 裁剪的隐藏后置物理镜头（被裁掉的超广角）一并纳入候选参与选择。
     */
    private fun backCameraId(): String? {
        try {
            val candidates = ArrayList<CameraLensPolicy.Candidate>()
            val hiddenCandidates = ArrayList<CameraLensPolicy.Candidate>()
            val notes = ArrayList<String>()
            val listed = cm.cameraIdList.toSet()
            // ① 系统公开枚举的相机（已判定打不开的非主摄镜头直接排除）
            for (id in cm.cameraIdList) {
                if (isLensCooling(id)) continue
                val (c, note) = readCameraCandidate(id, false)
                if (c != null) candidates.add(c)
                if (note != null) notes.add(note)
            }
            // ② 按序号试探被 HAL 裁剪的隐藏物理子相机。后置物理镜头（被裁剪的超广角）
            //    纳入选择候选，交策略层按等效焦距判据选中；隐藏前摄只落诊断，不进后置选择。
            for (n in 2..hiddenCameraProbeMaxId) {
                val hid = n.toString()
                if (hid in listed || isLensCooling(hid)) continue
                val (c, note) = readCameraCandidate(hid, true)
                if (c != null) {
                    hiddenCandidates.add(c)
                    if (c.back) candidates.add(c)
                }
                if (note != null) notes.add(note)
            }
            val picked = CameraLensPolicy.select(candidates, style.cameraLens)
            val backs = candidates.filter { it.back }
            val mainId = backs.firstOrNull()?.let { CameraLensPolicy.pickMain(backs).id }
            if (picked != null) {
                // 选中的是独立超广角子相机时记下主摄 id，供打开失败回退；选中主摄则不设。
                fallbackMainId = if (picked != mainId) mainId else null
                // 【超广角 · 逻辑摄像头】关键是这里：现代机型 cameraIdList 只有一颗后置，
                // 超广角不是独立 id，而是同一颗 id 上把 CONTROL_ZOOM_RATIO 拉到 0.6x。
                // 读不到 range / 下限 ≥1（无广角端）时策略返回 null → 保持原生 1.0。
                val pickedChars = cm.getCameraCharacteristics(picked)
                val zoomRange = pickedChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                requestedZoomRatio = CameraLensPolicy.zoomForLogicalUltrawide(
                    style.cameraLens, zoomRange?.lower, zoomRange?.upper)
                // 【5.7.1】记录本次是否真的选到广角，供首帧自愈判断（相当于策略判据的复算）
                val pickCand = candidates.firstOrNull { it.id == picked }
                val mainCand = backs.firstOrNull { it.id == mainId }
                lastPickRealUltrawide = pickCand != null && (
                    (requestedZoomRatio != null && requestedZoomRatio!! < 1f)
                        || (pickCand.eflMm != null && mainCand?.eflMm != null
                        && pickCand.eflMm!! <= mainCand.eflMm!! * CameraLensPolicy.ULTRAWIDE_EFL_RATIO)
                    )
                val hiddenNote = if (hiddenCandidates.isEmpty()) ""
                    else " | 隐藏子相机=" + hiddenCandidates.joinToString {
                        "${it.id}(${it.minFocalMm ?: "?"}mm/等效=${
                            it.eflMm?.let { e -> String.format(java.util.Locale.US, "%.1fmm", e) } ?: "?"})"
                    }
                // 镜头选择结论与选中 zoom 走独立节流通道：避免被高频计步日志挤出环形缓冲。
                DiagLog.logThrottled(
                    "lens.pick", "dump",
                    "[cam] 镜头选择=${style.cameraLens} → id=$picked"
                        + " zoom=" + (requestedZoomRatio?.toString() ?: "1.0(原生)")
                        + " zoomRange=" + (zoomRange?.let { "${it.lower}~${it.upper}" } ?: "不支持")
                        + hiddenNote,
                    5_000L, 120_000L
                )
                // 【5.6.0 多厂商校准】设备清单一行输出，含焦距 / 传感器对角 / 等效焦距 /
                // 变焦范围 / 能力位。三星、魅族、Pixel、类原生、小米的 id 顺序与上报字段
                // 各不相同，只能靠真机数据校准；节流避免每次开相机刷屏。
                DiagLog.logThrottled(
                    "lens.inventory", "dump",
                    "[lens] 后摄清单 | " + CameraLensPolicy.describe(candidates)
                        + hiddenNote
                        + " | 主摄=" + mainId
                        + " | 能力=" + notes.joinToString(" ; "),
                    5_000L, 120_000L
                )
            } else {
                requestedZoomRatio = null
                fallbackMainId = null
            }
            return picked
        } catch (_: Throwable) {
            requestedZoomRatio = null
            // 任何异常都退回第一颗后置，保证「有摄像头就能出画面」这条底线
            return try {
                cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
