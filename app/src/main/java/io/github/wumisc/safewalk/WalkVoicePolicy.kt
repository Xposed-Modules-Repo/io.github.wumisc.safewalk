package io.github.wumisc.safewalk

/**
 * 行走语音播报的判定逻辑（纯函数，零 `android.*` 依赖）。
 *
 * ## 为谁做
 *
 * 视力障碍用户。他们走路时最怕两件事：手机在口袋里震了一下，却不知道是骚扰短信
 * 还是家人来电；以及为了确认消息而停下脚步、把手机举到眼前。语音播报解决这两个问题。
 *
 * ## 为什么限制这么多
 *
 * 语音是**打断式**通道，比通知更粗暴：说话时用户听不清环境声（对盲人尤其危险，
 * 车流、喇叭、台阶提示音都会被盖住）。所以每一条规则都是「宁可少说，也不要乱说」：
 *
 * 1. **只在行走中**播——站着或坐着时用户能自己看手机，不需要念；
 * 2. **只在亮屏时**播——熄屏说明手机在口袋里，这时候念出来既吓人又尴尬；
 * 3. **尊重勿扰**——用户明确说了「别吵我」，一秒钟的「重要消息」也不行；
 * 4. **全局 15 秒冷却**——群里刷屏时不能变成复读机；
 * 5. **同来源 60 秒冷却**——同一个人连发五条，只念第一条，后面用「还有新消息」概括
 *    （后续版本），避免把一条完整对话逐句念完。
 *
 * ## 只识别两类
 *
 * 来电与即时消息。系统通知、推送广告、更新提醒一律不念——那些不是「走路时需要
 * 立刻知道」的事，念了只会增加噪声。
 */
object WalkVoicePolicy {

    /** 播报类别。 */
    enum class Kind {
        /** 来电 / 通话界面。 */
        CALL,

        /** 即时消息类通知。 */
        MESSAGE,

        /** 不播。 */
        IGNORE
    }

    /** 全局最小间隔：上一句念完后至少隔这么久才念下一句。 */
    const val GLOBAL_COOLDOWN_MS = 15_000L

    /** 同一 App 的最小间隔。 */
    const val SAME_SOURCE_COOLDOWN_MS = 60_000L

    /** 消息正文最多念多少字。再长会把用户从「听一句」拖成「听一段」。 */
    const val MAX_SPEAK_CHARS = 40

    // 镜像 AccessibilityEvent 的类型常量（理由见 A11yEventFormatter）
    private const val T_WINDOW_STATE_CHANGED = 0x00000020
    private const val T_NOTIFICATION_STATE_CHANGED = 0x00000040

    /** 来电相关包名。国产 ROM 常把拨号与通话界面拆成两个包。 */
    private val CALL_PKGS = setOf(
        "com.android.incallui",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.phone",
        "com.samsung.android.incallui",
        "com.android.mms.service"
    )

    /** 即时消息类包名 → 播报时用的中文名。 */
    private val MSG_PKGS: Map<String, String> = mapOf(
        "com.tencent.mm" to "微信",
        "com.tencent.mobileqq" to "QQ",
        "com.android.mms" to "短信",
        "com.google.android.apps.messaging" to "信息",
        "com.alibaba.android.rimet" to "钉钉",
        "com.ss.android.lark" to "飞书",
        "org.telegram.messenger" to "Telegram",
        "com.whatsapp" to "WhatsApp",
        "com.xiaomi.smspush" to "短信",
        "com.coloros.sms" to "短信",
        "com.vivo.message" to "短信",
        "com.hihonor.mms" to "短信"
    )

    /**
     * 把一条无障碍事件归类。
     *
     * 来电靠「包名 + 类名里有 InCall」双重判断：部分 ROM 的来电界面由拨号盘包名承载，
     * 类名却是 `InCallActivity`；只认包名会漏掉这些机型。
     */
    @JvmStatic
    fun classify(pkg: String, eventType: Int, className: String): Kind {
        if (pkg.isEmpty()) return Kind.IGNORE
        if (pkg in CALL_PKGS) return Kind.CALL
        if (className.contains("InCall", ignoreCase = true)) return Kind.CALL
        if (eventType == T_NOTIFICATION_STATE_CHANGED && MSG_PKGS.containsKey(pkg)) {
            return Kind.MESSAGE
        }
        // 窗口切换也可能是消息 App 打开，那不是「新消息」，不念
        if (eventType == T_WINDOW_STATE_CHANGED) return Kind.IGNORE
        return Kind.IGNORE
    }

    /** 包的展示名；不认识的包直接返回包名末段，至少让用户知道是哪个 App。 */
    @JvmStatic
    fun sourceLabel(pkg: String): String =
        MSG_PKGS[pkg] ?: pkg.substringAfterLast('.').ifEmpty { pkg }

    /**
     * 该不该念。
     *
     * @param walking        行走模式是否生效（总开关 + 后摄预览都开着）
     * @param screenOn       屏幕是否亮着
     * @param dnd            是否处于勿扰 / 静音模式
     * @param kind           事件类别
     * @param now            当前时间戳
     * @param lastSpeakTs    上次播报时间（0 表示从未）
     * @param lastSamePkgTs  上次播报同一来源的时间（0 表示从未）
     */
    @JvmStatic
    @JvmOverloads
    fun shouldSpeak(
        walking: Boolean,
        screenOn: Boolean,
        dnd: Boolean,
        kind: Kind,
        now: Long,
        lastSpeakTs: Long,
        lastSamePkgTs: Long,
        globalCooldownMs: Long = GLOBAL_COOLDOWN_MS,
        sameSourceCooldownMs: Long = SAME_SOURCE_COOLDOWN_MS
    ): Boolean {
        if (!walking) return false
        if (!screenOn) return false
        if (dnd) return false
        if (kind == Kind.IGNORE) return false
        // 时钟回拨：宁可放过一条，也不要因为负数间隔而连续播报
        if (now < lastSpeakTs) return true
        if (lastSpeakTs != 0L && now - lastSpeakTs < globalCooldownMs) return false
        if (lastSamePkgTs != 0L && now - lastSamePkgTs < sameSourceCooldownMs) return false
        return true
    }

    /** 组装播报文本。 */
    @JvmStatic
    fun compose(kind: Kind, label: String, body: String): String = when (kind) {
        Kind.CALL -> "来电，来自$label"
        Kind.MESSAGE -> {
            val b = clip(body)
            if (b.isEmpty()) "来自$label 的新消息" else "来自$label：$b"
        }
        Kind.IGNORE -> ""
    }

    /** 正文截断：超过 [MAX_SPEAK_CHARS] 就截断并停住，不要念半句话的逗号。 */
    @JvmStatic
    fun clip(raw: String): String {
        val flat = raw.split(' ', '\n', '\r', '\t')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (flat.isEmpty()) return ""
        if (flat.length <= MAX_SPEAK_CHARS) return flat
        return flat.substring(0, MAX_SPEAK_CHARS) + "，"
    }
}
