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
     * 真实散步大约 0.7~1.1Hz（每步 900~1400ms），快走 1.5~2Hz。
     * 下限取 0.5Hz（每步最多 2000ms），覆盖慢走到快跑；
     * 3Hz 以上的抖腿 / 摇手机仍然挡在外面。
     */
    const val MIN_STEP_FREQ = 0.5
    const val MAX_STEP_FREQ = 2.5
    /**
     * 真断步阈值：超过这个时长没有新峰，才判定「停下很久、重新数」。
     * 必须明显宽于步频下限（2000ms），否则正常慢速散步会被当成断步清空，
     * 计数永远攒不满 → 走了也不触发。
     */
    const val GAIT_RESET_MS = 2500L
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
    const val LAY_FLAT_WEIGHT = 1.45f
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
    /** 上一帧是否已经在阈值之上：只认「上穿」的那一刻，避免一个波峰算成好几步 */
    private var aboveThreshold = false
    private val peakIntervals = mutableListOf<Long>()

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
    /** 连续合格步达到这个数才认定「走起来」，防刷手机误触。可由 UI 配置（2~10） */
    var confirmSteps: Int = 5

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
        if (value > threshold && value <= MotionConfig.MAX_PEAK_VALUE) {
            if (!aboveThreshold) {
                aboveThreshold = true
                val since = if (lastPeakTime == 0L) Long.MAX_VALUE else now - lastPeakTime
                // 不应期：200ms 内的重复上穿不算，挡掉同一脚步的抖动与主轴切换
                if (since >= MotionConfig.REFRACTORY_MS) {
                    if (lastPeakTime != 0L) {
                        if (since > MotionConfig.GAIT_RESET_MS) {
                            // 真断步：超过 2.5s 没有新峰（停下来很久），重新累计，
                            // 防止「走 2 步 → 停 10 秒 → 抖 3 下」凑够步数误触发
                            peakIntervals.clear()
                            peakAxes.clear()
                        } else {
                            val freq = 1000.0 / since
                            if (freq in MotionConfig.MIN_STEP_FREQ..MotionConfig.MAX_STEP_FREQ) {
                                peakIntervals.add(since)
                                peakAxes.add(currentAxis)
                                // 队列上限必须 ≥ confirmSteps 的最大档（10），
                                // 否则 confirmSteps=5 时 size 永远到不了 5，走了也不触发
                                if (peakIntervals.size > 10) {
                                    peakIntervals.removeAt(0)
                                    peakAxes.removeAt(0)
                                }
                                lastPeakTime = now
                                // 有运动，重置静置计时器
                                staticDetectTimerStart = null
                                gaitStateCallback?.onLongStaticDetected(false)
                                // 抗误触：连续攒够 confirmSteps 个合格步、且主轴一致
                                // 才认定「走起来」。否则单手刷手机的两下抖动也会把预览弹出来。
                                if (peakIntervals.size >= confirmSteps && axisStable()) {
                                    // 平躺（床上/沙发平放刷手机）姿态不判走：走路时手机随身体
                                    // 上下摆动会转成 TILTED/UPRIGHT，稳定 LAY_FLAT 基本是平放
                                    // 误触。步仍累计，姿态转正后照常触发，不误杀走路。
                                    if (currentAttitude == PhoneAttitude.LAY_FLAT) {
                                        return false
                                    }
                                    return true
                                }
                                return false
                            }
                            // 落窗口外但 ≤2.5s：慢走 / 抖动，既不计入也不清空，
                            // 避免把正常慢速散步当成断步
                        }
                    }
                    lastPeakTime = now
                }
            }
        } else {
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

    /** 当前已累计的合格步数（诊断日志用，Java 侧读取） */
    fun confirmedStepCount(): Int = peakIntervals.size

    /**
     * 主轴稳定性：最近 confirmSteps 个合格步里，同一个轴的占比 ≥70% 才算稳定。
     * 走路是规律的上下颠簸（主轴固定），拿在手里乱动方向随机（主轴来回切）。
     */
    private fun axisStable(): Boolean {
        if (peakAxes.size < confirmSteps) return false
        var c0 = 0; var c1 = 0; var c2 = 0
        for (a in peakAxes) {
            when (a) { 0 -> c0++; 1 -> c1++; else -> c2++ }
        }
        val max = maxOf(c0, c1, c2)
        return max >= peakAxes.size * 0.7
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
        lastPeakTime = 0L
        aboveThreshold = false
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
        return "峰数=${peakIntervals.size} 步频=[$freqText] 阈值=%.2f 包络=%.2f 姿态=$currentAttitude"
            .format(currentAdaptiveThreshold, envelope)
    }
}
