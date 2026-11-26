package io.github.wumisc.safewalk

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 【诊断日志上报】关于页手动触发的诊断日志上行通道。
 *
 * ## 设计红线（合规）
 * 1. **默认关闭、显式同意**：未在弹窗里点过「同意并上传」（[SafeWalkPrefKey.logUploadConsented]
 *    = false）时，[upload] 直接不发任何请求。同意可在关于页撤回。
 * 2. **数据最小化**：只带机型 / 系统 / UI / 应用版本等排障必需的环境字段，外加 diag 日志尾部；
 *    **不带**精确位置、联系人、手机号、急救卡内容（医疗字段落盘时已由 EmergencyDiag 脱敏）。
 * 3. **本地先脱敏**：上报前 [redact] 再跑一遍正则兜底，抹掉手机号 / MAC / 坐标 / IMEI 等残留。
 * 4. **失败静默降级**：网络不可用 / 服务报错只记一条本地诊断日志，不阻塞主功能、不弹错误打扰；
 *    用户手动点的入口才给一句结果 Toast。
 *
 * ## 网络栈
 * 沿用项目既有原生 [HttpURLConnection]，不引入 OkHttp 等新依赖（包体裁剪）。
 * 联网只发生在 App 进程；Hook（SystemUI）进程不参与上报。
 *
 * ## 端点与令牌
 * [ENDPOINT_URL] 指向自建 Cloudflare Pages 后端；令牌写在客户端只能挡「随手写个请求打接口」，
 * 挡不住反编译，与既有广播令牌同一口径。若令牌泄露，在后端轮换 `UPLOAD_TOKEN` Secret 后同步本处。
 */
object DiagUploader {

    /** Cloudflare Pages 上报接口地址。 */
    private const val ENDPOINT_URL = "https://safewalk-diag.pages.dev/api/diag/upload"
    /** 接口 Bearer 令牌（与后端 Pages Secret `UPLOAD_TOKEN` 一致）。 */
    private const val UPLOAD_TOKEN = "1c2bfe3bcf5a5b02d1a19b37c9a213327d0286b95d0d8fee"

    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 8_000
    private const val TAIL_LINES = 400
    private const val MAX_LOG_CHARS = 60_000

    private const val PREFS_NAME = "safewalk_xsp"
    /** 上次「成功」上报的时间戳键；失败不写，避免网络抖动后罚站十分钟。 */
    private const val KEY_LAST_OK_TS = "diag_upload_last_ok_ts"

    /** 同一进程内的并发闸：连点两次不会真的发两个请求。 */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 上报结果回调，回调发生在主线程。 */
    interface Callback {
        fun onResult(ok: Boolean, message: String)
    }

    /** 端点是否已部署配置好；未配置时入口不应发起请求。 */
    @JvmStatic
    fun isConfigured(): Boolean =
        !ENDPOINT_URL.contains("CHANGE_ME") && !UPLOAD_TOKEN.contains("CHANGE_ME")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun hasConsented(ctx: Context): Boolean = SafePrefs.getBool(
        prefs(ctx), SafeWalkPrefKey.logUploadConsented, false
    )

    @JvmStatic
    fun setConsented(ctx: Context, consented: Boolean) {
        prefs(ctx).edit().putBoolean(SafeWalkPrefKey.logUploadConsented, consented).apply()
    }

    /**
     * 触发一次手动上报。在后台线程发请求，结果经主线程 [Callback] 回传。
     * 未同意 / 未配置 / 冷却中 / 异常都不会崩，失败信息落到本地诊断日志。
     *
     * 三道前置闸（缺一道都能被连点打穿，见 [UploadCooldown] 的注释）：
     * 1. 未同意 → 不发请求（同意只在弹窗里写，撤回即生效）；
     * 2. 同进程已有请求在飞 → 直接拒绝，不排队；
     * 3. 同机 10 分钟内已成功上报过 → 拒绝，并告诉用户还要等几分钟。
     */
    @JvmStatic
    fun upload(ctx: Context, note: String?, cb: Callback?) {
        val app = ctx.applicationContext
        if (!hasConsented(app)) {
            cb?.onResult(false, "尚未同意上传诊断日志")
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            cb?.onResult(false, "正在上传中，请稍候")
            return
        }
        val now = System.currentTimeMillis()
        val lastOk = prefs(app).getLong(KEY_LAST_OK_TS, 0L)
        if (!UploadCooldown.shouldAllow(lastOk, now)) {
            inFlight.set(false)
            val mins = UploadCooldown.remainingMinutes(lastOk, now)
            cb?.onResult(false, "刚上传过，请 ${mins} 分钟后再试")
            return
        }
        Thread {
            val result = try {
                uploadBlocking(app, note)
            } catch (t: Throwable) {
                DiagLog.log("[upload] 上报异常: ${t.javaClass.simpleName} ${t.message}")
                false to "网络不可用，日志仍保存在本地"
            }
            if (result.first) {
                prefs(app).edit().putLong(KEY_LAST_OK_TS, System.currentTimeMillis()).apply()
            }
            inFlight.set(false)
            Handler(Looper.getMainLooper()).post { cb?.onResult(result.first, result.second) }
        }.start()
    }

    private fun uploadBlocking(ctx: Context, note: String?): Pair<Boolean, String> {
        if (!isConfigured()) return false to "上报服务暂未配置"

        val env = buildEnv()
        val raw = DiagLog.tail(TAIL_LINES)
        val logText = redact(raw)
        val payload = JSONObject().apply {
            put("device", JSONObject(env))
            put("app", JSONObject().apply {
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("package", ctx.packageName)
            })
            put("note", note?.trim().orEmpty())
            put("ts", System.currentTimeMillis())
            put("log", if (logText.length > MAX_LOG_CHARS) {
                logText.substring(logText.length - MAX_LOG_CHARS)
            } else {
                logText
            })
        }
        val body = payload.toString().toByteArray(Charsets.UTF_8)

        val c = (URL(ENDPOINT_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $UPLOAD_TOKEN")
            setRequestProperty("User-Agent", "SafeWalk/${BuildConfig.VERSION_NAME}")
            setFixedLengthStreamingMode(body.size)
        }
        return try {
            c.outputStream.use { it.write(body) }
            when (val code = c.responseCode) {
                in 200..299 -> {
                    DiagLog.log("[upload] 诊断日志已上报（env=${env["model"]}）")
                    true to "诊断日志已上传，感谢反馈"
                }
                else -> {
                    DiagLog.log("[upload] 上报 HTTP $code")
                    false to "上传失败（HTTP $code），日志仍保存在本地"
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun buildEnv(): Map<String, String> {
        val rom = try {
            RomDetector.detectFromBuild().displayName
        } catch (_: Throwable) {
            "未知"
        }
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "release" to Build.VERSION.RELEASE.orEmpty(),
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "ui" to rom
        )
    }

    /**
     * 上报前的本地兜底脱敏。纯字符串变换、不碰系统 API，便于单测钉死。
     *
     * 注意：医疗急救字段在落盘时已经过 EmergencyDiag 钩子脱敏成 `***(len=N)`，
     * 这里只正则兜底手机号 / MAC(BSSID) / 经纬度 / IMEI 等可能残留在诊断行里的标识。
     */
    @JvmStatic
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        // 手机号：1 开头 11 位；前后不能再接数字，避免在毫秒时间戳 / IMEI 等长数字串中段误命中
        out = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)").replace(out, "***(手机号)")
        // MAC / BSSID：冒号或连分分隔的 6 段十六进制
        out = Regex("(?i)(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}").replace(out, "***(MAC)")
        // IMEI：15 位纯数字
        out = Regex("\\b\\d{15}\\b").replace(out, "***(IMEI)")
        // 经纬度：关键词后跟一个小数坐标
        out = Regex(
            "(?i)(lat|lng|lon|latitude|longitude|纬度|经度|坐标|location)\\s*[=:：]?\\s*-?\\d{1,3}\\.\\d+"
        ).replace(out, "$1=***(坐标)")
        return out
    }
}
