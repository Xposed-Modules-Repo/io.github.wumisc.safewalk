package io.github.wumisc.safewalk

/**
 * 路况预览的「镜头选择」策略（纯逻辑：不碰 Camera2、不碰 View，便于 JVM 单测）。
 *
 * ## 背景
 *
 * 一台手机往往有多颗后置摄像头：主摄、超广角、长焦……Camera2 里它们只是
 * `CameraManager.getCameraIdList()` 里几个平等的字符串 id，系统**不保证**哪个
 * 是主摄、也不保证顺序。App2 之前的实现 `backCameraId()` 只会返回「第一颗
 * facing=BACK 的摄像头」，具体是哪颗完全看厂商心情 —— 用户没法选超广角。
 *
 * ## 怎么认出超广角
 *
 * 唯一跨厂商可靠的判据是焦距：[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]。
 * 超广角的等效焦距明显短于主摄（典型 0.5x，即主摄焦距的 ~0.5 倍）。本策略把
 * 「所有后置摄像头里**最短焦距**那颗」判为超广角，但只有它比「最长焦距那颗」
 * 短到 [ULTRAWIDE_FOCAL_RATIO] 以下才算 —— 避免把「主摄 + 长焦」这种双摄
 * 误判成有超广角（那样会把长焦当主摄、把主摄当超广角，画面全乱）。
 *
 * ## 回退
 *
 * 用户选了超广角、但设备其实没有（单摄 / 双摄但无广角）→ 一律回退到**主摄**。
 * 主摄的判定用「相机 id 列表里第一颗后置」——这是绝大多数 Android 机型的约定
 * （id "0" 就是逻辑主摄），也正是改动前的旧行为，最稳。用焦距找「最长那颗」
 * 反而会误选长焦，所以绝不那样做。
 *
 * 选中集的 id 由 [select] 返回，渲染层只管照它 openCamera，选镜头与开相机彻底解耦。
 */
object CameraLensPolicy {

    /** 跟随默认：等价于超广角（带回退）—— 用户明确「也可默认选择超广角」 */
    const val MODE_AUTO = "auto"
    /** 只用主摄 */
    const val MODE_MAIN = "main"
    /** 优先超广角，没有则回退主摄 */
    const val MODE_ULTRAWIDE = "ultrawide"

    // 兼容别名：预览样式三级页（PreviewStyleActivity）用 LENS_* 命名同一组档位，
    // 两个入口写同一个偏好、走同一套策略。值必须与 MODE_* 完全一致。
    const val LENS_MAIN = MODE_MAIN
    const val LENS_ULTRAWIDE = MODE_ULTRAWIDE

    /**
     * 判定「有超广角」的焦距比阈值：最短焦距 ≤ 最长焦距 × 该值才算广角。
     * 0.75 留足余量（超广角典型 0.5x），又不至于把同焦距的双摄误判成广角。
     */
    const val ULTRAWIDE_FOCAL_RATIO = 0.75f

    /**
     * 逻辑摄像头切超广角要下发的 zoom ratio。0.6 ≈ 业界通称的「0.6x 超广角」，
     * 系统会据此把物理镜头从主摄切到超广角；在只暴露一颗后置 id 的机型上，
     * 这是唯一可行的超广角入口（见 [zoomForLogicalUltrawide]）。
     */
    const val ULTRAWIDE_ZOOM = 0.6f

    /**
     * 逻辑摄像头场景：要不要下发 [ULTRAWIDE_ZOOM]，即 0.6x 超广角。
     *
     * 现代机型（小米 / OPPO / vivo / Pixel / 多数三星）只暴露一颗后置 id，
     * 超广角靠 zoom ratio 切换——`CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE`
     * 的下限 < 1.0 就说明它支持广角端。此时把 [ULTRAWIDE_ZOOM] 下发到
     * `CaptureRequest.CONTROL_ZOOM_RATIO`，HAL 会自动切到超广角物理镜头，
     * 画面就是用户说的「0.6 倍裁切 / 0.6x 视野」。
     *
     * 下限 ≥ 1.0 或读不到 range → 该逻辑摄像头没有广角端，返回 null（保持主摄 1.0），
     * 配合独立 id 路径的回退，实现「无超广角用主摄代替」。
     *
     * @param mode        用户选的镜头档位
     * @param zoomLower   CONTROL_ZOOM_RATIO_RANGE.lower；读不到传 null
     * @param zoomUpper   CONTROL_ZOOM_RATIO_RANGE.upper；读不到传 null
     * @return 要下发的 zoom ratio；null 表示保持默认 1.0（不下发该 key）
     */
    @JvmStatic
    fun zoomForLogicalUltrawide(mode: String?, zoomLower: Float?, zoomUpper: Float?): Float? {
        // 用户明确要主摄：绝不下发 <1 的 zoom，保持原生 1.0
        if (normalize(mode) == MODE_MAIN) return null
        // 没有 range：该设备不支持 zoom ratio 控制，走独立 id 路径，这里不干预
        if (zoomLower == null || zoomUpper == null) return null
        if (zoomLower >= 1f || zoomUpper < zoomLower) return null
        // 把目标 0.6 夹在设备支持范围内：下限可能是 0.5，也可能是 0.7
        return ULTRAWIDE_ZOOM.coerceIn(zoomLower, zoomUpper)
    }

    /** 写错的值一律按超广角（默认带回退，是用户认可的默认档） */
    @JvmStatic
    fun normalize(mode: String?): String = when (mode) {
        MODE_MAIN -> MODE_MAIN
        MODE_ULTRAWIDE, MODE_AUTO -> MODE_ULTRAWIDE
        else -> MODE_ULTRAWIDE
    }

    /**
     * 一个候选摄像头。故意只用纯数据类型，不引用 android.hardware.camera2，
     * 这样 JVM 单测能直接构造多摄场景，不需要真机 / Robolectric 的相机桩。
     *
     * @param id 摄像头 id，原样返回给调用方 openCamera
     * @param back 是否后置
     * @param minFocalMm 该摄像头最短可用焦距（mm）；拿不到传 null
     * @param maxFocalMm 该摄像头最长可用焦距（mm）；拿不到传 null
     */
    data class Candidate(
        val id: String,
        val back: Boolean,
        val minFocalMm: Float?,
        val maxFocalMm: Float?
    )

    /**
     * 按用户选择的镜头，从候选里挑出一个摄像头 id。
     *
     * 主摄 = 相机 id 列表里第一颗后置（跨机型最稳的约定，也是旧行为）。
     * 超广角 = 焦距最短、且短到 [ULTRAWIDE_FOCAL_RATIO] 以下的后置；没有就回退主摄。
     *
     * @return 选中的 id；没有任何后置摄像头时返回 null（调用方提示「无后置摄像头」）
     */
    @JvmStatic
    fun select(candidates: List<Candidate>, mode: String?): String? {
        val backs = candidates.filter { it.back }
        if (backs.isEmpty()) return null

        // 主摄：第一颗后置。焦距读不到时两种模式都用它，保证再差的 ROM 也能出画面。
        val main = backs[0]
        if (normalize(mode) == MODE_MAIN) return main.id

        val mainFocal = main.minFocalMm
        if (mainFocal == null || mainFocal <= 0f) return main.id

        // 超广角：焦距比主摄短到阈值以下的后置里，挑最短那颗。
        val ultrawide = backs
            .mapNotNull { c -> c.minFocalMm?.takeIf { it > 0f }?.let { c to it } }
            .filter { (_, f) -> f <= mainFocal * ULTRAWIDE_FOCAL_RATIO }
            .minByOrNull { (_, f) -> f }
        return ultrawide?.first?.id ?: main.id
    }
}
