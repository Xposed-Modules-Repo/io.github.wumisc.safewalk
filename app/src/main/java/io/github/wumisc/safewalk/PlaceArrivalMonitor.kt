package io.github.wumisc.safewalk

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 「到达某类场所」监控：把证件提醒 / 社交提示从「正在走路」搬到「到了地方」。
 *
 * 【原来的问题】
 * Hook 的 `triggerMovement()` 只要确认持续步行，就无条件推送「证件提醒」和
 * 一大段「【求职】优先青旅…」。这两条的内容明明是「到了某类场合该注意什么」，
 * 却跟你人在哪儿毫无关系 —— 在家走动、沙发上起身都会弹，属于触发条件与内容
 * 语义完全错配。
 *
 * 【现在的链路】
 * 低频定位（NETWORK 优先，60s/150m）→ 系统 Geocoder 反编码地址 → 复用
 * [LocationModeEngine] 的地点词库判定「求职 / 约会 / 聚会 / 旅游」→
 * [ArrivalJudge] 确认「连续两次同类别」才算到达 → 按类别发**一条**对应提醒。
 *
 * 【为什么不在 SystemUI(Hook) 侧做】
 * Hook 注入的是 SystemUI 进程，在里面注册定位既拿不到权限也没必要。定位放在
 * App 自己的常驻服务里，判定完直接发通知，链路更短、也更好排查。
 *
 * 【隐私】反编码走系统本地 Geocoder，坐标不出设备，也不落盘（只存 1km 网格
 * 字符串用于「同一地点不重复提醒」）。
 */
object PlaceArrivalMonitor {

    /** 定位间隔：60 秒。到达场景以分钟计，没必要更密。 */
    private const val MIN_INTERVAL_MS = 60_000L

    /** 位移阈值：150 米。低于它不回调，进一步省电。 */
    private const val MIN_DISTANCE_M = 150f

    private const val SP_NAME = "safewalk_xsp"
    private const val KEY_SEEN = "arriveSeenPlaces"
    private const val SEEN_MAX = 80

    /** 证件提醒（旅游类）：同一地点 24 小时内只提醒一次 —— 出差常去同一车站，隔天该再提。 */
    private const val TTL_ID_MS = 24L * 60 * 60 * 1000

    /** 社交提示：同一地点 30 天内只提醒一次 —— 否则每天通勤到公司都会弹「面试礼仪」。 */
    private const val TTL_SOCIAL_MS = 30L * 24 * 60 * 60 * 1000

    private var appCtx: Context? = null
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null

    @Volatile
    private var running = false

    private val judge = ArrivalJudge()

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "safewalk-place").apply { isDaemon = true }
    }

    /** Geocoder 是同步阻塞调用，用这个标记保证同一时刻只有一次反编码在跑 */
    @Volatile
    private var busy = false

    /** 上一次采样打日志的时间（节流，避免每分钟刷屏） */
    private var lastSampleLogTs = 0L

    @JvmStatic
    fun start(ctx: Context) {
        if (running) return
        val c = ctx.applicationContext

        if (!hasLocationPermission(c)) {
            DiagLog.log("[place] 未授予定位权限 → 到达提醒暂不生效（在权限引导里授上即可）")
            return
        }
        val m = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (m == null) {
            DiagLog.log("[place] 系统无定位服务 → 到达提醒不可用")
            return
        }
        val provider = when {
            m.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            m.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            DiagLog.log("[place] 系统定位开关未开启 → 到达提醒暂不生效")
            return
        }

        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)
            // Android 11+ 必须实现的空实现，否则部分 ROM 会崩
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            m.requestLocationUpdates(provider, MIN_INTERVAL_MS, MIN_DISTANCE_M, l, Looper.getMainLooper())
            appCtx = c
            lm = m
            listener = l
            running = true
            DiagLog.log("[place] 到达提醒已启动 | provider=$provider"
                    + " 间隔=${MIN_INTERVAL_MS / 1000}s/${MIN_DISTANCE_M.toInt()}m")
            // 打开 App 时先用最后已知位置判一次，不必等满 60 秒
            try {
                m.getLastKnownLocation(provider)?.let { onLocation(it) }
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            DiagLog.log("[place] 定位注册失败: ${t.message}")
        }
    }

    @JvmStatic
    fun stop() {
        if (!running) return
        running = false
        try {
            listener?.let { lm?.removeUpdates(it) }
        } catch (_: Throwable) {
        }
        listener = null
        lm = null
        judge.reset()
        DiagLog.log("[place] 到达提醒已停止")
    }

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun onLocation(loc: Location) {
        if (busy) return
        val c = appCtx ?: return
        busy = true
        worker.execute {
            try {
                LocationModeEngine.load(c)
                val addr = LocationModeEngine.addressOf(c, loc)
                val kind = if (addr.isNullOrBlank()) null else LocationModeEngine.suggestFromAddress(addr)
                evaluate(c, loc, addr, kind)
            } catch (t: Throwable) {
                DiagLog.log("[place] 判定异常: ${t.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun evaluate(ctx: Context, loc: Location, addr: String?, kind: String?) {
        val now = System.currentTimeMillis()
        val prev = judge.pendingKind
        val hit = judge.onSample(kind, now)

        if (hit == null) {
            // 首次进入某类场所范围（还没够停留次数）留一条，方便真机确认「识别到了但没弹」
            if (kind != null && judge.pendingHits == 1) {
                DiagLog.log("[place] 进入「$kind」类场所范围（待停留确认）| 地址=${shorten(addr)}")
            } else if (kind == null && prev != null) {
                DiagLog.log("[place] 离开「$prev」类场所，候选清零")
            } else if (now - lastSampleLogTs > 10 * 60 * 1000L) {
                lastSampleLogTs = now
                DiagLog.log("[place] 采样：${if (kind == null) "不在已知场所" else "「$kind」类"}"
                        + " | 地址=${shorten(addr)}")
            }
            return
        }

        deliver(ctx, loc, hit, addr)
    }

    /** 到达确认后发**一条**提醒：旅游→证件；求职/约会/聚会→到场礼仪。 */
    private fun deliver(ctx: Context, loc: Location, kind: String, addr: String?) {
        val prefs = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val grid = gridKey(loc)
        val nm = SafeWalkNotificationManager(ctx)

        if (kind == "旅游") {
            // 交通枢纽 / 景区 / 酒店民宿：核心风险是证件丢失
            if (!SafePrefs.getBool(prefs, SafeWalkPrefKey.enableIdReminder, true)) {
                DiagLog.log("[place] 到达「旅游」场所，但证件提醒开关是关的，跳过")
                return
            }
            val key = "id|$grid"
            if (!allow(prefs, key, now, TTL_ID_MS)) {
                DiagLog.log("[place] 到达「旅游」场所（$grid），该地点今天已提醒过，跳过")
                return
            }
            nm.idReminder(kind)
            mark(prefs, key, now)
            DiagLog.log("[place] ✅ 到达「旅游」场所（$grid）→ 已发证件提醒 | 地址=${shorten(addr)}")
            return
        }

        // 求职 / 约会 / 聚会：到场礼仪提示，内容按类别取，不再用用户设置的出行模式硬套
        if (!SafePrefs.getBool(prefs, SafeWalkPrefKey.enableSocialTipCard, true)) {
            DiagLog.log("[place] 到达「$kind」场所，但社交提示开关是关的，跳过")
            return
        }
        val tips = TravelTemplate.getArrivalTips(kind)
        if (tips.isEmpty()) return
        val key = "soc|$grid"
        if (!allow(prefs, key, now, TTL_SOCIAL_MS)) {
            DiagLog.log("[place] 到达「$kind」场所（$grid），该地点已提醒过，跳过")
            return
        }
        nm.socialTip(tips.joinToString("；"), kind)
        mark(prefs, key, now)
        DiagLog.log("[place] ✅ 到达「$kind」场所（$grid）→ 已发到场提示 | 地址=${shorten(addr)}")
    }

    /** 约 1.1km 网格，用于「同一地点不重复提醒」；坐标精度降到 0.01° 也算轻度脱敏。 */
    private fun gridKey(loc: Location): String =
        String.format(Locale.US, "%.2f,%.2f", loc.latitude, loc.longitude)

    private fun allow(prefs: SharedPreferences, key: String, now: Long, ttl: Long): Boolean {
        val raw = prefs.getString(KEY_SEEN, null) ?: return true
        return try {
            val ts = JSONObject(raw).optLong(key, 0L)
            ts == 0L || now - ts > ttl
        } catch (_: Throwable) {
            true
        }
    }

    private fun mark(prefs: SharedPreferences, key: String, now: Long) {
        try {
            val raw = prefs.getString(KEY_SEEN, null)
            val o = if (raw.isNullOrBlank()) JSONObject()
            else try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }
            o.put(key, now)
            if (o.length() > SEEN_MAX) {
                val keys = o.keys().asSequence().toList().sortedBy { o.optLong(it, 0L) }
                for (k in keys.take(o.length() - SEEN_MAX)) o.remove(k)
            }
            prefs.edit().putString(KEY_SEEN, o.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    private fun shorten(addr: String?): String {
        if (addr.isNullOrBlank()) return "-"
        return if (addr.length <= 40) addr else addr.substring(0, 40) + "…"
    }
}
