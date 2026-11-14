package io.github.wumisc.safewalk

/**
 * 相机预览的「方向」策略（纯逻辑：不碰 Camera2、不碰 View，便于 JVM 单测）。
 *
 * ## 为什么需要它
 *
 * 相机传感器是**物理固定**在机身里的，绝大多数手机的后摄是「横着装」的
 * （`SENSOR_ORIENTATION = 90`）。而屏幕可以竖、可以横、还能倒过来。
 * Camera2 的预览流本身**不做任何旋转**：它把传感器那一帧原样塞进 Surface。
 * 于是同一个画面：
 *
 * | 设备姿势 | 屏幕旋转 | 需要补偿 |
 * | --- | --- | --- |
 * | 竖直握着 | 0° | 顺时针转 90° |
 * | 横过来（左转） | 90° | 不转 |
 * | 倒过来 | 180° | 顺时针转 270° |
 * | 横过来（右转） | 270° | 转 180° |
 *
 * 以前这里完全没有旋转逻辑，只把输出缓冲尺寸设成小窗的宽高。竖屏时
 * 靠「拉成小窗比例」勉强能看，一旦横过来，画面就是侧躺的 —— 用户反馈
 * 「横屏时方向不对」，说的正是这件事。
 *
 * ## 三件事分开算，才能各自被验证
 *
 * 1. [rotationDegrees]：该转多少度。只看传感器方向与屏幕旋转两个整数。
 * 2. [pickPreviewSize]：输出缓冲该多大。**必须从相机支持的尺寸里挑**，
 *    不能直接写窗口宽高 —— 那等于让 HAL 把 4:3 的画面硬拉成 150:260。
 * 3. [transform]：把「源画面」映射到「窗口」的 3×3 矩阵（旋转 + 等比铺满 + 取景裁剪）。
 *
 * 之所以拆成一个 object 的三条纯函数，是为了在没有真机的情况下也能锁住行为：
 * 矩阵用「源图四个角映射到窗口哪里」来断言，比人眼看截图可靠。
 */
object PreviewOrientationPolicy {

    /** 自动：按传感器与屏幕实际方向转正（默认） */
    const val MODE_AUTO = "auto"
    /** 固定：忽略屏幕旋转，永远按竖屏姿势显示。手机装在支架上固定横放时有用 */
    const val MODE_FIXED = "fixed"
    /** 翻转：在自动结果上再加 180°。少数 ROM 的传感器方向标定相反，用它纠正 */
    const val MODE_FLIP = "flip"

    /** 合法取值的集合，供配置层校验（写错的值一律按自动处理） */
    @JvmStatic
    fun normalizeMode(mode: String?): String = when (mode) {
        MODE_FIXED, MODE_FLIP -> mode
        else -> MODE_AUTO
    }

    /**
     * 屏幕旋转（`Display.getRotation()` / `Surface.ROTATION_*`）→ 角度。
     *
     * 刻意写成 0/1/2/3 的整数判断，而不是引用 `android.view.Surface.ROTATION_90`：
     * 常量虽然会被内联，但纯 JVM 环境下 android.jar 是空壳，引用它会让这条
     * 最需要被测试的映射在没有 Robolectric 时不可用。注释里写清对应关系即可。
     */
    @JvmStatic
    fun displayDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        1 -> 90   // Surface.ROTATION_90
        2 -> 180  // Surface.ROTATION_180
        3 -> 270  // Surface.ROTATION_270
        else -> 0 // Surface.ROTATION_0
    }

    /**
     * 计算画面需要顺时针旋转的角度。
     *
     * 后摄：`传感器角度 - 屏幕角度`。传感器 90°、屏幕 0° → 转 90°；
     * 屏幕转到 90°（横屏）→ 0°，画面本来就正了。
     * 前摄是镜像安装，方向关系相反：`传感器角度 + 屏幕角度`。
     *
     * 结果归一到 0 / 90 / 180 / 270：矩阵只需要这四个角，中间值没有意义。
     */
    @JvmStatic
    fun rotationDegrees(
        sensorOrientation: Int,
        displayRotationDeg: Int,
        frontFacing: Boolean
    ): Int {
        val s = ((sensorOrientation % 360) + 360) % 360
        val d = ((displayRotationDeg % 360) + 360) % 360
        val raw = if (frontFacing) (s + d) % 360 else (s - d + 360) % 360
        // 四舍五入到最近的 90°，避免 89 / 271 这种脏数据算出半格
        return ((raw + 45) / 90 % 4) * 90
    }

    /** 把用户选择的模式叠加到自动计算结果上 */
    @JvmStatic
    fun applyMode(autoDeg: Int, mode: String?): Int = when (normalizeMode(mode)) {
        MODE_FIXED -> 0
        MODE_FLIP -> (autoDeg + 180) % 360
        else -> autoDeg
    }

    /**
     * 从相机支持的输出尺寸里挑一个给预览用。
     *
     * 判据：**长宽比最接近「转正后的窗口」**，其次分辨率大的优先。
     *
     * 为什么不能直接用窗口宽高：`setDefaultBufferSize(150dp, 260dp)` 会让 HAL
     * 把 4:3 的画面硬缩成 0.58 的长条，路面被压扁，方向再对也没用。
     *
     * @param candidates 扁平化的 `{w0,h0,w1,h1,…}`，来自
     *        `StreamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)`
     * @return 命中的 `[w, h]`；没有任何可用尺寸时返回 null（调用方退回窗口尺寸）
     */
    @JvmStatic
    fun pickPreviewSize(
        candidates: Array<IntArray>?,
        viewW: Int,
        viewH: Int,
        rotationDeg: Int
    ): IntArray? {
        if (candidates == null || candidates.isEmpty()) return null
        if (viewW <= 0 || viewH <= 0) return null

        // 转 90/270 时，源画面的「宽」对应窗口的「高」
        val swap = rotationDeg == 90 || rotationDeg == 270
        val target = if (swap) viewH.toDouble() / viewW else viewW.toDouble() / viewH

        // 先按「比例」筛，再按「分辨率」挑。两轮分开写而不是塞进一个循环里
        // 用一堆 && 兜：那种写法一旦条件写反，结果只是「挑了个奇怪尺寸」，
        // 不会报错，等到真机画面变形才发现。
        val usable = ArrayList<IntArray>(candidates.size)
        for (c in candidates) {
            // 元素是 IntArray，类型上不为 null；真正要防的是 Java 侧传进来的短数组和 0/负数。
            // 写 `c == null` 编译器会报「恒为假」，等于把注意力浪费在一个不会发生的情况上。
            if (c.size < 2) continue
            val w = c[0]
            val h = c[1]
            if (w <= 0 || h <= 0) continue
            // 只要「能看清路面」的档位：640×480 起步。太小的尺寸在截图放大后全是噪点，
            // 画面增强再一拉对比度，暗部就是一整片斑块，反而更看不清。
            if (maxOf(w, h) < 640 || minOf(w, h) < 480) continue
            usable.add(c)
        }
        if (usable.isEmpty()) return null

        // 1080p 以内优先：4K 预览对这颗小窗没有任何好处，只是白烧电和带宽
        val budget = usable.filter { it[0].toLong() * it[1] <= 1920L * 1080L }
        val pool = if (budget.isNotEmpty()) budget else usable

        var best: IntArray? = null
        var bestDiff = Double.MAX_VALUE
        var bestArea = 0L
        for (c in pool) {
            val w = c[0]
            val h = c[1]
            val shortSide = minOf(w, h).toDouble()
            if (shortSide <= 0.0) continue
            val aspect = maxOf(w, h) / shortSide
            val diff = Math.abs(aspect - target)
            val area = w.toLong() * h
            val better = when {
                best == null -> true
                // 比例差 2% 以内算同一档（16:9 与 16:10 不该互相淘汰），此时选更大的
                Math.abs(diff - bestDiff) < 0.02 -> area > bestArea
                else -> diff < bestDiff
            }
            if (better) {
                best = intArrayOf(w, h)
                bestDiff = diff
                bestArea = area
            }
        }
        return best
    }

    /**
     * 生成「源画面 → 窗口」的 3×3 矩阵（行主序 9 个 float，可直接喂给
     * `android.graphics.Matrix.setValues`）。
     *
     * 变换顺序（从右往左作用于源坐标）：
     *   1. 平移到源图中心；
     *   2. 顺时针旋转 [rotationDeg]；
     *   3. 等比缩放 [scale]，使旋转后的画面**铺满**窗口（多出来的部分裁掉，不留黑边）；
     *   4. 平移到窗口中心；
     *   5. 取景：以**窗口中心**为锚点再放大 1/(1-k)，然后上移，只留下半部分。
     *
     * 为什么取景放在最后、按窗口坐标算：用户拖的是「屏幕上看到的那一段」，
     * 而不是源图坐标。放最后，竖屏横屏、转 90° 还是 180°，行为都一致：
     * 永远只留下屏幕下半部分。
     */
    @JvmStatic
    fun transform(
        viewW: Int,
        viewH: Int,
        srcW: Int,
        srcH: Int,
        rotationDeg: Int,
        framingPct: Int
    ): FloatArray {
        if (viewW <= 0 || viewH <= 0 || srcW <= 0 || srcH <= 0) {
            return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        val rot = ((rotationDeg % 360) + 360) % 360
        val swap = rot == 90 || rot == 270

        // 旋转后的包围盒（单位：源像素）
        val effW = if (swap) srcH.toDouble() else srcW.toDouble()
        val effH = if (swap) srcW.toDouble() else srcH.toDouble()
        // 等比铺满：取两个方向缩放比的较大者，保证不留黑边
        val scale = maxOf(viewW / effW, viewH / effH)

        // 四个直角直接用解析值，避免 cos(90°) 算出 6e-17 这种脏数
        val cos: Double
        val sin: Double
        when (rot) {
            90 -> { cos = 0.0; sin = 1.0 }
            180 -> { cos = -1.0; sin = 0.0 }
            270 -> { cos = 0.0; sin = -1.0 }
            else -> { cos = 1.0; sin = 0.0 }
        }

        val cx = srcW / 2.0
        val cy = srcH / 2.0
        // 线性部分：先缩放再旋转（各向同性缩放与旋转可交换）
        val m00 = scale * cos
        val m01 = -scale * sin
        val m10 = scale * sin
        val m11 = scale * cos
        // 平移部分：让源图中心落在窗口中心。
        // 逐项与上面的线性部分对齐：x' = m00*x + m01*y + tx，y' = m10*x + m11*y + ty
        val tx = viewW / 2.0 - (m00 * cx + m01 * cy)
        val ty = viewH / 2.0 - (m10 * cx + m11 * cy)

        // ---- 取景裁剪（屏幕坐标，纵向）----
        val f = framingPct.coerceIn(0, 100) / 100.0
        val k = f * 0.5                      // 裁掉的比例：0 → 不裁，1 → 只留下半
        val zoom = 1.0 / (1.0 - k)           // 裁 k 后要放大 1/(1-k) 才能铺满
        val cw = viewW / 2.0
        val ch = viewH / 2.0
        // 放大必须以**窗口中心**为锚点，不能直接乘在 tx/ty 上：
        // `zoom * tx` 是绕原点缩放，中心会被推到右下，画面整体跑偏。
        // 绕中心缩放的平移项 = zoom*t + c - zoom*c。
        val anchorX = cw - zoom * cw
        val anchorY = ch - zoom * ch
        // 放大后「下半部分」占据的区间是 [k*H, H]，它的上沿被放大到
        // ch + zoom*(k*H - ch)，再上移这么多，正好让它贴住屏幕上边。
        val shiftY = -(ch + zoom * (k * viewH - ch))

        return floatArrayOf(
            (zoom * m00).toFloat(), (zoom * m01).toFloat(), (zoom * tx + anchorX).toFloat(),
            (zoom * m10).toFloat(), (zoom * m11).toFloat(),
            (zoom * ty + anchorY + shiftY).toFloat(),
            0f, 0f, 1f
        )
    }

    /** 一行日志：出问题时用户回传日志，这一行就够定位是哪一环算错了 */
    @JvmStatic
    fun describe(
        sensorOrientation: Int,
        displayRotationDeg: Int,
        mode: String?,
        autoDeg: Int,
        finalDeg: Int,
        srcW: Int,
        srcH: Int
    ): String {
        return "方向 | 传感器=${sensorOrientation}° 屏幕=${displayRotationDeg}°" +
            " 模式=${normalizeMode(mode)}" +
            " → 自动=${autoDeg}° 实转=${finalDeg}°" +
            " 缓冲=${srcW}x${srcH}"
    }
}
