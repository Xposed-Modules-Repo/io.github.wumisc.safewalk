package io.github.wumisc.safewalk

/**
 * 路况预览「镜头选择」策略（5.4.17）。
 *
 * ## 背景
 *
 * 行走模式用后置摄像头把身后路况叠在屏上。旧版固定打开 cameraIdList 里第一颗
 * 后置（主摄），视野窄，近距离的台阶/路沿容易出画。超广角视野大，更适合看路。
 *
 * ## 策略（与具体机型解耦，纯函数，便于 JVM 单测）
 *
 * 不依赖 android.hardware.camera2，调用方把每颗相机的三要素抽象成 [Candidate] 传进来：
 *  · [Candidate.facing]  与 CameraCharacteristics.LENS_FACING_* 对齐：0=前摄 1=后摄
 *  · [Candidate.focalMm] 该相机的等效焦距（LENS_FOCAL_LENGTH），<=0 表示机型没上报
 *  · [Candidate.id]      相机 id
 *
 * 选定规则：
 *  · 主摄 = 后置列表里的第一颗（与旧版 backCameraId() 行为一致，保证「主摄」档位可控）；
 *  · 超广角 = 后置里焦距**明显更小**的一颗（< 主摄焦距 × [ULTRAWIDE_RATIO]），取其中最小的；
 *  · [LENS_ULTRAWIDE]（默认）= 有超广角就用，没有就回退主摄 —— 即「无超广角可用主摄代替」；
 *  · [LENS_MAIN] = 强制主摄。
 *
 * 故意不把「只要有两颗后置就认为第二颗是超广角」写死：很多机型第二颗是长焦或景深，
 * 焦距比主摄更大。只认「焦距显著更小」这一条，判错也不会把画面切到长焦上。
 */
object CameraLensPolicy {

    /** 超广角优先（默认）：有超广角用超广角，没有用主摄。 */
    const val LENS_ULTRAWIDE = "ultrawide"
    /** 强制主摄。 */
    const val LENS_MAIN = "main"

    /** facing 取值，与 CameraCharacteristics.LENS_FACING_* 一致。 */
    const val FACING_FRONT = 0
    const val FACING_BACK = 1

    /**
     * 判定「超广角」的焦距比：候选焦距 < 主摄焦距 × 该值才算超广角。
     * 0.85 意味着至少比主摄广 15%，足以滤掉「两颗焦距几乎一样」的伪多摄。
     */
    const val ULTRAWIDE_RATIO = 0.85f

    /** 一颗相机的可判定信息；由渲染层从 CameraCharacteristics 读出后填进来。 */
    data class Candidate(
        val id: String,
        val facing: Int,
        val focalMm: Float
    )

    /** 归一：拼错 / 旧配置 / 未知档位一律按「超广角优先」，即默认行为。 */
    @JvmStatic
    fun normalize(mode: String?): String = when (mode) {
        LENS_MAIN -> LENS_MAIN
        else -> LENS_ULTRAWIDE
    }

    /**
     * 从候选里挑出要打开的相机 id。
     *
     * @return 选中的相机 id；没有任何后置相机时返回 null（调用方按「无后置」处理）。
     */
    @JvmStatic
    fun chooseCameraId(candidates: List<Candidate>, mode: String?): String? {
        val backs = candidates.filter { it.facing == FACING_BACK }
        if (backs.isEmpty()) return null
        // 主摄 = 第一颗后置，与旧版行为一致；超广角只在「另有焦距更小者」时存在。
        val main = backs.first()
        if (normalize(mode) == LENS_MAIN) return main.id

        val mainFocal = main.focalMm
        val ultraWide: Candidate? = if (mainFocal > 0f) {
            backs.drop(1)
                .filter { it.focalMm > 0f && it.focalMm < mainFocal * ULTRAWIDE_RATIO }
                .minByOrNull { it.focalMm }
        } else {
            // 主摄没上报焦距，就不臆测谁更广，老实用主摄。
            null
        }
        return (ultraWide ?: main).id
    }

    /** 是否识别到了超广角；供日志与「本机镜头」提示用。 */
    @JvmStatic
    fun hasUltraWide(candidates: List<Candidate>): Boolean {
        val backs = candidates.filter { it.facing == FACING_BACK }
        if (backs.size < 2) return false
        val mainFocal = backs.first().focalMm
        if (mainFocal <= 0f) return false
        return backs.drop(1).any { it.focalMm > 0f && it.focalMm < mainFocal * ULTRAWIDE_RATIO }
    }

    /** 一行式日志描述，避免调用方各自拼字符串。 */
    @JvmStatic
    fun describe(candidates: List<Candidate>, mode: String?, chosen: String?): String {
        val backs = candidates.filter { it.facing == FACING_BACK }
        val list = backs.joinToString(",") { "${it.id}:${it.focalMm}mm" }
        return "镜头策略 | 档位=${normalize(mode)} 后置候选=$list 超广角=${hasUltraWide(candidates)} 选中=$chosen"
    }
}
