package io.github.wumisc.safewalk

import io.github.wumisc.safewalk.torch.GaitStateCallback
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 步态检测参数。
 */
object MotionConfig {
    const val ENTER_DEBOUNCE_MS = 2500L
    const val EXIT_DEBOUNCE_MS = 4500L

    /**
     * 步频窗口，单位 Hz。
     *
     * 真实散步大约 0.9~1.1Hz（每步 900~1400ms），快走 1.5~2Hz。
     * 【5.7.3-lx102 · 114】下限 0.5 → 0.7Hz：沙盒矩阵里「车上低频颠簸」
     * （0.35/0.65Hz 正弦、振幅 1.0）靠 0.65Hz 这一档就能凑满 8 步，
     * 而人走路再慢也在 0.9Hz 上下；0.7Hz（42 步/分）已低于任何真实步行。
     * 3Hz 以上的抖腿 / 摇手机仍由上限挡住。
     */
    const val MIN_STEP_FREQ = 0.7
    const val MAX_STEP_FREQ = 2.5
    /**
     * 真断步阈值：超过这个时长没有新峰，才判定「停下很久、重新数」。
     * 必须明显宽于步频下限（最短间隔 1/MAX_STEP_FREQ=400ms，最长 1/0.7≈1428ms），
     * 否则正常慢速散步会被当成断步清空，计数永远攒不满 → 走了也不触发。
     */
    const val GAIT_RESET_MS = 2500L
    /**
     * 【5.6.3】合格步「滑动窗口」的时效（毫秒）。
     *
     * 旧实现只按数量保留最近 10 个间隔，**完全没有时间上限**：只要每隔 <2.5s 来一个
     * 越阈峰（蹲着时躯干微动、手机在兜里随呼吸轻晃就够），窗口就永远不清，
     * `confirmedStepCount()` 长期停在满格 10，于是
     *   `countReached = credible && count >= confirmSteps` 恒成立、
     *   `lastStepTs` 每帧被刷成 now → 判停永远不触发 → 小窗常挂不消失。
     * 真机表现就是「走几步到厕所蹲下，路况预览一直显示」。
     *
     * 真人走满 10 步：正常步频 0.9~1.5Hz 只需 6.7~11s，慢走 0.7Hz 约 14s。
     * 取 15s 作上限：正常与偏慢的步行都在窗口内，而「蹲着每 2s 一次微动」
     * 在 15s 里最多 8 个峰，撑不到 10 格，窗口自然掉出满格。
     */
    const val STEP_WINDOW_MS = 15_000L
    /**
     * 【5.6.4】垂直/水平能量占比的 EMA 系数。
     *
     * 50Hz 采样下 0.9 对应约 0.2s 时间常数：够快，能在「晃两下」的窗口内建立统计；
     * 也够稳，不会被单帧的轴向瞬时抖动带偏。
     */
    const val VERTICAL_EMA = 0.9f
    const val MIN_WALK_SPEED = 0.3f
    const val MAX_WALK_SPEED = 2.5f
    const val LOCATION_QUEUE_SIZE = 3

    // ===== 峰值检测 =====

    /** 峰阈值的绝对下限（m/s²）：低于这个值就是噪声，不是脚步 */
    const val MIN_PEAK_THRESHOLD = 0.6f
    /**
     * 可计峰的线性加速度幅值上限（m/s²）。
     *
     * 真实步行/跑步的峰值约 1.5~6；远超 8 的幅值只可能是拿放手机、
     * 翻转、拍打等非步行瞬态。超过该值：不计峰、不污染包络阈值，
     * 避免「翻一下手机把阈值顶到 7~8、之后几秒真实步伐全被压住」。
     */
    const val MAX_PEAK_VALUE = 8.0f
    /** 包络阈值 = 最近包络 × 该系数 */
    const val ENV_FACTOR = 0.6f
    /**
     * 包络的时间衰减常数。
     *
     * 原来是 4 秒，看起来「更稳」，实际是致命的：包络只升不降，4 秒衰减意味着
     * 任何一次瞬态（启动时的重力收敛、把手机从桌上拿起来）都会把阈值顶到
     * 5~6 m/s² **长达好几秒**，而真实步行的峰值只有 2.5 m/s² —— 期间一个峰都上不去。
     * 1.5 秒衰减时，同样的瞬态 2 秒内就回落到 1.5 以下，4 秒采样窗口还来得及。
     */
    const val ENV_DECAY_MS = 1500.0
    /** 两次计峰的最短间隔：200ms ≈ 5 次/秒，挡住同一脚步的抖动与主轴切换造成的重复计数 */
    const val REFRACTORY_MS = 200L

    /**
     * 重力低通预热帧数。
     *
     * 重力已经用首帧对齐，这里只是挡住最开始几帧的姿态抖动（手机还在手里翻转时
     * 第一帧读数未必等于稳态重力）。3 帧 = 60ms，占 4 秒采样窗口的 1.5%，
     * 代价可以忽略。
     */
    const val WARMUP_FRAMES = 3

    // 姿态判定
    const val LAY_FLAT_Z_THRESHOLD = 8.5f
    /**
     * 平放姿态的阈值系数。【5.7.3 由 1.45 改为 1.0】
     *
     * 1.45 的出发点是对的（躺着刷手机不该算走路），但它把阈值抬到
     * `max(0.6, 包络×0.6)×1.45` 后，平着拿手机走路（屏幕朝上看小窗）的
     * 步峰只剩一两成能越线 —— 真机日志里 10 秒只出 1~4 个峰、步频被拉成
     * 0.5~0.8Hz（真人步频 1.5~2.2Hz），连续合格步永远攒不到 8 步。
     *
     * 现在不再按姿态抬阈值：防误触的职责交给上层（[WalkConfirmPolicy] 的
     * 非平躺要求、[WalkPreviewService] 的 credible 证据链、硬件计步与定位佐证）。
     * 采集层只负责「这一帧的波形像不像脚步」。
     */
    const val LAY_FLAT_WEIGHT = 1.0f
}

enum class PhoneAttitude {
    UNKNOWN, LAY_FLAT, UPRIGHT, TILTED
}

/**
 * 步态检测器：一阶低通分离重力，主轴自适应，包络阈值计峰，步频窗口校验。
 *
 * 【这一版为什么整体重写】
 * 旧实现有两个数学上致命的错误，两者都不报错、不崩溃，只是「永远判不出在走路」：
 *
 * ① **重力滤波系数写反了**。`y = alpha*y + (1-alpha)*x` 是低通，alpha 必须接近 1
 *    才是「慢慢跟踪重力」。旧代码 alpha=0.2，等于让重力估计 80% 跟着新样本跑，
 *    减完以后残差只剩 1% 量级 —— 仿真里 1.5Hz 正常步行，线性加速度平均值
 *    只有 **0.07 m/s²**，而阈值下限是 0.6，一个峰都不会出现。
 *    单元测试没发现，是因为测试喂的是「单帧 +6 m/s² 的阶跃尖峰」，
 *    这种不连续信号在 alpha=0.2 下也能产生 1.2 的残差；真实步行是连续正弦，
 *    两者行为完全不同。所以这次专门补了正弦波用例锁死。
 *
 * ② **阈值公式在小信号下自相矛盾**。原来是 `均值 + 1.8×标准差`，
 *    而 |正弦| 的均值本身就有 0.64 倍幅值、标准差 0.31 倍幅值，
 *    算出来 1.2 倍幅值 > 幅值本身 —— 阈值永远高于信号峰值。
 *    现在改成「时间衰减包络 × 0.6」，与波形分布无关。
 *
 * 另外加了主轴自适应：手机在口袋里朝哪个方向是随机的，固定用某一轴必然失效。
 */
class GaitDetector {
    /** 重力估计。0.96 对应约 0.5Hz 的低通截止，人体步频的基波能干净地留在残差里 */
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private val alpha = 0.96f

    /**
     * 重力估计是否已经用第一帧初始化过。
     *
     * 这是「走了半天没反应」的真正根因之一，而且极隐蔽：
     * gravity 初值是 0，而真实重力是 9.8。让低通滤波器从 0 爬到 9.8，
     * 时间常数约 0.5 秒、收敛到 99% 要 2.3 秒 —— 这期间残差高达 9.4 m/s²，
     * 包络被这个假峰顶到 9.4，阈值随之升到 5 以上，而真实步行峰值只有 2.5。
     * 4 秒采样窗口里一个峰都上不去，日志永远显示「峰数=0」。
     *
     * 正确做法是拿第一帧的读数直接当重力初值（人站着时加速度计读数就是重力），
     * 残差从第一帧起就是 0。
     */
    private var gravityInit = false

    /**
     * 还需要多少帧才算预热完。
     *
     * 重力已经用首帧对齐之后，这里只需要挡住最开始几帧的姿态抖动。
     * 保留这个机制是为了「检测器被复用到另一个方向」时有个缓冲，
     * 不再承担「等重力收敛」的职责。
     */
    private var warmupLeft = MotionConfig.WARMUP_FRAMES

    /** 各轴线性加速度的能量（EMA），用来挑「这次走路主要晃的是哪个轴」 */
    private var emaX = 0f
    private var emaY = 0f
    private var emaZ = 0f
    private val emaAlpha = 0.98f

    /** 峰值包络：只升不降（按时间缓慢衰减） */
    private var envelope = 0f
    private var lastSampleTs = 0L

    private var lastPeakTime = 0L
    /**
     * 【5.3.8 审查返工】判停时钟数据源：最近一次「步频窗口内合格步峰」的单调递增时间戳。
     *
     * 与 lastPeakTime 分开维护：后者在「越阈但步频落窗口外」的兜底分支也会被顶新，
     * 抖动 / 慢走会借此刷新时钟；本字段只在合格步峰入队时推进，静止 / 无峰 / 平放抖动
     * 一律冻结。它是只读观察值，不参与任何步态判定，判停动作本身也不触碰它。
     */
    private var lastStepActivityTs = 0L

    /**
     * 【5.6.0】最近一次「采集层达标」是否发生在平放姿态下。
     *
     * 只作为**事实**上报：采集层不再因为平放而否决这一帧，改为由
     * [WalkPreviewService] 结合硬件步数与 [MotionContext] 决定要不要真正显示。
     * 默认 false，[reset] 时归零。
     */
    private var lastPeakFlat = false
    /** 上一帧是否已经在阈值之上：只认「上穿」的那一刻，避免一个波峰算成好几步 */
    private var aboveThreshold = false
    private val peakIntervals = mutableListOf<Long>()

    /**
     * 【5.6.3】每个合格步的绝对时间戳，与 [peakIntervals] / [peakAxes] 一一对应。
     *
     * 只为给「滑动窗口」加时间上限（[MotionConfig.STEP_WINDOW_MS]）用：
     * 数量上限解决不了「稀疏峰把满格状态永久吊住」，必须有绝对时间才能剔除陈旧峰。
     * 三个队列严格等长、同进同出，长度一致性由 [dropStalePeaks] 与入队逻辑保证。
     */
    private val peakTs = mutableListOf<Long>()

    /**
     * 每个合格步所在的主轴（0/1/2）。
     *
     * 走路时人体上下颠簸，晃动主轴稳定在同一方向；拿在手里乱动（翻动/甩动/刷屏）
     * 时方向随机、主轴来回切换。触发前要求最近 confirmSteps 个合格步的主轴一致率
     * ≥70%，从根源把「手机在手里自己触发预览」挡掉。
     */
    private val peakAxes = mutableListOf<Int>()
    private var currentAxis = 0

    private var currentAdaptiveThreshold = MotionConfig.MIN_PEAK_THRESHOLD

    /**
     * 【5.6.4 垂直轴占比】线性加速度在「重力方向」上的能量占比（EMA，0~1）。
     *
     * 为什么需要这一项：峰值检测只看**幅值**，而手机在手里的任何一次前后/左右晃动、
     * 转身、举起来拍照，幅值都能轻松越过阈值、间隔也常落在 0.5~2.5Hz 的步频窗口里，
     * 于是「晃两下就攒够步数弹小窗」。可物理上二者完全不同：
     *  · 走路是脚落地给的**上下冲击**，能量几乎全部沿重力方向；
     *  · 手里晃动 / 翻转是**水平面内的往复**，能量几乎全在垂直于重力的平面上。
     * 所以用「沿重力方向能量 / 全部线性能量」这个比值做第二道闸，是幅值之外唯一
     * 有物理意义、又不依赖机型标定的区分维度。
     *
     * 以重力向量为参考（而非机身的某个固定轴），所以手机竖着拿、横着拿、在口袋里斜着
     * 都不影响：只要人在走，重力轴上的分量就始终占主导。
     */
    private var emaVertical = 0f
    private var emaHorizontal = 0f

    /**
     * 合格峰所需的垂直能量占比下限。
     *
     * 0.5 是二者「等能量」的分界：真走路（含手臂摆动）实测占比通常在 0.65~0.95，
     * 手持横向晃动在 0.1~0.35，平放抖动更低。取 0.5 留足余量，只拦「明显不是上下冲击」的峰。
     */
    private val MIN_VERTICAL_RATIO = 0.5f

    /**
     * 平放姿态下的峰幅值下限（m/s²）。【5.7.3 新增】
     *
     * 平着拿手机走路时，手臂前后摆的水平能量占优，真机日志里垂直占比常在
     * 0.15~0.35，被 0.5 的垂直闸门整段挡死（10 秒只留下 1~2 个峰、步频被拉成 0.6Hz）。
     * 平放姿态下「上下冲击」这个判据本身没有意义，改用绝对幅值兜底：
     * 真迈步的线性加速度峰值实测 0.9~2.6，取 0.85 作为「够劲」的门槛，
     * 捏着手机搓屏、桌上微震这类小抖动（<0.5）依然过不去。
     */
    private val FLAT_PEAK_MIN = 0.85f

    private fun peakGateOk(value: Float): Boolean =
        if (currentAttitude == PhoneAttitude.LAY_FLAT) value >= FLAT_PEAK_MIN
        else verticalRatio() >= MIN_VERTICAL_RATIO

    // ========== 闸门拒因计数（每 10 秒打印后清零，供真机诊断） ==========
    private var gateVerticalDrop = 0
    private var gateFreqDrop = 0
    private var gateRefractoryDrop = 0
    private var verticalDropLatch = false

    /** 当前垂直能量占比（诊断用，0~1；无样本时返回 0） */
    fun verticalRatio(): Float {
        val sum = emaVertical + emaHorizontal
        return if (sum <= 1e-6f) 0f else emaVertical / sum
    }

    /**
     * 姿态稳定器：滞回 + 700ms 驻留。
     *
     * 【5.2.0 为什么必须加这一层】
     * 原来 `updateAttitude` 是单帧直判 `abs(gz) > 8.5 → LAY_FLAT`，而 8.5 对应
     * 倾角 acos(8.5/9.8) ≈ 30° —— 手持低头看屏幕（30~50° 仰角）正好卡在边界上，
     * 握持微抖就会在 LAY_FLAT / TILTED 之间反复翻转。
     *
     * 真机日志（2026-11-14，Redmi / HyperOS 4）里 18 分钟内 LAY_FLAT 出现 58 次、
     * TILTED 71 次，3 秒内翻两次。LAY_FLAT 是 [WalkConfirmPolicy] 的第一道硬闸，
     * 每翻一次就直接判死一次，这是「走了十几分钟一次没弹」的第二个原因。
     */
    private val attitudeStabilizer = AttitudeStabilizer()

    val currentAttitude: PhoneAttitude
        get() = attitudeStabilizer.current()

    fun getAttitude(): PhoneAttitude = attitudeStabilizer.current()

    /**
     * 屏幕法线与水平面的夹角：0° ≈ 屏幕朝上平放，90° ≈ 手机竖直。
     *
     * 用途：低头提醒。走路时低头看手机，屏幕朝上偏前，这个角度会明显变小；
     * 正常握持在身侧时接近竖直。重力尚未收敛（模长 < 8）返回 -1，调用方应忽略。
     *
     * 只读派生值，不改动任何既有步态判定逻辑。
     */
    fun tiltDegrees(): Float {
        val gMag = sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)
        if (gMag < 8f) return -1f
        val cos = (abs(gravityZ) / gMag).coerceIn(0f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
    }

    // ========== 静置状态回调（手电筒静置提醒用） ==========
    private var gaitStateCallback: GaitStateCallback? = null
    private var staticDetectTimerStart: Long? = null
    private val SENSOR_STATIC_THRESHOLD = 3000L // 传感器3秒无运动判定为静置
    /**
     * 连续合格步达到这个数才认定「走起来」，防刷手机误触。可由 UI 配置（8~10）。
     *
     * 【5.5.0 默认 5 → 8】用户反馈「晃手机、伸懒腰、翻身都会累计步数并误触发预览」，
     * 要求「连续走 8 步为最低标准」。5 步时两三下带规律的晃动就能凑满；
     * 8 步（正常步频约 4~5 秒、慢走约 10 秒）才是「人真的开始走路」的最小单位。
     * 队列上限 10（见 feedAcc 里的 `peakIntervals.size > 10`），8 在窗口内可达。
     */
    var confirmSteps: Int = 8

    fun setGaitStateCallback(callback: GaitStateCallback?) {
        gaitStateCallback = callback
    }

    /**
     * 姿态判定：交给 [AttitudeStabilizer] 做滞回与驻留，本方法只负责把
     * 低通后的重力分量转发过去。
     *
     * 5.2.0 之前这里是单帧直判（`abs(gz) > 8.5 → LAY_FLAT`），没有滞回也没有
     * 驻留，握持微抖就会反复翻转。判定细节与阈值理由见 [AttitudeStabilizer]。
     */
    private fun updateAttitude(gx: Float, gy: Float, gz: Float, now: Long) {
        attitudeStabilizer.feed(gx, gy, gz, now)
    }

    fun feedAcc(ax: Float, ay: Float, az: Float): Boolean =
        feedAcc(ax, ay, az, System.currentTimeMillis())

    /**
     * 带时间戳的重载：让单元测试可以喂「虚拟时间」的合成波形，
     * 不用真的 sleep 三秒去验证步态判定。
     *
     * @return true 表示这一帧构成一个「合格步」（间隔落在步频窗口内）
     */
    fun feedAcc(ax: Float, ay: Float, az: Float, now: Long): Boolean {
        // 【5.7.3-lx102 · 114】每帧先做时效剔除，不能只在「又收到一个合格峰」时才剔。
        // 旧实现的后果：人停下后不再有新峰，窗口里的 10 个旧峰永远不会过期，
        // confirmedStepCount() 一直满格，判停 / 提醒状态都看这个数字 → 永远认为还在走。
        dropStalePeaks(now)
        // —— 0. 重力初值直接用第一帧 ——
        // 不能从 0 开始收敛：那会在前两秒造出一个 9.4 m/s² 的假峰，
        // 把包络和阈值一起顶死，整个采样窗口作废。
        if (!gravityInit) {
            gravityX = ax
            gravityY = ay
            gravityZ = az
            gravityInit = true
            lastSampleTs = now
            return false
        }

        // —— 1. 低通分离重力 ——
        gravityX = alpha * gravityX + (1 - alpha) * ax
        gravityY = alpha * gravityY + (1 - alpha) * ay
        gravityZ = alpha * gravityZ + (1 - alpha) * az

        val linX = ax - gravityX
        val linY = ay - gravityY
        val linZ = az - gravityZ

        // —— 1.2 垂直（重力方向）能量占比 ——
        // 见 [emaVertical]：走路是沿重力的上下冲击，手持晃动是水平面内的往复。
        // 这里把线性加速度投影到重力方向，分别统计垂直/水平能量，做 EMA 平滑，
        // 供下面的峰闸门使用（采样 50Hz，0.9 的系数对应约 0.2s 的时间常数）。
        val gNorm = sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)
        if (gNorm > 1e-3f) {
            val linG = (linX * gravityX + linY * gravityY + linZ * gravityZ) / gNorm
            val linH2 = (linX * linX + linY * linY + linZ * linZ) - linG * linG
            emaVertical = MotionConfig.VERTICAL_EMA * emaVertical + (1f - MotionConfig.VERTICAL_EMA) * linG * linG
            emaHorizontal = MotionConfig.VERTICAL_EMA * emaHorizontal + (1f - MotionConfig.VERTICAL_EMA) * max(0f, linH2)
        }

        updateAttitude(gravityX, gravityY, gravityZ, now)

        // —— 1.5 预热：等重力收敛，别让启动瞬态污染包络 ——
        if (warmupLeft > 0) {
            warmupLeft--
            lastSampleTs = now
            return false
        }

        // —— 2. 主轴自适应 ——
        // 手机在口袋里是斜的、在手里是竖的、放在包里可能是横的。
        // 固定看某一个轴，换个姿势就完全测不到；这里取最近能量最大的那个轴。
        emaX = emaAlpha * emaX + (1 - emaAlpha) * linX * linX
        emaY = emaAlpha * emaY + (1 - emaAlpha) * linY * linY
        emaZ = emaAlpha * emaZ + (1 - emaAlpha) * linZ * linZ
        val value = when {
            emaX >= emaY && emaX >= emaZ -> { currentAxis = 0; linX }
            emaY >= emaZ -> { currentAxis = 1; linY }
            else -> { currentAxis = 2; linZ }
        }

        // —— 3. 包络阈值 ——
        val dtMs = if (lastSampleTs == 0L) 20.0 else (now - lastSampleTs).coerceIn(5L, 500L).toDouble()
        lastSampleTs = now
        // 包络封顶：拿放/翻转手机的大瞬态（>8）不参与包络更新，
        // 否则阈值被顶到 7~8，之后几秒正常步伐全被压住
        val rawEnv = max(abs(value), (envelope * exp(-dtMs / MotionConfig.ENV_DECAY_MS)).toFloat())
        envelope = min(rawEnv, MotionConfig.MAX_PEAK_VALUE)

        // 平躺时阈值放大 1.45 倍：躺着刷手机不应该被算成走路
        var threshold = max(MotionConfig.MIN_PEAK_THRESHOLD, envelope * MotionConfig.ENV_FACTOR)
        if (currentAttitude == PhoneAttitude.LAY_FLAT) threshold *= MotionConfig.LAY_FLAT_WEIGHT
        currentAdaptiveThreshold = threshold

        // —— 4. 上升沿计峰（仅限正常步行幅值区间，>MAX_PEAK_VALUE 的瞬态不计） ——
        // 【5.6.4 垂直轴闸门】幅值够了还不够，能量还必须主要沿重力方向：
        // 手持前后/左右晃动、转身、举起来拍照，幅值和间隔都像步，但能量在水平面，
        // 占比会明显低于 0.5；真走路的上下冲击则以垂直能量为主。
        val verticalOk = peakGateOk(value)
        if (value > threshold && value <= MotionConfig.MAX_PEAK_VALUE
            && verticalOk
        ) {
            if (!aboveThreshold) {
                aboveThreshold = true
                val since = if (lastPeakTime == 0L) Long.MAX_VALUE else now - lastPeakTime
                // 不应期：200ms 内的重复上穿不算，挡掉同一脚步的抖动与主轴切换
                if (since < MotionConfig.REFRACTORY_MS) gateRefractoryDrop++
                if (since >= MotionConfig.REFRACTORY_MS) {
                    if (lastPeakTime != 0L) {
                        if (since > MotionConfig.GAIT_RESET_MS) {
                            // 真断步：超过 2.5s 没有新峰（停下来很久），重新累计，
                            // 防止「走 2 步 → 停 10 秒 → 抖 3 下」凑够步数误触发
                            peakIntervals.clear()
                            peakAxes.clear()
                            peakTs.clear()
                        } else {
                            val freq = 1000.0 / since
                            if (freq in MotionConfig.MIN_STEP_FREQ..MotionConfig.MAX_STEP_FREQ) {
                                peakIntervals.add(since)
                                peakAxes.add(currentAxis)
                                peakTs.add(now)
                                // 先按时间剔除陈旧峰：久坐/蹲着时稀疏微动会把窗口吊在满格，
                                // 让下面的数量上限永远命中不到，必须靠时效解除（见 STEP_WINDOW_MS）
                                dropStalePeaks(now)
                                // 队列上限必须 ≥ confirmSteps 的最大档（10），
                                // 否则 confirmSteps=5 时 size 永远到不了 5，走了也不触发
                                if (peakIntervals.size > 10) {
                                    peakIntervals.removeAt(0)
                                    peakAxes.removeAt(0)
                                    peakTs.removeAt(0)
                                }
                                lastPeakTime = now
                                // 【5.3.8 审查返工】判停时钟：本帧是「步频窗口内的合格步峰」，
                                // 记下它的时间戳。刻意放在 confirmSteps 门槛判断之前 —— 门槛设成 8
                                // 攒不满也照常推进；静止 / 无峰 / 平放抖动走不到这里，时钟自然冻结。
                                // 越阈但频率落窗口外的兜底峰（下方 lastPeakTime = now）不写这里。
                                lastStepActivityTs = now
                                // 有运动，重置静置计时器
                                staticDetectTimerStart = null
                                gaitStateCallback?.onLongStaticDetected(false)
                                // 抗误触：连续攒够 confirmSteps 个合格步、且主轴一致
                                // 才认定「走起来」。否则单手刷手机的两下抖动也会把预览弹出来。
                                if (peakIntervals.size >= confirmSteps && axisStable()) {
                                    // 【5.6.0 平放修复】平放不再在这里一票否决。
                                    //
                                    // 5.5.1 及以前：`if (LAY_FLAT) return false`，于是
                                    // 「手机在兜里/平着拿走路」永远吐不出合格步，配合
                                    // [WalkConfirmPolicy] 的同款否决 + 服务层 credible 闸，
                                    // 形成三处串联硬拦；而唯一的补救分支「平放 + 硬件步数≥8」
                                    // 在缺少 ACTIVITY_RECOGNITION 的机型上又恒不成立（通道 STALE）。
                                    // 真机代价：18 分钟步行只激活 2 次，LAY_FLAT 占回调的 64%。
                                    //
                                    // 现在只把「这一帧的峰是平放姿态下产生的」当作事实上报，
                                    // 要不要放行交给上层：上层会用硬件步数与
                                    // [MotionContext] 的位移证据做补偿判定；两条都不成立时照样拦。
                                    // 采集层自己的能力（包络、步频、主轴、不应期）一处未改。
                                    lastPeakFlat = currentAttitude == PhoneAttitude.LAY_FLAT
                                    return true
                                }
                                return false
                            }
                            // 落窗口外但 ≤2.5s：慢走 / 抖动，既不计入也不清空，
                            // 避免把正常慢速散步当成断步
                            if (since <= MotionConfig.GAIT_RESET_MS) gateFreqDrop++
                        }
                    }
                    lastPeakTime = now
                }
            }
        } else {
            if (value > threshold && value <= MotionConfig.MAX_PEAK_VALUE && !verticalOk) {
                if (!verticalDropLatch) {
                    verticalDropLatch = true
                    gateVerticalDrop++
                }
            } else if (value <= threshold) {
                verticalDropLatch = false
            }
            aboveThreshold = false
        }

        // 走到这里说明没有合格步：累积静置时间
        if (staticDetectTimerStart == null) {
            staticDetectTimerStart = now
        }
        val staticDuration = now - staticDetectTimerStart!!
        val isStatic = staticDuration >= SENSOR_STATIC_THRESHOLD
        gaitStateCallback?.onLongStaticDetected(isStatic)

        return false
    }

    /**
     * 【5.6.3】按时间上限剔除陈旧合格峰：只保留最近 [MotionConfig.STEP_WINDOW_MS] 内的峰。
     *
     * 三个队列（间隔 / 主轴 / 绝对时间戳）同步从头部移除，任何时刻长度都必须相等。
     * 语义是「窗口 = 最近一段时间内的连续步流」，而不是「最近 10 个峰，不管隔多久」——
     * 后者是「蹲下后小窗不消失」的直接原因：稀疏到 2s 一个的微动峰也能把满格状态
     * 无限维持，使 qualified / stepTick 恒真、判停时钟被逐帧刷新。
     */
    private fun dropStalePeaks(now: Long) {
        while (peakTs.isNotEmpty() && now - peakTs.first() > MotionConfig.STEP_WINDOW_MS) {
            peakTs.removeAt(0)
            peakIntervals.removeAt(0)
            peakAxes.removeAt(0)
        }
    }

    /** 当前已累计的合格步数（诊断日志用，Java 侧读取） */
    fun confirmedStepCount(): Int = peakIntervals.size

    /**
     * 【5.3.8 审查返工】判停时钟数据源：最近一次「步频窗口内合格步峰」的时间戳（只读，单调递增）。
     *
     * 三条语义，供服务层 StaticStopPolicy 的判停时钟使用：
     * 1. 走路时每个合格步峰都会把它推进到该帧时间；推进点在 confirmSteps 门槛判断之前，
     *    因此门槛设成 8 攒不满（步流走了却不合格判停步）也照常推进，判停不会被误关掉。
     * 2. 静止 / 无峰 / 平放抖动时冻结：越阈但步频落窗口外的兜底峰、纯静止帧都不写它，
     *    所以真停下后时钟不再被伪增量刷成 now，静置超时能把窗口正常收起。
     * 3. 与 confirmedStepCount() / qualified / confirmSteps 门槛、以及服务层的
     *    lastAccelCountSeen 复位完全无关：它只认 GaitDetector 内部的合格步峰，
     *    不看采集层窗口大小，也不被任何复位分支伪造增量。
     *
     * 只在 reset() 里归零；判停动作本身不触碰它。纯观察值，不改动任何既有判定逻辑。
     */
    fun stepActivityTs(): Long = lastStepActivityTs

    /**
     * 【5.6.0】最近一次达标帧是否在平放姿态下产生（只读观察值，不参与任何判定）。
     *
     * 调用方（[WalkPreviewService]）用它决定是否需要额外证据：
     * 平放 + 硬件步数达标 / 平放 + 位移证据成立 → 放行；两条都没有 → 拦。
     */
    fun lastPeakWasFlat(): Boolean = lastPeakFlat

    /**
     * 主轴稳定性：最近 confirmSteps 个合格步里，同一个轴的占比 ≥60% 才算稳定。
     * 走路是规律的上下颠簸（主轴固定），拿在手里乱动方向随机（主轴来回切）。
     *
     * 【5.7.3 由 70% 放宽到 60%】平着拿手机走路时，手臂前后摆会让主轴在
     * 两个轴之间来回切，真机日志里 8 步里通常有 3 步换轴，70% 恒不成立；
     * 60% 仍然是「同一轴过半」的多数判据，乱动的随机切轴很难凑够。
     */
    private fun axisStable(): Boolean {
        if (peakAxes.size < confirmSteps) return false
        var c0 = 0; var c1 = 0; var c2 = 0
        for (a in peakAxes) {
            when (a) { 0 -> c0++; 1 -> c1++; else -> c2++ }
        }
        val max = maxOf(c0, c1, c2)
        // 平放 60%（手臂前后摆导致主轴在两轴间切换是常态），其余姿态维持 70%：
        // 手里乱动的主轴切换被判死的要求不能松，[GaitDetectorPureTest.handShake] 锁着这条。
        val need = if (currentAttitude == PhoneAttitude.LAY_FLAT) 0.6f else 0.7f
        return max >= peakAxes.size * need
    }

    /**
     * 步态是否「稳定」。
     *
     * 注意：2.9.4 起它**不再决定要不要提醒**，只用于自检结论和日志。
     * 容差从 250ms 放宽到 400ms：真人步间隔的抖动，加上手机在口袋里
     * 被裤子带着晃造成的峰位漂移，250ms 会把正常步行判成「不稳」，
     * 而这在真机上是常态 —— 之前正是它把 50 步之后的提醒全部卡住。
     *
     * 【5.1.0 为什么没跟着收紧】
     * 治「手持误触发」时第一时间想动这里：把 ±400ms 的绝对容差改成
     * ±35% 的相对容差，一眼看上去更合理。但试过之后不行 ——
     * 它只服务于 [WalkPreviewService] 里那条「姿态/节律闸门」的**旁路**，
     * 真走路的间隔会被漏峰、口袋晃动拉成 500/600/1000ms 混合，
     * 相对容差 650×0.35=227ms 直接把这类真实步流判死（已有单测覆盖）。
     *
     * 误触发的真正关卡在 [WalkConfirmPolicy]：那里看的是整段步流的形态
     * （平均间隔、最大间隔、节律一致性），且是「要不要显示」的最后一票。
     * 收紧放在那一处，边界清楚、有真机日志做依据，也不会误伤旁路。
     */
    fun hasStableGait(): Boolean {
        // 至少2步间隔稳定就算真走路（采样窗口内2步足够判定）
        if (peakIntervals.size < 2) return false
        val avg = peakIntervals.average()
        return peakIntervals.all { abs(it - avg) < 400 }
    }

    /**
     * 重置「这一段判定」的状态，**但保留重力估计和主轴估计**。
     *
     * 这一点是 2.9.3 之前一直失败的真凶之一：原来 reset 把 gravity 一起清 0，
     * 而 Hook 每次采样前都会 reset。于是每一轮 4 秒采样都要重新从 0 收敛到 9.8，
     * 前 0.6 秒的残差高达 9.4 m/s²，包络被顶到 9.4（4 秒衰减常数下 4 秒后才降到 3.4），
     * 阈值恒高于真实步行幅值 2.5 —— 一个峰都上不去，日志里每轮都是「峰数=0」。
     *
     * 两次采样之间只隔几秒，重力方向根本没变，保留它才是对的。
     */
    fun reset() {
        peakIntervals.clear()
        peakAxes.clear()
        peakTs.clear()
        lastPeakTime = 0L
        lastStepActivityTs = 0L
        lastPeakFlat = false
        aboveThreshold = false
        verticalDropLatch = false
        gateVerticalDrop = 0
        gateFreqDrop = 0
        gateRefractoryDrop = 0
        envelope = 0f
        lastSampleTs = 0L
        currentAdaptiveThreshold = MotionConfig.MIN_PEAK_THRESHOLD
        attitudeStabilizer.reset()
        // gravity / ema / warmupLeft 刻意不清：见上面的说明
        // 清空静置状态
        staticDetectTimerStart = null
        gaitStateCallback?.onLongStaticDetected(false)
    }

    fun getCurrentThreshold(): Float = currentAdaptiveThreshold

    /**
     * 诊断信息：验证失败时写进日志。
     * 能直接区分三种失败原因 —— 一个峰都没测到（信号太弱）、
     * 测到峰但步频不对（窗口外）、步频对但间隔不稳（走得太随意）。
     */
    fun debugInfo(): String {
        val freqText = if (peakIntervals.isEmpty()) "无"
        else peakIntervals.joinToString(",") { "%.2fHz".format(1000.0 / it) }
        // 【5.7.3】拒因计数：每打一次日志清零，保证这一行反映「刚过去的 10 秒」
        // 卡在哪一道闸门，不用再靠猜（阈值 / 垂直 / 步频 / 不应期）。
        val gateText = "闸门[垂直挡=$gateVerticalDrop 步频挡=$gateFreqDrop 间隔挡=$gateRefractoryDrop]"
        gateVerticalDrop = 0
        gateFreqDrop = 0
        gateRefractoryDrop = 0
        return "峰数=${peakIntervals.size} 步频=[$freqText] 阈值=%.2f 包络=%.2f 垂直=%.2f 姿态=$currentAttitude $gateText"
            .format(currentAdaptiveThreshold, envelope, verticalRatio())
    }
}
