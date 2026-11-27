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

    /**
     * 【5.7.16】数据源隔天更新，检查间隔跟着轮换周期放到 48 小时：
     * 轮换两天才换一批，数据没必要一天拉一次，请求量直接减半。
     */
    const val REFRESH_MS = 48L * 3600_000L

    /**
     * 【省流量 · 失败退避】一次没取到就别反复试。
     *
     * 真机日志里两个 JSON 源都被国内网络 reset，而旧实现失败后**不记失败时间**，
     * 于是每次进首页都会重新打两轮请求（6s 超时 ×2），既费流量又拖慢卡片出图。
     * 现在失败后 6 小时内不再尝试，直接用已有缓存；期间一旦成功就清零。
     */
    const val RETRY_BACKOFF_MS = 6L * 3600_000L

    /**
     * 【5.7.16】轮换周期两天换一批（用户要求：别为了多看几张脸给用户加流量压力）。
     *
     * 四条一批、每 48 小时整批前进 4 位：7 条数据两批（4 天）转完一轮。
     * 轮换本身是纯本地算术，不产生任何请求；配合 [REFRESH_MS] 一起放到 48 小时，
     * 拉数据的频率也跟着减半 —— 这才是真正省流量的一环。
     */
    const val ROTATE_HOURS = 48L
    private const val ROTATE_MS = ROTATE_HOURS * 3600_000L

    /** 旧口径按天推进，保留常量供旧调用/测试引用 */
    const val ROTATE_DAYS = 1L

    private const val RAW = "https://raw.githubusercontent.com/wumisc/antiburn-missing-kids/main/kids.json"
    private const val CDN = "https://cdn.jsdelivr.net/gh/wumisc/antiburn-missing-kids@main/kids.json"
    private const val CACHE = "missing_kids.json"
    /** APK 内置快照（assets/kids.json）：网络与缓存都没有时的最后一级，随包更新 */
    private const val ASSET = "kids.json"
    private const val SP = "safewalk"
    private const val KEY_TS = "missingKidsTs"
    private const val KEY_FAIL_TS = "missingKidsFailTs"
    /** 轮换锚点：首次看到当前这份数据的日期（天序号） */
    private const val KEY_ANCHOR_DAY = "missingKidsAnchorDay"
    /** 轮换锚点绑定的数据版本（updated 变了就重置锚点，新的顶到最前） */
    private const val KEY_ANCHOR_SRC = "missingKidsAnchorSrc"

    /** 一天的毫秒数：轮换按「自然天」推进，同一天里两张卡不重复 */
    private const val DAY_MS = 24L * 3600_000L

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

    /** 首页公益卡展示的孩子条数（列表四条）。 */
    const val SHOW_COUNT = 4

    /**
     * 最后一级兜底（**只在 assets/kids.json 读取失败时**才会用到，正常永远走内置快照）。
     *
     * 旧实现把它当常驻兜底，于是国内网络下每次打开都退回这两条老数据 —— 用户看到的
     * 就是「覆盖安装后公益卡又变回第一次打开的样子」。现在它退化成纯保险。
     */
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
        val photo: String,
        /** 登记日期（yyyy-MM-dd，可空）：数据源已按它倒序，内置快照再做一次排序兜底 */
        val regDate: String = "",
        /** 宝贝回家编号：同日期的二次排序键，0 表示数据没给 */
        val id: Int = 0
    )

    /**
     * 一整份数据（不是「两条」）：轮换需要全量条目才能每天换一对。
     *
     * @param updated 数据版本串（ISO 时间），既用来判断「哪份更新」，也当轮换锚点的归属标识
     */
    data class Snapshot(val updated: String, val kids: List<Kid>)

    /** 纯解析（可单测）：取前 [limit] 条。失败返回空列表。 */
    @JvmStatic
    @JvmOverloads
    fun parseList(json: String, limit: Int = SHOW_COUNT): List<Kid> = try {
        val a = JSONObject(json).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Kid>()
        for (i in 0 until minOf(a.length(), limit)) {
            val o = a.getJSONObject(i)
            out.add(kidOf(o))
        }
        out
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * 纯解析（可单测）：整份快照，条目按「最新发布优先」排好。
     *
     * 上游自己就是倒序，但内置快照是手工维护的，两次排序键（登记日期、编号）兜一层，
     * 保证轮换从最新的一条开始。结构不对返回 null，绝不抛。
     */
    @JvmStatic
    fun parseSnapshot(json: String): Snapshot? = try {
        val o = JSONObject(json)
        val a = o.optJSONArray("items") ?: return null
        val out = ArrayList<Kid>(a.length())
        for (i in 0 until a.length()) {
            val k = a.optJSONObject(i) ?: continue
            val kid = kidOf(k)
            if (kid.name.isBlank() && kid.lostPlace.isBlank()) continue
            out.add(kid)
        }
        out.sortWith(compareByDescending<Kid> { it.regDate }.thenByDescending { it.id })
        if (out.isEmpty()) null else Snapshot(o.optString("updated"), out)
    } catch (_: Throwable) {
        null
    }

    /**
     * 锚点决策（纯函数，可单测）：什么时候把「今天」重新当成第一对。
     *
     * 【5.6.19】只有两种情况重置：
     *  · 第一次看到（没有任何锚点）；
     *  · **真的从网络拉到新的数据版本**（fresh=true 且 updated 变了）。
     *
     * 关键点：仅仅因为「包内快照比缓存新」就重置锚点，会让每次覆盖安装都跳回第一对 ——
     * 用户看到的就是「又变回第一次」。所以升级后应当接着上次的位置继续往前转，
     * 只有数据源真的更新了才把新发布的孩子顶到最前。
     */
    @JvmStatic
    fun anchorDayOf(anchorDay: Long, anchorSrc: String, today: Long, updated: String, fresh: Boolean): Long =
        if (anchorDay < 0L || (fresh && anchorSrc != updated)) today else anchorDay

    private fun kidOf(o: JSONObject): Kid = Kid(
        o.optString("name"), o.optString("gender"),
        o.optString("lost_time"), o.optString("lost_place"),
        o.optString("feature"), o.optString("url"), o.optString("photo"),
        o.optString("reg_date"), o.optInt("id")
    )

    /**
     * 列表下标（纯函数，可单测）：从 [anchorDay] 起，每 [ROTATE_HOURS] 小时整批前进
     * [count] 位，返回 [count] 个**互不重复**的连续下标。
     *
     * · 时间用毫秒，槽号 = floor(now / ROTATE_MS)；
     *   anchorDay（天序号）换算成同一个槽号，保证老用户已有的锚点继续有效。
     * · 条目少于 [count] 时按环形取，最多把全部条目轮一遍（size 条就 size 条，不重复）。
     */
    @JvmStatic
    fun listIndices(nowMs: Long, anchorDay: Long, size: Int, count: Int): List<Int> {
        if (size <= 0) return emptyList()
        val take = count.coerceIn(1, size)
        val slot = nowMs / ROTATE_MS
        // 先乘后除：ROTATE_MS 比一天长时 DAY_MS / ROTATE_MS 会整除成 0，锚点会被算到第 0 槽
        val anchorSlot = (anchorDay * DAY_MS) / ROTATE_MS
        val steps = (slot - anchorSlot).coerceAtLeast(0L)
        val base = ((steps * take) % size).toInt()
        return (0 until take).map { (base + it) % size }
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

    /**
     * 阻塞加载（后台线程）：一级级往下退，任何一级都不抛。
     *
     * 顺序：24h 内缓存 → 失败退避期用本地 → 联网（raw → CDN）→ 本地 → 内置快照。
     *
     * 【5.6.18 BUG 修复 · 覆盖安装后又回到「第一次」】
     * 旧实现最后一级是**写死在代码里的两条老数据**，而国内网络下 raw / jsDelivr 常年
     * 被 reset，缓存根本没机会写入 → 每次打开都退回那两条，看起来就是「覆盖安装把
     * 上一次的卡丢了，又换回第一次打开的样子」。现在：
     * · 最后一级改成随包更新的 `assets/kids.json`（7 条，与 antiburn 同一份数据源），
     *   覆盖安装本身就带来最新快照；
     * · 缓存与内置快照之间按 `updated` 取新的那份，不会拿旧快照盖掉新缓存；
     * · 两张卡按天轮换，即便一直离线也不会永远钉在同一张脸。
     */
    private fun loadBlocking(ctx: Context): List<Kid> {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val cache = File(ctx.filesDir, CACHE)
        val ts = sp.getLong(KEY_TS, 0)
        val failTs = sp.getLong(KEY_FAIL_TS, 0)
        val now = System.currentTimeMillis()
        // 缓存新鲜 → 直接用，不请求网络
        if (cache.exists() && now - ts < REFRESH_MS) {
            readSnap(cache)?.let { return rotated(ctx, it, now) }
        }
        // 【省流量 · 退避】上一轮失败不到 6 小时 → 这次不联网，直接用缓存/内置快照。
        // 不能因为「昨天没取到」就每次进首页都重打两轮请求。
        if (now - failTs < RETRY_BACKOFF_MS) {
            val local = localBest(ctx, cache)
            DiagLog.log(
                "[kids] 失败退避中（${(RETRY_BACKOFF_MS - (now - failTs)) / 60_000} 分钟后重试），"
                    + "用本地快照 v${local.updated}"
            )
            return rotated(ctx, local, now)
        }
        for (u in listOf(RAW, CDN)) {
            val body = httpGet(u) ?: continue
            val snap = parseSnapshot(body) ?: continue
            // 【省流量 · 先取到再替换】只有拿到有效新数据才覆盖旧缓存；
            // 覆盖的同一时刻清掉失败标记，并把不再需要的旧照片按上限淘汰。
            try {
                cache.parentFile?.mkdirs()
                cache.writeText(body)
                sp.edit().putLong(KEY_TS, now).putLong(KEY_FAIL_TS, 0L).commit()
            } catch (_: Throwable) {
            }
            prunePhotos(ctx, snap.kids)
            return rotated(ctx, snap, now, fresh = true)
        }
        // 网络全失败：记失败时刻进入退避，再用本地快照 → 内置快照
        try {
            sp.edit().putLong(KEY_FAIL_TS, now).commit()
        } catch (_: Throwable) {
        }
        return rotated(ctx, localBest(ctx, cache), now)
    }

    /**
     * 本地可用的最优数据：本机缓存 vs APK 内置快照，按 `updated` 取新的那份。
     *
     * 这条规则是「覆盖安装不倒退」的关键：装新包后内置快照可能比磁盘缓存还新，
     * 就应当用新包里的；反过来缓存里是上次成功拉到的更新数据，也不能被旧快照盖掉。
     */
    private fun localBest(ctx: Context, cache: File): Snapshot {
        val cached = if (cache.exists()) readSnap(cache) else null
        val asset = assetSnapshot(ctx)
        if (cached == null) return asset ?: Snapshot("", parseList(FALLBACK))
        if (asset == null) return cached
        return if (cached.updated >= asset.updated) cached else asset
    }

    /** 读本机缓存；不存在 / 解析空返回 null。 */
    private fun readSnap(file: File): Snapshot? = try {
        if (!file.exists() || file.length() <= 0L) null
        else parseSnapshot(file.readText())
    } catch (_: Throwable) {
        null
    }

    /** 读 APK 内置快照（只读一次，缓存在内存里）。 */
    private var assetSnap: Snapshot? = null

    private fun assetSnapshot(ctx: Context): Snapshot? {
        assetSnap?.let { return it }
        val s = try {
            ctx.assets.open(ASSET).use { parseSnapshot(it.bufferedReader().readText()) }
        } catch (_: Throwable) {
            null
        }
        assetSnap = s
        return s
    }

    /**
     * 按「天」轮换出本次要展示的两条。
     *
     * 锚点 = 第一次看到时的日期；之后每过一天整对前进两位。
     * **只有真拿到网络新数据（fresh=true）且数据版本变了才重置锚点**：这样覆盖安装、
     * 包内快照更新都不会把用户从「昨天看到哪儿」拽回第一对，而数据源真的发布了
     * 新的孩子时，最新的一条能第一时间顶到最前（判定见 [anchorDayOf]）。
     */
    private fun rotated(ctx: Context, snap: Snapshot, now: Long, fresh: Boolean = false): List<Kid> {
        val kids = snap.kids
        if (kids.isEmpty()) return parseList(FALLBACK)
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val today = now / DAY_MS
        val storedAnchor = sp.getLong(KEY_ANCHOR_DAY, -1L)
        val anchorSrc = sp.getString(KEY_ANCHOR_SRC, "") ?: ""
        val anchor = anchorDayOf(storedAnchor, anchorSrc, today, snap.updated, fresh)
        try {
            if (anchor != storedAnchor) {
                sp.edit().putLong(KEY_ANCHOR_DAY, anchor).apply()
            }
            // 版本标识跟着当前这份数据走，但**不因此重置位置**（见 anchorDayOf）
            if (anchorSrc != snap.updated) {
                sp.edit().putString(KEY_ANCHOR_SRC, snap.updated).apply()
            }
        } catch (_: Throwable) {
        }
        if (kids.size == 1) return listOf(kids[0])
        return listIndices(now, anchor, kids.size, SHOW_COUNT).map { kids[it] }
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