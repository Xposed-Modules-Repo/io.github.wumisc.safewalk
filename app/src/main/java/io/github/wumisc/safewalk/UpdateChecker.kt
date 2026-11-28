package io.github.wumisc.safewalk

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 【5.7.18 更新提示】查最新版，给两条下载路：123 云盘 / GitHub。
 *
 * 为什么查 GitHub 接口而不是自建接口：正式版只发在
 * Xposed-Modules-Repo/io.github.wumisc.safewalk 的 Release 上，那里就是唯一权威版本号；
 * 具体下载走两条通道 —— 国内网络对 123 云盘更友好，GitHub 那条是原产地（含更新说明）。
 *
 * 三条硬约束：
 *  · 只读公开接口，不带任何本机信息（UA 只有版本号），失败一律静默，绝不弹错误；
 *  · 自动检查 24 小时最多一次，手动检查不受此限；
 *  · 只提示，不自动下载、不自动安装 —— 装不装用户决定。
 */
object UpdateChecker {

    /** GitHub 最新 Release（公开、无需令牌）。60 次/小时/IP 的限额对 24 小时一次绰绰有余 */
    const val LATEST_API =
        "https://api.github.com/repos/Xposed-Modules-Repo/io.github.wumisc.safewalk/releases/latest"

    /**
     * GitHub Releases Atom 源：**不限流**。
     *
     * api.github.com 按 IP 限 60 次/小时，国内运营商 CGNAT 下几千人共用一个出口，
     * 常常一查就 403（实测本机命中过）。Atom 是静态 feed，没这个限额，
     * 条目里的 link 直接带 `releases/tag/<版本>`，拿它当第二道正好。
     */
    const val ATOM_FEED =
        "https://github.com/Xposed-Modules-Repo/io.github.wumisc.safewalk/releases.atom"

    /** GitHub 下载页：Release 列表页，用户能看到更新说明与全部历史版本 */
    const val GITHUB_PAGE =
        "https://github.com/Xposed-Modules-Repo/io.github.wumisc.safewalk/releases/latest"

    /** 123 云盘分享（作者账号固定目录，含全部历史包）。提取码在链接里，点开就能下 */
    const val PAN123_PAGE = "https://1838528700.share.123pan.cn/123pan/UafYTd-BBXQv?pwd=9266#"

    /** 自动检查冷却：一天一次。装完新版本后当天不再弹 */
    const val CHECK_INTERVAL_MS = 24L * 3600_000L

    private const val SP = "safewalk_update"
    private const val KEY_LAST_CHECK = "lastCheckTs"
    private const val KEY_LATEST = "latestVersionCode"
    private const val KEY_LATEST_NAME = "latestVersionName"

    /** 远端最新版：版本号与展示名 */
    data class Release(val versionCode: Int, val versionName: String)

    /**
     * 纯解析（可单测）：从 Release 的 tag 里取版本号。
     *
     * 本项目 tag 形如 `140-5.7.18-lx102`（`<versionCode>-<versionName>`）。
     * 老版本 tag 只有 `5.6.15`、`v5.5.0` 这类写法时返回 null —— 拿不到可靠版本号
     * 就当作「没查到」，宁可不提示，也不提示一个错的版本。
     */
    @JvmStatic
    fun parseRelease(tag: String): Release? {
        val t = tag.trim()
        val head = t.substringBefore('-')
        val code = head.toIntOrNull() ?: return null
        if (code <= 0) return null
        val name = t.substringAfter('-', "").trim()
        return Release(code, if (name.isEmpty()) "版本 $code" else name)
    }

    /** 纯解析（可单测）：GitHub Release JSON → 远端版本。字段不全返回 null。 */
    @JvmStatic
    fun parseResponse(json: String): Release? = try {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        parseRelease(tag)
    } catch (_: Throwable) {
        null
    }

    /** 是否有新版：远端版本号严格大于本机。相等或更低都不提示（回退安装不打扰） */
    @JvmStatic
    fun isNewer(localCode: Int, remoteCode: Int): Boolean = remoteCode > localCode

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 本机版本号；拿不到返回 0 */
    @JvmStatic
    fun localVersionCode(ctx: Context): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
    } catch (_: Throwable) {
        0
    }

    /** 上次查到的远端版本（没有则 null）：进桌面先把它显示出来，用户一眼知道查过什么 */
    @JvmStatic
    fun lastKnown(ctx: Context): Release? {
        val sp = prefs(ctx.applicationContext)
        val code = sp.getInt(KEY_LATEST, 0)
        if (code <= 0) return null
        return Release(code, sp.getString(KEY_LATEST_NAME, "") ?: "")
    }

    /** 查一次最新版。回调在主线程；失败给 null（调用方静默）。
     *
     * @param force 手动检查：不受 24 小时冷却限制
     */
    @JvmStatic
    @JvmOverloads
    fun check(ctx: Context, force: Boolean = false, cb: (Release?) -> Unit = {}) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val now = System.currentTimeMillis()
        val sp = prefs(app)
        if (!force && now - sp.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) {
            // 冷却期内不再请求：直接回上次结果，避免一进 App 就联网
            val code = sp.getInt(KEY_LATEST, 0)
            val name = sp.getString(KEY_LATEST_NAME, "") ?: ""
            main.post { cb(if (code > 0) Release(code, name) else null) }
            return
        }
        Thread {
            val rel = fetch()
            try {
                sp.edit().putLong(KEY_LAST_CHECK, now).apply()
                if (rel != null) {
                    sp.edit().putInt(KEY_LATEST, rel.versionCode)
                        .putString(KEY_LATEST_NAME, rel.versionName)
                        .apply()
                }
            } catch (_: Throwable) {
            }
            main.post { cb(rel) }
        }.start()
    }

    /**
     * 版本检查链：GitHub API → GitHub Atom（不限流）→ 123 云盘分享页。
     *
     * GitHub 是原产地（版本号最准）；api.github.com 在共享 IP 下会 403，
     * 于是换同一个仓库的 Atom feed 再试一次；两者都打不开时退到 123 云盘扫包名
     * —— 那里存的就是全部正式包，`SafeWalk_5.7.18-lx102_140.apk` 的尾巴就是 versionCode。
     * 三条都不行就返回 null：宁可不提示，也不猜。
     */
    private fun fetch(): Release? {
        fetchGithub()?.let { return it }
        DiagLog.log("[update] api.github.com 不可用，改走 Atom")
        fetchAtom()?.let { return it }
        DiagLog.log("[update] GitHub 两路都不通，改走 123 云盘分享页")
        return fetchPan123()
    }

    /** 从 Atom feed 里取最新 tag（纯函数，可单测）：`releases/tag/140-5.7.18-lx102` */
    @JvmStatic
    fun parseAtom(xml: String): Release? {
        val re = Regex("""releases/tag/([0-9]{2,4}-[0-9A-Za-z.\-]+)""")
        return re.findAll(xml)
            .mapNotNull { parseRelease(it.groupValues[1]) }
            .maxByOrNull { it.versionCode }
    }

    private fun fetchAtom(): Release? = try {
        val xml = httpText(ATOM_FEED) ?: return null
        parseAtom(xml)
    } catch (t: Throwable) {
        DiagLog.log("[update] Atom 检查异常: ${t.javaClass.simpleName}")
        null
    }

    /**
     * 从 123 分享页 HTML 里扫最新包名（纯函数，可单测）。
     *
     * 取所有 `SafeWalk_<versionName>_<code>.apk` 里 code 最大的那个；
     * 页面是前端渲染的，扫不到就返回 null（调用方静默）。
     */
    @JvmStatic
    fun parsePan123(html: String): Release? {
        val re = Regex("""SafeWalk_([0-9][0-9.]*-lx102)_(\d{2,4})\.apk""")
        return re.findAll(html)
            .mapNotNull { m ->
                val name = m.groupValues[1]
                val code = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                Release(code, name)
            }
            .maxByOrNull { it.versionCode }
    }

    private fun fetchPan123(): Release? = try {
        val html = httpText(PAN123_PAGE) ?: return null
        parsePan123(html)
    } catch (t: Throwable) {
        DiagLog.log("[update] 123 云盘检查异常: ${t.javaClass.simpleName}")
        null
    }

    private fun httpText(u: String): String? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SafeWalk-UpdateCheck")
        if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Throwable) {
        null
    }

    private fun fetchGithub(): Release? = try {
        val c = URL(LATEST_API).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "SafeWalk-UpdateCheck")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode == 200) {
            c.inputStream.bufferedReader().use { parseResponse(it.readText()) }
        } else {
            DiagLog.log("[update] 检查失败 HTTP ${c.responseCode}")
            null
        }
    } catch (t: Throwable) {
        // 没网、DNS 被拦、接口限流都走这里：静默，不打扰用户
        DiagLog.log("[update] 检查异常: ${t.javaClass.simpleName}")
        null
    }

    /** 打开 123 云盘分享页 */
    @JvmStatic
    fun openPan123(ctx: Context) = open(ctx, PAN123_PAGE)

    /** 打开 GitHub 下载页 */
    @JvmStatic
    fun openGithub(ctx: Context) = open(ctx, GITHUB_PAGE)

    private fun open(ctx: Context, url: String) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(if (ctx is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
            DiagLog.log("[update] 打开下载页失败: $url")
        }
    }

    /**
     * 发现新版时弹提示（两个下载按钮 + 取消）。
     *
     * 文案只说事实：哪些版本、去哪下。不做「立即更新否则…」之类的催促。
     */
    @JvmStatic
    fun showPrompt(act: Activity, rel: Release, localCode: Int) {
        if (act.isFinishing || act.isDestroyed) return
        try {
            AlertDialog.Builder(act)
                .setTitle("有新版本 ${rel.versionName}")
                .setMessage(
                    "当前 $localCode，最新 ${rel.versionCode}。\n\n"
                        + "两个下载渠道：\n"
                        + "· GitHub：原产地，附带完整更新说明\n"
                        + "· 123 云盘：GitHub 打不开时的备选，点开即可下载\n\n"
                        + "APK 同签名，覆盖安装即可，设置与数据都保留。"
                )
                .setPositiveButton("GitHub") { _, _ -> openGithub(act) }
                .setNeutralButton("123 云盘") { _, _ -> openPan123(act) }
                .setNegativeButton("以后再说", null)
                .show()
        } catch (_: Throwable) {
        }
    }
}
