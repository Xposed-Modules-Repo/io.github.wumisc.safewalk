package io.github.wumisc.safewalk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * AED 导航页（T05）：三态 UI（找到 / 无数据 / 无定位），全本地计算。
 *
 * 无数据 100% 显示"请立即拨打 120" + ACTION_DIAL 预填按钮（不静默代拨）。
 * 定位只用 getLastKnownLocation，精度差时单次 requestSingleUpdate + 3s 超时，
 * 不新增长驻定位、不新增 wake lock。
 */
class AedGuideActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val status = TextView(this)
    private val detail = TextView(this)
    private var target: AedPoi? = null
    private var myLat = 0.0
    private var myLng = 0.0

    companion object {
        /** 无定位且无用户预设坐标时的兜底：按常住地址（北京）估算，页面明示 */
        private const val FALLBACK_LAT = 39.9042
        private const val FALLBACK_LNG = 116.4074
        private const val SINGLE_FIX_TIMEOUT_MS = 3_000L
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F2F7"))
            setPadding(48, 96, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(heading("附近 AED / 急救装置"))
        root.addView(TextView(this).apply {
            text = "纯本地离线检索（500 米内）。无数据时请立即拨打 120。"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 12, 0, 24)
        })
        status.apply {
            textSize = 20f
            setTextColor(Color.parseColor("#1D1D1D"))
            setPadding(0, 8, 0, 8)
        }
        root.addView(status)
        detail.apply {
            textSize = 16f
            setTextColor(Color.parseColor("#3A3A3C"))
            setPadding(0, 8, 0, 16)
        }
        root.addView(detail)

        val mapBtn = Button(this).apply { text = "用地图打开步行导航" }
        mapBtn.setOnClickListener { openInMap() }
        root.addView(mapBtn)

        val dial = Button(this).apply {
            text = "拨打 120"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C8392E"))
        }
        dial.setOnClickListener { dial120() }
        root.addView(dial, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })

        val back = Button(this).apply { text = "返回" }
        back.setOnClickListener { finish() }
        root.addView(back, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 })
        setContentView(root)

        resolveLocationAndRender()
    }

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
        setTextColor(Color.parseColor("#1D1D1D"))
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 8)
    }

    /** 定位 → 单次修正 → 500m 检索 → 三态渲染 */
    private fun resolveLocationAndRender() {
        val loc = lastKnown()
        if (loc != null) {
            myLat = loc.latitude
            myLng = loc.longitude
            render()
            // 精度差时请求一次修正，成功后重渲染；3s 后无条件移除
            if (loc.accuracy > 100f) requestSingleFix()
            return
        }
        // 无定位：用预设坐标估算（页面明示），仍可给出 AED 列表；无数据则 120 兜底
        myLat = FALLBACK_LAT
        myLng = FALLBACK_LNG
        render(estimated = true)
        requestSingleFix()
    }

    private fun lastKnown(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Throwable) {
            null
        }
    }

    private fun requestSingleFix() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        var listener: LocationListener? = null
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            listener = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    myLat = l.latitude
                    myLng = l.longitude
                    render()
                    handler.removeCallbacksAndMessages(null)
                    try { lm.removeUpdates(this) } catch (_: Throwable) {}
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            handler.postDelayed({
                try { lm.removeUpdates(listener) } catch (_: Throwable) {}
            }, SINGLE_FIX_TIMEOUT_MS)
        } catch (_: Throwable) {
            if (listener != null) handler.removeCallbacksAndMessages(null)
        }
    }

    private fun render(estimated: Boolean = false) {
        val pois = AedRepository.load(this)
        val found = AedRepository.nearestWithin(pois, myLat, myLng, AedRepository.DEFAULT_RADIUS_M)
        target = found
        if (found == null) {
            status.text = "附近暂无已知 AED"
            detail.text = "请立即拨打 120，或向现场人员求助。\n\n内置示范数据有限，可在急救卡编辑页导入自建 POI。"
            EmergencyDiag.event(EmergencyDiag.P_AED, "found=false radius=${AedRepository.DEFAULT_RADIUS_M}m")
            return
        }
        val distance = found.distanceTo(myLat, myLng)
        val bearing = AedPoi.bearing(myLat, myLng, found.lat, found.lng)
        status.text = "约 ${distance.toInt()} 米 · 朝${AedPoi.bearingText(bearing)}方向步行"
        val parts = mutableListOf<String>()
        if (found.name.isNotBlank()) parts.add(found.name)
        if (found.address.isNotBlank()) parts.add(found.address)
        if (found.access.isNotBlank()) parts.add("进入方式：${found.access}")
        if (found.openingHours.isNotBlank()) parts.add("开放：${found.openingHours}")
        if (found.phone.isNotBlank()) parts.add("电话：${found.phone}")
        if (estimated) parts.add("（无定位，按默认地址估算）")
        detail.text = parts.joinToString("\n")
        // 坐标绝不写日志：只记 found / 距离 / 来源
        EmergencyDiag.event(
            EmergencyDiag.P_AED,
            "found=true distance=${distance.toInt()}m source=${found.source}"
        )
    }

    private fun openInMap() {
        val p = target
        if (p == null) {
            Toast.makeText(this, "附近暂无已知 AED，请直接拨打 120", Toast.LENGTH_LONG).show()
            return
        }
        val uri = Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}(${Uri.encode(p.name)})")
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(packageManager) != null) {
            try {
                startActivity(i)
                EmergencyDiag.event(EmergencyDiag.P_AED, "map_opened")
                return
            } catch (_: Throwable) {
            }
        }
        // 无地图 App：只保留文字方位与坐标（显示在页面上），不空白
        detail.text = "${detail.text}\n\n坐标：${p.lat}, ${p.lng}（未安装地图应用，请按方位步行）"
    }

    private fun dial120() {
        val ok = SystemSosLauncher.dialEmergency(this, "120")
        if (!ok) Toast.makeText(this, "未找到拨号应用，请手动拨打 120", Toast.LENGTH_LONG).show()
        EmergencyDiag.event(EmergencyDiag.P_AED, "dial_120 prefilled=$ok")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
