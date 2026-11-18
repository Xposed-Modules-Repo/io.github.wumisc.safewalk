package io.github.wumisc.safewalk

/**
 * 医疗急救卡专用脱敏日志出口（v1.1）。
 *
 * 隐私两分铁律（v1.1 §9）：
 * - 永不写值：logcat、DiagLog 文件、崩溃堆栈、跨进程 Intent extra、config.json；
 * - 允许显示值：仅 EmergencyOverlay 与 EmergencyAlertActivity 两个屏幕显示面。
 *
 * 本类只产出 `***(len=N)` 形式的长度信息，绝不拼进任何真实字段值。
 * 事件名一律带前缀，方便从 diag.log 里 grep 出完整证据链。
 */
object EmergencyDiag {
    // ===== 事件前缀（沿用 v1.0 体系，[display] 为 v1.1 新增）=====
    const val P_CRASH = "crash"
    const val P_SOS = "sos"
    const val P_OVERLAY = "overlay"
    const val P_DISPLAY = "display"
    const val P_VAULT = "vault"
    const val P_EMERGENCY = "emergency"
    const val P_AED = "aed"
    const val P_SEC_REJECT = "SEC:REJECT"
    const val P_HOOK = "hook"
    const val P_KEEP = "keep"

    /** App 进程启动时调用一次：把脱敏钩子装进 DiagLog（幂等） */
    @JvmStatic
    fun install() {
        DiagLog.redactor = { key, value -> redact(key, value) }
    }

    /**
     * 字段值脱敏：只保留字段名与字符数。
     *
     * 空值返回 `key=***(len=0)`，非空的姓名"张三"也只返回 `name=***(len=2)`。
     * 注意长度本身是低敏感信息（可用于排查"字段没写进去"），允许记录。
     */
    @JvmStatic
    fun redact(key: String?, value: String?): String =
        "${key ?: "?"}=***(len=${value?.length ?: 0})"

    /** 带前缀的事件日志（正文必须已经脱敏） */
    @JvmStatic
    fun event(prefix: String, msg: String) {
        DiagLog.log("[$prefix] $msg")
    }

    /**
     * 记录一次急救卡操作：只输出字段名与长度，不输出任何值。
     *
     * @param action 事件动作名，如 saved / read / display_fields
     */
    @JvmStatic
    fun logCard(action: String, card: EmergencyCard) {
        val sb = StringBuilder("[$P_VAULT] $action ")
        sb.append(redact("name", card.name)).append(' ')
        sb.append(redact("bloodType", card.bloodType.label)).append(' ')
        sb.append(redact("rh", card.rh.label)).append(' ')
        sb.append(redact("allergy", card.allergy)).append(' ')
        sb.append(redact("conditions", card.conditions)).append(' ')
        sb.append("contacts=").append(card.contacts.size)
        DiagLog.log(sb.toString())
    }

    /**
     * 一次性 token 的日志表示：只记哈希前 4 位，绝不记完整 token。
     *
     * Binder 取件失败/成功都要能对着日志排查，但又不能把可重放的 token 写进文件。
     * 这里用稳定的 31 进位哈希取 4 个十六进制字符，足以区分不同事件。
     */
    @JvmStatic
    fun tokenHash4(token: String?): String {
        if (token.isNullOrEmpty()) return "----"
        var h = 0
        for (c in token) h = 31 * h + c.code
        return java.lang.Integer.toHexString(h and 0xFFFF)
    }
}
