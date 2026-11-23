package io.github.wumisc.safewalk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 【5.6.13 公益】「寻找宝贝回家」数据源。
 *
 * 数据来自用户另一个开源项目 wumisc/antiburn-missing-kids（宝贝回家寻子网「家寻宝贝」
 * 规范化 JSON，隔天更新、最新优先）。本模块只读不写，不改动该项目。
 *
 * 通道：raw.githubusercontent.com 主 → cdn.jsdelivr.net 备 → 本地缓存 → 内置兜底快照。
 * 刷新间隔 [REFRESH_MS]（24h）：数据源隔天更新，每天检查一次即可及时跟上，又不会频繁请求。
 * 全部网络在后台线程，回调切回主线程；任何失败都降级，绝不崩、绝不阻塞首页。
 */
object MissingKidsRepository {

    /** 数据源隔天更新，24h 检查一次即可及时跟上，又不至于频繁联网。 */
    const val REFRESH_MS = 24L * 3600_000L

    /**
     * 【省流量 · 失败退避】一次没取到就别反复试。
     *
     * 真机日志里两个 JSON 源都被国内网络 reset，而旧实现失败后**不记失败时间**，
     * 于是每次进首页都会重新打两轮请求（6s 超时 ×2），既费流量又拖慢卡片出图。
     * 现在失败后 6 小时内不再尝试，直接用已有缓存；期间一旦成功就清零。
     */
    const val RETRY_BACKOFF_MS = 6L * 3600_000L

    private const val RAW = "https://raw.githubusercontent.com/wumisc/antiburn-missing-kids/main/kids.json"
    private const val CDN = "https://cdn.jsdelivr.net/gh/wumisc/antiburn-missing-kids@main/kids.json"
    private const val CACHE = "missing_kids.json"
    private const val SP = "safewalk"
    private const val KEY_TS = "missingKidsTs"
    private const val KEY_FAIL_TS = "missingKidsFailTs"

    /** 照片磁盘缓存目录（filesDir 下），避免每次进首页重复下载解码。 */
    private const val PHOTO_DIR = "kid_photos"

    /**
     * 【省流量 · 照片上限】最多留 8 张缩略图（每张约 15KB）。
     *
     * 数据源是 7 条隔天轮换，留 8 张刚好覆盖一整轮：同一个孩子再轮到时不重下，
     * 磁盘也封顶。超出才按最久未用淘汰（命中缓存时会刷新时间戳）。
     */
    private const val MAX_PHOTOS = 8

    /** 缩略图目标边长（px）：卡片照片放大后按 2x 采样到 192px 保持清晰。 */
    private const val PHOTO_TARGET_PX = 192

    /** 首页公益卡展示的孩子条数（一天两条）。 */
    const val SHOW_COUNT = 2

    /** 内置兜底快照（离线/首次无网时展示），与数据源 schema 一致。 */
    private const val FALLBACK = """{"items":[
        {"id":748652,"name":"牛乾坤","gender":"男","lost_time":"1995-08-16","lost_place":"河北省,邯郸市,丛台区,三陵乡陈窑村","feature":"额头圆形。下嘴唇有一点小芭、头和脖子有分离形态，耳轮廓大，大眼晴。","url":"https://baobeihuijia.com/bbhj/contents/3/282279.html","photo":"https://baobeihuijia.com/bbhj/special/2026/9/s_773b7bf3f4b893a7.png"},
        {"id":747933,"name":"李茜","gender":"女","lost_time":"2001-11-06","lost_place":"湖南省,长沙市,市辖区,长沙市第一社会福利院","feature":"走失时短发。","url":"https://baobeihuijia.com/bbhj/contents/3/281580.html","photo":"https://baobeihuijia.com/bbhj/special/2026/8/s_b2c58a5f2e7ea1e9.png"}
    ]}"""

    data class Kid(
        val name: String,
        val gender: String,
        val lostTime: String,
        val lostPlace: String,
        val feature: String,
        val url: String,
        val photo: String
    )

    /** 纯解析（可单测）：取前 [limit] 条。失败返回空列表。 */
    @JvmStatic
    @JvmOverloads
    fun parseList(json: String, limit: Int = SHOW_COUNT): List<Kid> = try {
        val a = JSONObject(json).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Kid>()
        for (i in 0 until minOf(a.length(), limit)) {
            val o = a.getJSONObject(i)
            out.add(
                Kid(
                    o.optString("name"), o.optString("gender"),
                    o.optString("lost_time"), o.optString("lost_place"),
                    o.optString("feature"), o.optString("url"), o.optString("photo")
                )
            )
        }
        out
    } catch (_: Throwable) {
        emptyList()
    }

    /** 纯解析（可单测）：取第一条。失败返回 null。 */
    @JvmStatic
    fun parseKid(json: String): Kid? = parseList(json).firstOrNull()

    /** 展示用副标题：性别 · 走失时间 · 地点（压缩逗号）。 */
    @JvmStatic
    fun subtitle(k: Kid): String {
        val place = k.lostPlace.replace(",", " ")
        return listOf(k.gender, k.lostTime, place).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** 异步加载前两条，回调在主线程。 */
    @JvmStatic
    fun load(ctx: Context, cb: (List<Kid>) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val kids = loadBlocking(app)
            main.post { cb(kids) }
        }.start()
    }

    private fun loadBlocking(ctx: Context): List<Kid> {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val cache = File(ctx.filesDir, CACHE)
        val ts = sp.getLong(KEY_TS, 0)
        val failTs = sp.getLong(KEY_FAIL_TS, 0)
        val now = System.currentTimeMillis()
        // 缓存新鲜 → 直接用，不请求网络
        if (cache.exists() && now - ts < REFRESH_MS) {
            parseList(cache.readText()).let { if (it.isNotEmpty()) return it }
        }
        // 【省流量 · 退避】上一轮失败不到 6 小时 → 这次不联网，直接用缓存/兜底。
        // 不能因为「昨天没取到」就每次进首页都重打两轮请求。
        if (now - failTs < RETRY_BACKOFF_MS) {
            val cached = cachedOrFallback(ctx)
            DiagLog.log("[kids] 失败退避中（${(RETRY_BACKOFF_MS - (now - failTs)) / 60_000} 分钟后重试），用缓存")
            return cached
        }
        for (u in listOf(RAW, CDN)) {
            val body = httpGet(u) ?: continue
            val list = parseList(body)
            if (list.isEmpty()) continue
            // 【省流量 · 先取到再替换】只有拿到有效新数据才覆盖旧缓存；
            // 覆盖的同一时刻清掉失败标记，并把不再需要的旧照片按上限淘汰。
            try {
                cache.writeText(body)
                sp.edit().putLong(KEY_TS, now).putLong(KEY_FAIL_TS, 0L).commit()
            } catch (_: Throwable) {
            }
            prunePhotos(ctx, list)
            return list
        }
        // 网络全失败：记失败时刻进入退避，再用旧缓存 → 兜底快照
        try {
            sp.edit().putLong(KEY_FAIL_TS, now).commit()
        } catch (_: Throwable) {
        }
        return cachedOrFallback(ctx)
    }

    /** 旧缓存优先、其次内置兜底快照；两者都不会触发任何网络请求。 */
    private fun cachedOrFallback(ctx: Context): List<Kid> {
        val cache = File(ctx.filesDir, CACHE)
        if (cache.exists()) {
            parseList(cache.readText()).let { if (it.isNotEmpty()) return it }
        }
        return parseList(FALLBACK)
    }

    private fun httpGet(u: String): String? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "SafeWalk/5.6")
        if (c.responseCode == 200) {
            c.inputStream.bufferedReader().use { it.readText() }
        } else {
            DiagLog.log("[kids] JSON HTTP ${c.responseCode}: $u")
            null
        }
    } catch (t: Throwable) {
        DiagLog.log("[kids] JSON 下载异常: ${t.javaClass.simpleName} ${t.message}")
        null
    }

    /** 异步加载照片：磁盘缓存命中直接解码，否则下载后采样压缩成缩略图落盘。失败回调 null。 */
    @JvmStatic
    fun loadPhoto(ctx: Context, url: String, cb: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val bmp = try {
                loadPhotoBlocking(app, url)
            } catch (_: Throwable) {
                null
            }
            main.post { cb(bmp) }
        }.start()
    }

    private fun loadPhotoBlocking(ctx: Context, url: String): Bitmap? {
        if (url.isEmpty()) {
            DiagLog.log("[kids] 照片地址为空，跳过")
            return null
        }
        val dir = File(ctx.filesDir, PHOTO_DIR)
        val cache = File(dir, photoFileName(url))
        // 缓存命中：直接解码（缓存已是压缩缩略图）。
        if (cache.exists()) {
            decodeScaled(cache)?.let {
                // LRU 时间戳：命中就刷一次，淘汰时优先丢掉最久没用的
                try { cache.setLastModified(System.currentTimeMillis()) } catch (_: Throwable) {}
                DiagLog.log("[kids] 照片命中缓存: ${cache.name}")
                return it
            }
            DiagLog.log("[kids] 缓存照片解码失败，重新下载: ${cache.name}")
        }

        val raw = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("User-Agent", "SafeWalk/5.6")
            if (c.responseCode != 200) {
                DiagLog.log("[kids] 照片下载 HTTP ${c.responseCode}: $url")
                return decodeScaled(cache)
            }
            c.inputStream.use { it.readBytes() }
        } catch (t: Throwable) {
            DiagLog.log("[kids] 照片下载异常: ${t.javaClass.simpleName} ${t.message}")
            return decodeScaled(cache)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val opt = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, PHOTO_TARGET_PX)
        }
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opt)
        if (bmp == null) {
            DiagLog.log("[kids] 照片解码失败（${raw.size}B，${bounds.outWidth}x${bounds.outHeight}）: $url")
            return null
        }
        try {
            dir.mkdirs()
            cache.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Throwable) {
            // 缓存写不进去不影响本次展示，下次再下即可。
        }
        DiagLog.log("[kids] 照片加载成功: ${bmp.width}x${bmp.height}（${raw.size}B）")
        return bmp
    }

    private fun decodeScaled(file: File): Bitmap? = try {
        BitmapFactory.decodeFile(file.absolutePath)
    } catch (_: Throwable) {
        null
    }

    /**
     * 【省流量 · 换新时清理】新批次写好后，把照片缓存压回上限 [MAX_PHOTOS]。
     *
     * 淘汰顺序是最久未用（命中会刷新时间戳），所以「这一轮还在用」的两张永远不会被删；
     * 只清掉早已轮换走、以后也不会再碰的旧文件，避免目录无限长、也避免重复下载。
     * 任何异常都吞掉：清理失败不影响卡片展示。
     */
    private fun prunePhotos(ctx: Context, keep: List<Kid>) {
        try {
            val dir = File(ctx.filesDir, PHOTO_DIR)
            if (!dir.isDirectory) return
            val keepNames = keep.map { photoFileName(it.photo) }.toSet()
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            val extra = files.size - MAX_PHOTOS
            if (extra <= 0) return
            var deleted = 0
            for (f in files) {
                if (deleted >= extra) break
                if (f.name in keepNames) continue
                if (f.delete()) deleted++
            }
            if (deleted > 0) DiagLog.log("[kids] 换新清理旧照片 $deleted 张（上限 $MAX_PHOTOS）")
        } catch (_: Throwable) {
        }
    }

    /** 按目标边长算 inSampleSize（2 的幂），避免大图直接解码撑爆内存。 */
    @JvmStatic
    fun sampleSize(w: Int, h: Int, target: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        var bw = w
        var bh = h
        while (bw / 2 >= target && bh / 2 >= target) {
            bw /= 2
            bh /= 2
            s *= 2
        }
        return s
    }

    /** 缓存文件名：URL 哈希 + 路径尾段，清洗非法字符。纯函数，可单测。 */
    @JvmStatic
    fun photoFileName(url: String): String {
        val hash = Integer.toHexString(url.hashCode())
        val tail = url.substringAfterLast('/').substringBefore('?').takeLast(24)
            .replace(Regex("[^A-Za-z0-9._-]"), "")
        return "kid_${hash}_$tail.jpg"
    }
}