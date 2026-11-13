package io.github.wumisc.safewalk

import android.content.Context
import android.media.AudioAttributes
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 行走语音播报的执行层：内存状态 + 系统 TTS。
 *
 * ## 与 [WalkVoicePolicy] 的分工
 *
 * 策略类只回答「该不该念」，纯函数、可单测；本类负责「读现场状态（行走模式、亮屏、
 * 勿扰）+ 调 TTS + 记冷却时间戳」。分开的原因是：TTS 和 PowerManager 都是 Android
 * 类型，塞进策略类会让那 20 多条单测全部跑不起来。
 *
 * ## 为什么冷却时间戳放这里而不是 SP
 *
 * 播报是短时行为，进程被回收后重新计时反而是好事——用户刚重启完手机，
 * 第一条重要消息应该马上能听到，而不是被十分钟前的冷却挡住。
 *
 * ## 轻柔是怎么做到的
 *
 * 三个参数一起调：`USAGE_ASSISTANCE_ACCESSIBILITY` 让系统按「辅助提示」类音量处理
 * （不会被当成媒体在放歌，也不会在通话时用最大音量）、语速 0.92、音量 0.75。
 * 目标是「听得清，但不吓人」——盲人用户对突然的大声提示比普通人更敏感。
 */
object WalkVoiceAnnouncer {

    /** 与 RomSettings 同一个配置文件；改名前先搜这三个字面量。 */
    private const val PREFS = "safewalk_xsp"

    private var tts: TextToSpeech? = null
    private var ready = false

    /** TTS 初始化是异步的；初始化完成前到达的句子先存这里，只留最后一条。 */
    private var pending: String? = null

    private var lastSpeakTs = 0L
    private val lastByPkg = HashMap<String, Long>()

    /**
     * 收到一条候选事件时调用。内部完成全部判定，调用方不需要先过滤。
     */
    @JvmStatic
    fun trySpeak(ctx: Context, pkg: String, kind: WalkVoicePolicy.Kind, body: String) {
        if (kind == WalkVoicePolicy.Kind.IGNORE) return

        if (!isWalkModeOn(ctx)) return

        val screenOn = try {
            ctx.getSystemService(PowerManager::class.java)?.isInteractive == true
        } catch (_: Throwable) {
            false
        }
        val dnd = isDnd(ctx)
        val now = System.currentTimeMillis()

        val ok = WalkVoicePolicy.shouldSpeak(
            walking = true,
            screenOn = screenOn,
            dnd = dnd,
            kind = kind,
            now = now,
            lastSpeakTs = lastSpeakTs,
            lastSamePkgTs = lastByPkg[pkg] ?: 0L
        )
        if (!ok) return

        lastSpeakTs = now
        lastByPkg[pkg] = now

        val text = WalkVoicePolicy.compose(kind, WalkVoicePolicy.sourceLabel(pkg), body)
        if (text.isEmpty()) return
        speak(ctx, text)
        DiagLog.log("[voice] 播报：$text")
    }

    /** 行走模式是否生效：总开关 + 后摄预览（与 KeepAlivePolicy.desired 同义）。 */
    @JvmStatic
    fun isWalkModeOn(ctx: Context): Boolean = try {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        SafePrefs.getBool(sp, SafeWalkPrefKey.masterEnable, true) &&
                SafePrefs.getBool(sp, SafeWalkPrefKey.rearCamAuto, false)
    } catch (_: Throwable) {
        false
    }

    /** 勿扰 / 静音 / 仅闹钟 都算「用户不想被打扰」。 */
    @JvmStatic
    fun isDnd(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val f = nm?.currentInterruptionFilter ?: android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        f != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
    } catch (_: Throwable) {
        false
    }

    private fun speak(ctx: Context, text: String) {
        ensure(ctx)
        val t = tts
        if (!ready || t == null) {
            // 引擎还在初始化（或系统没装 TTS 引擎）：留最后一句，初始化完再念
            pending = text
            return
        }
        try {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "safewalk-voice")
        } catch (e: Throwable) {
            DiagLog.log("[voice] TTS 调用失败：${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun ensure(ctx: Context) {
        if (tts != null) return
        val app = ctx.applicationContext
        tts = try {
            TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (!ready) {
                    DiagLog.log("[voice] TTS 初始化失败（status=$status），本机可能没有语音引擎")
                    return@TextToSpeech
                }
                val t = tts ?: return@TextToSpeech
                try {
                    t.language = Locale.CHINESE
                    t.setSpeechRate(0.92f)
                    // 「轻柔」靠 usage 与内容类型影响系统混音策略；音量本身由 TTS 默认值
                    // 决定，不去改系统音量（那会影响用户正在听的音乐或导航）
                    // setAudioAttributes 返回 SUCCESS/ERROR（Int），不是 Boolean
                    val ret = t.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    if (ret != TextToSpeech.SUCCESS) {
                        DiagLog.log("[voice] TTS 音频属性设置被拒（ret=$ret），将按默认通道播放")
                    }
                } catch (e: Throwable) {
                    DiagLog.log("[voice] TTS 参数设置失败：${e.javaClass.simpleName}")
                }
                pending?.let { p ->
                    pending = null
                    try {
                        t.speak(p, TextToSpeech.QUEUE_FLUSH, null, "safewalk-voice")
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (e: Throwable) {
            DiagLog.log("[voice] TTS 创建失败：${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /** 服务关闭时释放引擎（TTS 持有 AudioTrack，不释放会一直占着）。 */
    @JvmStatic
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ready = false
        pending = null
        lastSpeakTs = 0L
        lastByPkg.clear()
    }
}
