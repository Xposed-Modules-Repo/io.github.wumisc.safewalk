package io.github.wumisc.safewalk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 【v1.1 改造】SystemUI 进程系统级急救覆盖层。
 *
 * 与 v1.0 的"只画非隐私壳"不同：撞击确认为真且 DisplayProvider 取件成功后，
 * 直接在锁屏之上以红底 ≥18sp 渲染关键医疗字段（[DisplayFields]）。
 *
 * 单一暴露原则：Activity 可见即 hide()，removeView 并把字段内存副本置 null。
 * ACK 丢失时由 Hook 既有 tick 的 TTL 检查兜底 hide。
 */
object EmergencyOverlay {
    /** TYPE_STATUS_BAR_PANEL 是 @hide 常量（2008），用字面量避免依赖隐藏 API 编译 */
    private const val TYPE_STATUS_BAR_PANEL = 2008
    private const val TEXT_SIZE_SP = 18f

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var ctx: Context? = null
    @Volatile
    private var wm: WindowManager? = null
    @Volatile
    private var root: View? = null
    @Volatile
    private var fieldsCopy: DisplayFields? = null
    @Volatile
    private var shownAtElapsed = 0L
    @Volatile
    private var eventId = ""

    /** install 只准备 WindowManager，不 addView；取到字段才真正显示 */
    @JvmStatic
    fun install(appCtx: Context) {
        val c = appCtx.applicationContext ?: appCtx
        ctx = c
        wm = try {
            c.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        } catch (t: Throwable) {
            EmergencyDiag.event(EmergencyDiag.P_OVERLAY, "wm_unavailable: ${t.javaClass.simpleName}")
            null
        }
        EmergencyDiag.event(EmergencyDiag.P_OVERLAY, "installed wm=${wm != null}")
    }

    /** 渲染关键字段（切换回主线程，SystemUI 主线程永远不被 Binder 阻塞） */
    @JvmStatic
    fun render(fields: DisplayFields, id: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            renderOnMain(fields, id)
        } else {
            main.post { renderOnMain(fields, id) }
        }
    }

    /** token 失效/过期时的降级：不显示医疗字段，只保留非隐私提示 */
    @JvmStatic
    fun showFallback(id: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            renderOnMain(null, id)
        } else {
            main.post { renderOnMain(null, id) }
        }
    }

    @SuppressLint("InflateParams")
    private fun renderOnMain(fields: DisplayFields?, id: String) {
        hideOnMain()
        val c = ctx ?: return
        val manager = wm ?: return
        eventId = id
        shownAtElapsed = SystemClock.elapsedRealtime()
        fieldsCopy = fields

        // 第④路：以 system uid 关闭遮挡的锁屏对话框（App 侧发无效，必须 Hook 发）
        closeSystemDialogs()

        val view = buildView(c, fields)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            TYPE_STATUS_BAR_PANEL,
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP
        var added = false
        try {
            manager.addView(view, lp)
            added = true
        } catch (_: Throwable) {
            // 部分 ROM 不允许 system uid 加 STATUS_BAR_PANEL → 降级 APPLICATION_OVERLAY
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            try {
                manager.addView(view, lp)
                added = true
            } catch (_: Throwable) {
            }
        }
        if (added) {
            root = view
            EmergencyDiag.event(
                EmergencyDiag.P_OVERLAY,
                "shown=true fields=" + (if (fields == null) 0 else countFields(fields)) + " type=" + lp.type
            )
        } else {
            fieldsCopy = null
            shownAtElapsed = 0L
            EmergencyDiag.event(EmergencyDiag.P_OVERLAY, "addView_failed event=$id")
        }
    }

    /** Activity 可见 / 用户取消：removeView + 字段副本置 null */
    @JvmStatic
    fun hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            hideOnMain()
        } else {
            main.post { hideOnMain() }
        }
    }

    private fun hideOnMain() {
        val v = root
        if (v != null) {
            try { wm?.removeView(v) } catch (_: Throwable) {}
        }
        root = null
        fieldsCopy = null
        shownAtElapsed = 0L
        eventId = ""
    }

    /** 挂既有 tick 的 TTL 兜底：ACK 丢失时 20s 后自动退场 */
    @JvmStatic
    fun expireIfDue(nowElapsed: Long): Boolean {
        val shown = shownAtElapsed
        if (shown > 0L && nowElapsed - shown > SafeWalkPrefKey.DISPLAY_TTL_MS) {
            hide()
            EmergencyDiag.event(EmergencyDiag.P_OVERLAY, "ttl_hide event=$eventId")
            return true
        }
        return false
    }

    @JvmStatic
    fun isShowing(): Boolean = root != null

    /** 仅供测试/诊断读取当前字段副本数量，不返回字段值 */
    @JvmStatic
    fun fieldsCountForDiag(): Int = fieldsCopy?.let { countFields(it) } ?: 0

    @JvmStatic
    fun closeSystemDialogs() {
        try {
            ctx?.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Throwable) {
        }
    }

    /** 红底高对比，关键字段统一 ≥18sp；严重过敏前置 ★ */
    private fun buildView(c: Context, fields: DisplayFields?): View {
        val scroll = ScrollView(c)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#C8392E"))
            val p = (20 * c.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        box.addView(title(c, "🚨 医疗急救卡 · 无需解锁", 22f, true))
        if (fields == null || DisplayFields.isBlank(fields)) {
            box.addView(
                title(c, "疑似撞击 · 正在载入急救卡…", TEXT_SIZE_SP, false)
            )
        } else {
            box.addView(field(c, "姓名", fields.name))
            val blood = listOf(fields.bloodType, fields.rh)
                .filter { it.isNotBlank() && !it.startsWith("未知") }
                .joinToString(" ")
            if (blood.isNotBlank()) box.addView(field(c, "血型", blood))
            if (fields.allergy.isNotBlank()) {
                box.addView(field(c, if (fields.allergySevere) "★严重过敏" else "过敏", fields.allergy))
            }
            if (fields.conditions.isNotBlank()) box.addView(field(c, "基础疾病", fields.conditions))
            val contact = listOf(fields.primaryContactName, fields.primaryContactPhone)
                .filter { it.isNotBlank() }.joinToString(" ")
            if (contact.isNotBlank()) box.addView(field(c, "联系人", contact))
            if (fields.age > 0) box.addView(field(c, "年龄", fields.age.toString()))
        }
        box.addView(title(c, "完整急救卡即将显示 · 请在 60 秒内确认", TEXT_SIZE_SP, false))
        scroll.addView(
            box,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return scroll
    }

    private fun title(c: Context, text: String, sp: Float, bold: Boolean): TextView = TextView(c).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = sp
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        val m = (6 * c.resources.displayMetrics.density).toInt()
        setPadding(0, m, 0, m)
    }

    private fun field(c: Context, label: String, value: String): TextView = TextView(c).apply {
        this.text = "$label：$value"
        setTextColor(Color.WHITE)
        textSize = TEXT_SIZE_SP
        val m = (4 * c.resources.displayMetrics.density).toInt()
        setPadding(0, m, 0, m)
    }

    private fun countFields(f: DisplayFields): Int {
        var n = 0
        if (f.name.isNotBlank()) n++
        if (f.bloodType.isNotBlank()) n++
        if (f.allergy.isNotBlank()) n++
        if (f.conditions.isNotBlank()) n++
        if (f.primaryContactName.isNotBlank() || f.primaryContactPhone.isNotBlank()) n++
        if (f.age > 0) n++
        return n
    }
}
