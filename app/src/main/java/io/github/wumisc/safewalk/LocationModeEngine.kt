package io.github.wumisc.safewalk

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import org.json.JSONObject

/**
 * 基于定位地点的「出行模式」自动识别引擎。
 *
 * 原理：拿到坐标 → 系统 Geocoder 反编码成地址文本 → 用内置关键词词库做投票，
 * 得分最高且领先第二名 ≥ [CONFIDENCE_GAP] 的模式即为建议，返回 null 表示「无建议、
 * 维持现状」。纯离线关键词匹配，不依赖任何第三方地图 SDK。
 *
 * 【词库来源】内置 assets/travel_mode_dict.json，支持「联网更新」：联网时把新版
 * 词库下载到 files/ 同名文件，本引擎加载时优先读 files 覆盖 assets 里的旧版。
 *
 * 【边界】系统 Geocoder 在部分国产 ROM 离线时可能返回空，此时 judge 返回 null，
 * 引擎不乱切模式——这正是「拿不到信息就不打扰」的兜底。
 */
object LocationModeEngine {

    private const val ASSET_NAME = "travel_mode_dict.json"
    private const val CONFIDENCE_GAP = 2

    @Volatile
    private var dict: Map<String, List<String>>? = null

    /** 加载词库：files 优先（联网更新的覆盖），否则 assets 内置 */
    @JvmStatic
    fun load(ctx: Context) {
        if (dict != null) return
        synchronized(this) {
            if (dict != null) return
            val text = readDictText(ctx) ?: return
            try {
                val o = JSONObject(text)
                val m = HashMap<String, List<String>>()
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = o.optJSONArray(k) ?: continue
                    val l = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) l.add(arr.getString(i))
                    m[k] = l
                }
                if (m.isNotEmpty()) dict = m
            } catch (_: Throwable) {
            }
        }
    }

    private fun readDictText(ctx: Context): String? = try {
        // 联网更新覆盖版优先；没有则读内置 assets
        val files = ctx.getFileStreamPath(ASSET_NAME)
        if (files.exists() && files.length() > 0) {
            files.readText()
        } else {
            ctx.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }
    } catch (_: Throwable) {
        null
    }

    /** 把下载的词库写入 files，供后续加载覆盖 assets 内置版 */
    @JvmStatic
    fun updateFromNetwork(ctx: Context, newText: String): Boolean = try {
        ctx.openFileOutput(ASSET_NAME, Context.MODE_PRIVATE).use { it.write(newText.toByteArray()) }
        synchronized(this) { dict = null } // 让下次 load 重新解析
        true
    } catch (_: Throwable) {
        false
    }

    /** 地址文本 → 建议模式（未命中/置信不足返回 null） */
    @JvmStatic
    fun suggestFromAddress(address: String): String? {
        val d = dict ?: return null
        val votes = HashMap<String, Int>()
        for ((mode, kws) in d) {
            var score = 0
            for (kw in kws) if (address.contains(kw)) score++
            if (score > 0) votes[mode] = score
        }
        if (votes.isEmpty()) return null
        val top = votes.maxByOrNull { it.value } ?: return null
        val second = votes.values.sortedDescending().getOrElse(1) { 0 }
        return if (top.value - second >= CONFIDENCE_GAP) top.key else null
    }

    /** 定位 → 地址 → 建议模式（定位空或反编码失败返回 null） */
    @JvmStatic
    fun judge(ctx: Context, loc: Location?): String? {
        if (loc == null) return null
        val address = reverseGeocode(ctx, loc) ?: return null
        return suggestFromAddress(address)
    }

    @Suppress("DEPRECATION") // 系统 Geocoder.getFromLocation 无等效替代，Android 14 仅标记
    private fun reverseGeocode(ctx: Context, loc: Location): String? {
        return try {
            val gc = Geocoder(ctx, java.util.Locale.getDefault())
            val list: List<Address> = gc.getFromLocation(loc.latitude, loc.longitude, 1) ?: return null
            if (list.isEmpty()) return null
            val a = list[0]
            val sb = StringBuilder()
            val n = a.maxAddressLineIndex
            for (i in 0..n) {
                a.getAddressLine(i)?.let { sb.append(it) }
            }
            if (sb.isEmpty()) {
                val parts = arrayOf(a.adminArea, a.locality, a.subLocality, a.thoroughfare, a.subThoroughfare)
                for (p in parts) if (!p.isNullOrBlank()) sb.append(p)
            }
            if (sb.isEmpty()) null else sb.toString()
        } catch (_: Throwable) {
            null
        }
    }
}
