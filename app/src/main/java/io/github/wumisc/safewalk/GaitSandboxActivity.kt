package io.github.wumisc.safewalk

import android.app.Activity
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 步态沙盒（测试用）。
 *
 * 为什么要有它：真机每改一次判据就要「装包 → 重启 SystemUI → 下楼走两圈」，
 * 一轮五分钟还不一定复现。这里把传感器流本身录下来，之后在手机本地反复重放，
 * 用**同一份** [GaitDetector] 判一遍，判据改动可以离线对比，不用再靠走路验证。
 *
 * 只做三件事：
 * ① 录制加速度原始序列（时间戳 + ax/ay/az，约 50Hz）；
 * ② 回放：把序列按虚拟时间喂给 GaitDetector，逐秒打印合格步/峰数/闸门拒因；
 * ③ 导出 JSON，跟 diag.log 一起回传即可离线复现。
 *
 * 刻意不接预览服务、不接 Hook、不写偏好 —— 纯离线实验台，关掉页面即结束。
 */
class GaitSandboxActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvReport: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnStop: Button
    private lateinit var btnReplay: Button
    private lateinit var btnExport: Button

    private lateinit var sm: SensorManager
    private var listener: SensorEventListener? = null

    private val samples = ArrayList<DoubleArray>(4096)  // [tMs, ax, ay, az]
    private var recording = false
    private var firstNanos = 0L
    private var lastTrace: MutableList<DoubleArray>? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        setContentView(buildUi())
        tvStatus.text = "待机｜默认录 30 秒；录制结束后点「回放」即可离线判定"
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.parseColor("#F4F4F5"))
        }
        root.addView(TextView(this).apply {
            text = "步态沙盒"
            textSize = 24f
            setTextColor(Color.parseColor("#1A1A1A"))
        })
        root.addView(TextView(this).apply {
            text = "录制真机传感器流，离线重放同一套 GaitDetector。改判据后先在这里看结论，再决定要不要下楼走两圈。"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(8), 0, dp(16))
        })
        tvStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(tvStatus)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRecord = button("录制 30 秒") { startRecord() }
        btnStop = button("停止") { stopRecord() }
        row1.addView(btnRecord, weight())
        row1.addView(btnStop, weight())
        root.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        btnReplay = button("回放并判定") { replay() }
        btnExport = button("导出 JSON") { export() }
        row2.addView(btnReplay, weight())
        row2.addView(btnExport, weight())
        root.addView(row2)

        val scroll = ScrollView(this)
        tvReport = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(tvReport)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        updateButtons()
        return root
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            setOnClickListener { onClick() }
        }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6); marginStart = dp(6)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun updateButtons() {
        btnRecord.isEnabled = !recording
        btnStop.isEnabled = recording
        btnReplay.isEnabled = !recording && lastTrace != null
        btnExport.isEnabled = !recording && lastTrace != null
    }

    // ---------------- 录制 ----------------

    private fun startRecord() {
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) {
            tvStatus.text = "本机没有加速度计，无法录制"
            return
        }
        samples.clear()
        firstNanos = 0L
        recording = true
        var count = 0
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                val v = e?.values ?: return
                if (v.size < 3 || samples.size >= MAX_SAMPLES) return
                // 【5.7.6】时间戳必须取硬件的 e.timestamp（纳秒），不能用 currentTimeMillis：
                // 厂商批处理上报时一包 6~8 帧共用一个墙钟毫秒，导出的 trace 里
                // 83.8% 的相邻样本时间戳完全相同，任何下游算法都还原不出真实步间隔。
                // 这里存成「相对首个样本的毫秒」，保留三位小数。
                if (firstNanos == 0L) firstNanos = e.timestamp
                val tMs = (e.timestamp - firstNanos) / 1_000_000.0
                samples.add(doubleArrayOf(tMs, v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
                count++
                if (count % 50 == 0) {
                    val ms = (e.timestamp - firstNanos) / 1_000_000L
                    main.post { tvStatus.text = "录制中 ${ms / 1000}s｜${samples.size} 帧｜${hzOf(samples)}Hz" }
                }
                if ((e.timestamp - firstNanos) / 1_000_000L >= MAX_RECORD_MS) {
                    main.post { stopRecord() }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        try {
            sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_UI)
            listener = l
            tvStatus.text = "录制中｜请按平时走路的姿势拿手机走动"
        } catch (t: Throwable) {
            recording = false
            tvStatus.text = "录制启动失败：${t.message}"
        }
        updateButtons()
    }

    private fun stopRecord() {
        val l = listener ?: return
        try { sm.unregisterListener(l) } catch (_: Throwable) {}
        listener = null
        if (recording && samples.isNotEmpty()) {
            lastTrace = ArrayList(samples)
            tvStatus.text = "已录制 ${samples.size} 帧 / ${(samples.last()[0] / 1000).toInt()}s｜${hzOf(samples)}Hz｜点「回放并判定」"
        } else {
            tvStatus.text = "没录到数据"
        }
        recording = false
        updateButtons()
    }

    // ---------------- 回放 ----------------

    private fun replay() {
        val trace = lastTrace ?: return
        tvReport.text = "回放中…"
        Thread {
            val lines = StringBuilder()
            val detector = GaitDetector()
            val confirm = SafePrefs.getInt(
                getSharedPreferences("safewalk_xsp", MODE_PRIVATE),
                SafeWalkPrefKey.gaitConfirmSteps, 8).coerceIn(8, 10)
            detector.confirmSteps = confirm
            detector.reset()
            // 【5.7.3-lx102 · 115】回放必须和真机走同一条判定链：
            // 只算 GaitDetector 的「连续合格步」不够 —— 服务里还要再过
            // WalkConfirmPolicy（节律、跨度、平放专用阈值）。沙盒结论如果只报
            // 「检测器达标」，会出现「沙盒说会激活、真机不弹」的错觉。
            val policy = WalkConfirmPolicy(confirm)
            val stepTs = ArrayList<Long>()
            var lastLogMs = -1L
            var maxConfirmed = 0
            var stepHits = 0
            var firstPassMs = -1L
            var lastReject = ""
            var frames = 0
            for (s in trace) {
                // 【5.7.6】时间轴存的是小数毫秒，回放时四舍五入到毫秒再喂：
                // 117Hz 下相邻帧是 8.5ms，截断会把它压成 8ms 并累积漂移，四舍五入的抖动只有 ±0.5ms。
                val t = Math.round(s[0])
                val ok = detector.feedAcc(s[1].toFloat(), s[2].toFloat(), s[3].toFloat(), t)
                frames++
                if (ok) {
                    stepHits++
                    stepTs.add(t)
                }
                maxConfirmed = maxOf(maxConfirmed, detector.confirmedStepCount())
                if (firstPassMs < 0 && stepTs.size >= confirm) {
                    val flat = detector.currentAttitude == PhoneAttitude.LAY_FLAT
                    val verdict = policy.evaluate(flat, stepTs.size, stepTs, t)
                    if (verdict.pass) firstPassMs = t else lastReject = verdict.reason
                }
                // 每秒一行
                if (t / 1000 != lastLogMs) {
                    lastLogMs = t / 1000
                    lines.append(String.format(
                        "%3ds 合格=%d/%d 出步=%d %s\n",
                        lastLogMs, detector.confirmedStepCount(), confirm,
                        stepHits, detector.debugInfo()))
                    stepHits = 0
                }
            }
            lines.append("\n—— 结论 ——\n")
            lines.append("帧数=$frames 最长连续合格步=$maxConfirmed/$confirm ")
            lines.append(if (firstPassMs >= 0) "首次达标于 ${firstPassMs / 1000}s（会激活）\n"
            else "全程未达标（不会激活）\n")
            if (lastReject.isNotEmpty()) {
                lines.append("最近一次策略拦截原因：$lastReject\n")
            }
            lines.append("姿态终值=${detector.getAttitude()} 垂直占比=")
            lines.append(String.format("%.2f", detector.verticalRatio()))
            main.post {
                tvReport.text = lines.toString()
                if (tvReport.parent != null) (tvReport.parent as ScrollView).post {
                    (tvReport.parent as ScrollView).fullScroll(View.FOCUS_UP)
                }
                tvStatus.text = "回放完成：$frames 帧"
            }
        }.start()
    }

    // ---------------- 导出 ----------------

    private fun export() {
        val trace = lastTrace ?: return
        try {
            val arr = JSONArray()
            for (s in trace) {
                arr.put(JSONObject()
                    .put("t", Math.round(s[0] * 1000.0) / 1000.0)
                    .put("x", s[1])
                    .put("y", s[2])
                    .put("z", s[3]))
            }
            val durationMs = if (trace.isEmpty()) 0.0 else trace.last()[0]
            // 【5.7.6】hz 不再写死「≈50」。这行以前是硬编码的谎话：
            // 真机实测同一段 30 秒导出了 3523 帧，实际 117Hz，文件里却写着 50Hz，
            // 下游按 50Hz 换算窗口长度，步频直接算成 2.3 倍。现在从数据本身算。
            val hz = if (durationMs > 0) trace.size * 1000.0 / durationMs else 0.0
            val dir = File(DiagLog.USER_DIR)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "gait_trace_${System.currentTimeMillis()}.json")
            f.writeText(JSONObject()
                .put("frames", trace.size)
                .put("hz", "%.1f".format(hz))
                .put("durationMs", Math.round(durationMs))
                .put("note", "步态沙盒导出的加速度原始序列（t=SensorEvent.timestamp 硬件时钟换算的相对毫秒，含三位小数；hz 为实际帧率）")
                .put("samples", arr)
                .toString())
            tvStatus.text = "已导出：${f.absolutePath}"
            DiagLog.log("[sandbox] 步态 trace 已导出 ${f.absolutePath}（${trace.size} 帧 / ${"%.0f".format(hz)}Hz）")
        } catch (t: Throwable) {
            tvStatus.text = "导出失败：${t.message}"
        }
    }

    /**
     * 【5.7.6】按样本间隔算实际帧率（Hz）。
     *
     * 用「总帧数 / 总时长」会被开头结尾的空档带偏，这里取间隔中位数，
     * 对批处理上报造成的偶发长间隔更稳。
     */
    private fun hzOf(trace: List<DoubleArray>): String {
        if (trace.size < 3) return "—"
        val n = minOf(trace.size - 1, 400)
        val dts = ArrayList<Double>(n)
        for (i in 1..n) dts.add(trace[i][0] - trace[i - 1][0])
        dts.sort()
        val med = dts[dts.size / 2]
        return if (med <= 0.0) "—" else "%.0f".format(1000.0 / med)
    }

    override fun onDestroy() {
        listener?.let { try { sm.unregisterListener(it) } catch (_: Throwable) {} }
        listener = null
        super.onDestroy()
    }

    companion object {
        private const val MAX_RECORD_MS = 30_000L
        private const val MAX_SAMPLES = 6000
    }
}
