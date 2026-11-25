package io.github.wumisc.safewalk

/**
 * 「这次启动要不要清空重建 diag.log」的纯决策（无 IO、无 Android 依赖，便于单测）。
 *
 * 为什么不能只看 versionCode：本项目常态是「版本号不动、只改代码再打一包」，
 * 覆盖安装同版本号时 versionCode 一模一样，旧实现直接早退，于是新日志文件里
 * 还混着上一次安装的内容——每次抓日志都得先人肉找分界线。
 *
 * 三条证据，任意一条成立就判定为「新安装事件」，清空重建：
 *  · versionCode 变化 —— 跨版本升级；
 *  · APK 字节数变化 —— 换包必变，比时间戳更硬（同版本重打包也命中）；
 *  · 安装指纹变化（lastUpdateTime 与 APK 落盘时间取大者）—— 补「只换了壳、
 *    字节数碰巧相同」的极端情况。
 *
 * 取不到的一侧（值为 0）一律不参与比较，避免「没有记录」被误判成「换过包」，
 * 否则每次启动都会把刚写的日志删掉。
 */
object LogResetPolicy {

    /**
     * 决策结果。
     *
     * @param reset  是否清空重建
     * @param reason 写进日志首行的原因文案（reset=false 时无意义，给空串）
     */
    data class Decision(val reset: Boolean, val reason: String)

    /** 值与值之间「确实变了」：两侧都有效且不等 */
    private fun differs(current: Long, recorded: Long): Boolean =
        current > 0L && recorded > 0L && current != recorded

    /**
     * @param oldVer   上次记录的 versionCode；-1 表示从没记录过（首次安装或旧版未启用该功能）
     * @param curVer   当前 versionCode
     * @param oldStamp 上次记录的安装指纹；0 表示没记录过
     * @param curStamp 当前安装指纹
     * @param oldSize  上次记录的 APK 字节数；0 表示没记录过
     * @param curSize  当前 APK 字节数
     * @param versionName 当前版本名，仅用于文案（可空）
     */
    @JvmStatic
    @JvmOverloads
    fun decide(
        oldVer: Int,
        curVer: Int,
        oldStamp: Long,
        curStamp: Long,
        oldSize: Long,
        curSize: Long,
        versionName: String = ""
    ): Decision {
        val ver = if (versionName.isNotEmpty()) versionName else curVer.toString()
        if (oldVer < 0) return Decision(true, "首次记录版本")
        if (oldVer != curVer) return Decision(true, "版本升级 $oldVer→$curVer")
        if (differs(curSize, oldSize)) return Decision(true, "覆盖安装（同版本 $ver，APK 已替换）")
        if (differs(curStamp, oldStamp)) return Decision(true, "覆盖安装（同版本 $ver，安装指纹变化）")
        return Decision(false, "")
    }
}
