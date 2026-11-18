package io.github.wumisc.safewalk

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AED / 急救装置 POI。
 *
 * 数据源：内置 assets/aed_cn.geojson（OSM 示范）+ 用户自建 files/aed_user.geojson。
 * 全本地计算，零网络请求、零地图 SDK。
 */
data class AedPoi(
    val id: String = "",
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val address: String = "",
    val access: String = "",
    val openingHours: String = "",
    val phone: String = "",
    val source: String = "OSM",
    val verifiedAt: Long = 0L
) {
    /** 到目标点的直线距离（米），Haversine 纯函数 */
    @JvmOverloads
    fun distanceTo(targetLat: Double, targetLng: Double): Double =
        haversine(lat, lng, targetLat, targetLng)

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Haversine 球面距离（米），坐标非法时返回 Double.MAX_VALUE */
        @JvmStatic
        fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            if (lat1 !in -90.0..90.0 || lat2 !in -90.0..90.0 ||
                lng1 !in -180.0..180.0 || lng2 !in -180.0..180.0
            ) return Double.MAX_VALUE
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLng / 2) * sin(dLng / 2)
            return 2 * EARTH_RADIUS_M * sqrt(a.coerceIn(0.0, 1.0))
        }

        /** 从当前位置指向 POI 的方位角（0=北，90=东） */
        @JvmStatic
        fun bearing(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Double {
            val p1 = Math.toRadians(fromLat)
            val p2 = Math.toRadians(toLat)
            val dl = Math.toRadians(toLng - fromLng)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        /** 方位角 → 中文八方位文字 */
        @JvmStatic
        fun bearingText(deg: Double): String {
            val names = arrayOf("正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北")
            return names[((deg + 22.5) / 45.0).toInt() % 8]
        }
    }
}
