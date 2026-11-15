package io.github.wumisc.safewalk

/**
 * 用**重力方向**判断手机当前怎么拿，决定小窗是竖条还是横条（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么不能再用 `Display.getRotation()`
 *
 * 5.3.0 用屏幕旋转角算画面方向，真机日志（2026-11-15，2.5 小时）里只有
 * `SCREEN_ON/OFF`，**一次方向变化都没有** —— 用户手机开着「旋转锁定」，
 * `getRotation()` 恒为 0。于是：
 *
 * · 手机横过来，系统不转，预览也不转 → 画面侧躺；
 * · 竖着拿时，窗口是 150×260 的竖条，横向的传感器画面硬塞进去，
 *   只剩中间一条能看，两侧大片空 → 用户说的「一小块，空余很大」。
 *
 * 旋转锁定是走路时的常态（很多人一直开着），所以**姿态必须自己测**：
 * 加速度计给出的重力向量与屏幕无关，横就是横、竖就是竖。
 *
 * ## 三件事分开算
 *
 * 1. [rawDegrees]：重力向量 → 0/90/180/270（顺时针为正，与 `Display` 语义一致）。
 * 2. [PoseTracker]：加**迟滞 + 驻留**，避免走路时手臂晃动导致窗口反复横竖跳。
 * 3. [windowSize]：朝向决定窗口是「竖条 150×260」还是「横条 260×150」。
 *
 * ## 坐标约定
 *
 * 设备坐标：x 指向屏幕右侧，y 指向屏幕上方，z 垂直屏幕向外。
 * 竖直握持时重力沿 +y（加速度计报的是反作用力，所以读数为正）。
 * 设备相对自然方向逆时针转过 θ 时：
 *
 * ```
 * ax = g·sinθ   ay = g·cosθ   →   θ = atan2(ax, ay)
 * ```
 *
 * θ > 0 表示逆时针（顶部转向左边），与 `Surface.ROTATION_90` 的语义一致，
 * 因此可以直接喂给 [PreviewOrientationPolicy.rotationDegrees]。
 */
object DevicePosePolicy {

    /** 竖屏（自然方向） */
    const val DEG_PORTRAIT = 0

    /** 横屏，顶部朝左（设备逆时针转 90°） */
    const val DEG_LANDSCAPE_CCW = 90

    /** 倒置 */
    const val DEG_UPSIDE_DOWN = 180

    /** 横屏，顶部朝右（设备顺时针转 90°） */
    const val DEG_LANDSCAPE_CW = 270

    /** 还没有可信样本（刚注册传感器、或一直平放） */
    const val NO_SAMPLE = -1

    /**
     * 平放判定阈值：|az| 超过 7 m/s² 且明显大于 ax/ay 时，
     * 说明屏幕朝天或朝地，此时 ax/ay 只剩噪声，姿态不可信。
     * 走路时手机拿在手里很少平放，但放在桌上/口袋里会。
     */
    const val FLAT_AZ = 7.0f

    /**
     * 迟滞带（度）。离开当前朝向超过 `45 + 8 = 53°` 才考虑切换。
     *
     * 不用 45° 整：走路时手腕摆动很容易在 44~46° 之间来回，卡在边界上
     * 就是每秒切一次窗口，画面会跳成幻灯片。
     */
    const val HYST_DEG = 8.0

    /** 驻留时长：新朝向必须连续保持这么久才真的切（毫秒） */
    const val DWELL_MS = 450L

    /** 平放判定 */
    @JvmStatic
    fun isFlat(ax: Float, ay: Float, az: Float): Boolean {
        val absZ = Math.abs(az)
        if (absZ < FLAT_AZ) return false
        return absZ >= Math.abs(ax) && absZ >= Math.abs(ay)
    }

    /** 重力方向 → 0/90/180/270（四舍五入到最近的 90°） */
    @JvmStatic
    fun rawDegrees(ax: Float, ay: Float): Int {
        if (ax == 0f && ay == 0f) return DEG_PORTRAIT
        val theta = Math.toDegrees(Math.atan2(ax.toDouble(), ay.toDouble()))
        return normalize(Math.round(theta / 90.0).toInt() * 90)
    }

    /** 归一到 [0,360) */
    @JvmStatic
    fun normalize(deg: Int): Int = ((deg % 360) + 360) % 360

    /** 两个角度之间的最短夹角（0~180） */
    @JvmStatic
    fun angleDistance(a: Double, b: Int): Double {
        var d = Math.abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    /** 该朝向是不是横着（窗口要变成横条） */
    @JvmStatic
    fun isLandscapePose(deg: Int): Boolean = deg == DEG_LANDSCAPE_CCW || deg == DEG_LANDSCAPE_CW

    /**
     * 窗口是否要跟着转成横条。
     *
     * 「锁定竖屏」档不跟：用户明确要求不管怎么拿都保持竖条（夹在支架上、躺着看）。
     * 其余两档（跟随 / 翻转）都跟 —— 翻转只影响画面内容，不影响窗口形状。
     */
    @JvmStatic
    fun isLandscapeWindow(poseDeg: Int, mode: String?): Boolean {
        if (PreviewOrientationPolicy.normalizeMode(mode) == PreviewOrientationPolicy.MODE_FIXED) {
            return false
        }
        return isLandscapePose(poseDeg)
    }

    /**
     * 小窗像素尺寸（dp）：竖屏 [短边, 短边×比例]，横屏 [短边×比例, 短边]。
     *
     * 用户调的是**短边**，长边按 150:260 固定比例换算 —— 这样横竖切换时
     * 窗口面积不变，只是旋转 90°，不会出现「横屏忽然变成一大块」。
     */
    @JvmStatic
    fun windowSize(shortSideDp: Int, aspect: Float, landscape: Boolean): IntArray {
        val longSide = Math.round(shortSideDp * aspect)
        return if (landscape) intArrayOf(longSide, shortSideDp)
        else intArrayOf(shortSideDp, longSide)
    }

    /**
     * 姿态跟踪器：把「抖动的重力向量」变成「稳定的窗口朝向」。
     *
     * 两道闸：
     *  · **迟滞**：离开当前朝向不足 53° 一律不换（防手腕轻摆）；
     *  · **驻留**：越过阈值后还要连续维持 [dwellMs] 才生效（防转身瞬间的过冲）。
     *
     * 平放时整帧丢弃、保持上一次朝向：桌上平放不等于「横屏」，这时换窗口形状
     * 只会让用户莫名其妙。
     */
    class PoseTracker @JvmOverloads constructor(
        private val dwellMs: Long = DWELL_MS,
        startDeg: Int = NO_SAMPLE
    ) {
        private var stable = startDeg
        private var candidate = NO_SAMPLE
        private var candidateSince = 0L

        /** 当前稳定朝向；[NO_SAMPLE] 表示还没有可信样本 */
        fun stable(): Int = stable

        /** 清空状态（重新显示预览时调用，避免沿用上一次会话的残留朝向） */
        fun reset() {
            stable = NO_SAMPLE
            candidate = NO_SAMPLE
            candidateSince = 0L
        }

        /**
         * 喂一帧重力样本，返回**当前稳定朝向**（可能没变）。
         *
         * 调用方拿返回值和上一次比对，不同才动窗口 —— 这里不做节流，
         * 因为「变没变」本身就是节流的结果。
         */
        fun update(ax: Float, ay: Float, az: Float, nowMs: Long): Int {
            if (isFlat(ax, ay, az)) {
                candidate = NO_SAMPLE
                return stable
            }
            if (stable == NO_SAMPLE) {
                // 第一帧可信样本直接采纳：越早定下形状，用户越少看到「先竖后横」
                stable = rawDegrees(ax, ay)
                candidate = NO_SAMPLE
                return stable
            }
            val theta = Math.toDegrees(Math.atan2(ax.toDouble(), ay.toDouble()))
            // 还在当前朝向的容忍带里：不换，也不记账
            if (angleDistance(theta, stable) <= 45.0 + HYST_DEG) {
                candidate = NO_SAMPLE
                return stable
            }
            val target = rawDegrees(ax, ay)
            if (target != candidate) {
                candidate = target
                candidateSince = nowMs
                return stable
            }
            if (nowMs - candidateSince >= dwellMs) {
                stable = target
                candidate = NO_SAMPLE
            }
            return stable
        }
    }
}
