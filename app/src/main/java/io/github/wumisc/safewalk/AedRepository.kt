package io.github.wumisc.safewalk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AED 仓库：内置 GeoJSON + 用户自建 GeoJSON 合并、Haversine 检索、导入导出。
 *
 * 全程 org.json + 内存计算，不引 OSM/地图 SDK、不发网络请求。
 * 用户数据 source=USER 覆盖同 id 的内置 OSM 点。
 */
object AedRepository {
    const val ASSET_FILE = "aed_cn.geojson"
    const val USER_FILE = "aed_user.geojson"
    const val DEFAULT_RADIUS_M = 500

    /** 加载并去重：内置示范 + 用户自建（USER 优先） */
    @JvmStatic
    fun load(ctx: Context): List<AedPoi> {
        val linked = LinkedHashMap<String, AedPoi>()
        try {
            ctx.assets.open(ASSET_FILE).use { stream ->
                parseGeoJSON(stream.bufferedReader().use { it.readText() })
                    .forEach { linked[it.id] = it }
            }
        } catch (_: Throwable) {
        }
        try {
            val f = File(ctx.filesDir, USER_FILE)
            if (f.exists()) {
                parseGeoJSON(f.readText()).forEach { linked[it.id] = it }
            }
        } catch (_: Throwable) {
        }
        return linked.values.toList()
    }

    /** 解析 FeatureCollection；缺字段一律用默认值，绝不因脏数据崩溃 */
    @JvmStatic
    fun parseGeoJSON(raw: String): List<AedPoi> = try {
        val root = JSONObject(raw)
        val features: JSONArray = root.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<AedPoi>(features.length())
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val geom = f.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lng = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lng.isNaN()) continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val fid = f.optString("id", "").ifBlank {
                "${p.optString("name", "poi")}-${lat}-${lng}"
            }
            out.add(
                AedPoi(
                    id = fid,
                    name = p.optString("name", "AED"),
                    lat = lat,
                    lng = lng,
                    address = p.optString("addr:full", p.optString("address", "")),
                    access = p.optString("access", ""),
                    openingHours = p.optString("opening_hours", ""),
                    phone = p.optString("phone", ""),
                    source = p.optString("source", "OSM").uppercase(),
                    verifiedAt = p.optLong("verified_at", 0L)
                )
            )
        }
        out
    } catch (_: Throwable) {
        emptyList()
    }

    /** 500m 内最近的点（纯内存 Haversine；无数据返回 null） */
    @JvmStatic
    @JvmOverloads
    fun nearestWithin(
        pois: List<AedPoi>,
        lat: Double,
        lng: Double,
        radiusM: Int = DEFAULT_RADIUS_M
    ): AedPoi? {
        var best: AedPoi? = null
        var bestD = Double.MAX_VALUE
        for (p in pois) {
            val d = p.distanceTo(lat, lng)
            if (d <= radiusM && d < bestD) {
                best = p
                bestD = d
            }
        }
        return best
    }

    @JvmStatic
    fun nearestWithin(ctx: Context, lat: Double, lng: Double, radiusM: Int = DEFAULT_RADIUS_M): AedPoi? =
        nearestWithin(load(ctx), lat, lng, radiusM)

    /** 导入用户 GeoJSON：返回导入条数（整份替换用户数据源） */
    @JvmStatic
    fun importGeoJSON(ctx: Context, raw: String): Int {
        val pois = parseGeoJSON(raw)
        if (pois.isEmpty()) return 0
        val out = JSONObject()
        val arr = JSONArray()
        pois.forEach { p ->
            val props = JSONObject()
                .put("name", p.name)
                .put("addr:full", p.address)
                .put("access", p.access)
                .put("opening_hours", p.openingHours)
                .put("phone", p.phone)
                .put("source", "USER")
                .put("verified_at", p.verifiedAt)
            val geom = JSONObject().put("type", "Point")
                .put("coordinates", JSONArray().put(p.lng).put(p.lat))
            arr.put(JSONObject().put("type", "Feature").put("id", p.id)
                .put("properties", props).put("geometry", geom))
        }
        out.put("type", "FeatureCollection").put("features", arr)
        File(ctx.filesDir, USER_FILE).writeText(out.toString())
        EmergencyDiag.event(EmergencyDiag.P_AED, "imported count=${pois.size}")
        return pois.size
    }

    /** 导出用户自建 POI（round-trip 一致） */
    @JvmStatic
    fun exportGeoJSON(ctx: Context): String {
        val f = File(ctx.filesDir, USER_FILE)
        return if (f.exists()) f.readText() else "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}
