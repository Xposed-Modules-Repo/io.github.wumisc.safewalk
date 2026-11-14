package io.github.wumisc.safewalk

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 首次安装「权限集中授权」页。纯 [android.app.Activity] + 代码构建布局，
 * 不写 XML、不用 AppCompat / ComponentActivity、不依赖任何第三方类。
 *
 * 【为什么集中授权，而不是「用到时再弹」】
 * SafeWalk 是 Xposed/LSPosed 模块：主逻辑（步态检测、夜间手电、车祸检测、重启系统界面）
 * 跑在 SystemUI 进程，而 App 侧的权限散落在十几个功能页里各自按需申请。旧做法是
 * 「用户打开某个功能才弹框」，后果有三：
 *   ① 用户从头到尾不知道这个模块总共要哪些权限、为什么要。看到相机 / 电话 / 短信
 *      授权框时最容易一律点拒绝，之后功能静默失效，排查时还误以为是 Hook 没生效；
 *   ② 国产 ROM（尤其小米 HyperOS）默认不放行自启动、后台弹窗、悬浮窗、电池白名单，
 *      这些开关根本不在 App 内，散落在系统设置的四五个角落，用户永远找不齐；
 *   ③ 「电话/短信仅用于急救卡撞击预警自动求助」这类用途不一次讲清，容易被误解。
 * 因此首次安装集中列一张清单：危险（运行时）权限一次性 requestPermissions，特殊权限
 * 逐项引导到系统设置页，状态随 onResume 实时刷新，用户点「跳过 / 完成」后写一次性标记。
 *
 * 【为什么特殊权限必须手动跳系统设置页】
 * Android 权限分三类：普通权限（安装即授予）、危险权限（requestPermissions 弹框），
 * 以及「特殊权限 / AppOps」。SYSTEM_ALERT_WINDOW（悬浮窗）、MANAGE_EXTERNAL_STORAGE
 * （所有文件访问）、忽略电池优化、无障碍服务都属于最后一类：系统**不提供**对应的
 * requestPermissions 接口，也不允许应用给自己授予（否则任何 App 都能悄悄盖悬浮窗骗点击）。
 * 系统唯一开放的是若干 Settings.ACTION_* 设置页 Intent，必须由用户在系统 UI 里亲手打开。
 * 这类 Intent 在不同 ROM 上是否存在、要不要带 data=package: 都不一致，所以每个跳转都
 * try/catch，失败一律回退到 ACTION_APPLICATION_DETAILS_SETTINGS（应用详情页），绝不崩。
 *
 * 【小米 HyperOS 的权限管控差异】
 * HyperOS 在 AOSP 之外额外加了「自启动」「后台弹出界面」「后台运行」「省电策略」等私有
 * 开关，入口是 com.miui.securitycenter 里的私有 Activity，AOSP 没有任何标准 Intent。
 * 而这些开关恰好决定行走检测与悬浮窗能不能在后台活下来：默认全关时锁屏几分钟进程就被
 * 杀，Hook 发的「走起来」广播没人收。更麻烦的是该 Activity 的完整类名在 MIUI 各版本间
 * 改过多次（.permission.autostart 与 .permcenter.autostart 两套都见过），所以这里把已知
 * 类名逐个尝试，全部失败再退到应用详情页让用户自己找。非小米设备上这些 Intent 必然
 * 失败，属预期行为，会用 `[PERM:SYSTEM]` 记一笔而不是崩掉。
 *
 * 【ROOT 与传感器为什么只说明、不申请】
 * ROOT 不是 Android 权限，没有授权框可弹；本页只检查 su 可执行文件是否存在，用来说明
 * 「重启系统界面」这条兜底链路是否可用，并刻意不执行 `su`（那会在已 root 设备上触发
 * Magisk / KernelSU 的授权弹窗，违背「仅说明状态、不弹授权框」）。加速度计 / 光线 /
 * 计步器属于普通传感器，安装即由系统授予，无需任何申请。
 *
 * 【日志约定】
 * 关键节点统一 `[PERM]`；缺失项 `[PERM:MISSING]`、已授予 `[PERM:GRANTED]`、
 * 系统限制 / 跳转失败 `[PERM:SYSTEM]`、ROOT 探测 `[PERM:ROOT]`。全部走项目内 DiagLog，
 * 同时进 logcat 与 diag.log，方便没有 adb 的用户回传现场。
 */
class PermissionOnboardingActivity : Activity() {

    companion object {
        /** 一次性标记存在私有 sp "safewalk"（功能配置在 "safewalk_xsp"，两者互不干扰）。 */
        private const val SP_ONEOFF = "safewalk"
        private const val KEY_ONBOARDING_DONE = "onboardingDone"

        private const val REQ_RUNTIME = 1001

        /** 后台定位单独一个请求码：它必须在「前台定位已授予」之后才申请 */
        private const val REQ_BG_LOCATION = 1002

        /**
         * 「所有文件访问」设置页 action。刻意用字符串常量而不是 Settings 里的常量：
         * 该 action 在部分 ROM 上并不存在，用字符串可以让 startActivity 失败时干净地
         * 落进 try/catch 回退，而不是在编译期就绑死某个 API 级别。
         */
        private const val ACTION_MANAGE_ALL_FILES =
            "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"

        /**
         * 适配表不可用时的通用步骤名。
         *
         * 刻意用「自启动 / 后台活动」这种不带系统名的写法：不知道是哪一家，就一个系统名都不提，
         * 用户至少不会因为看到「HyperOS」而在荣耀手机上困惑。
         */
        private val FALLBACK_STEPS = listOf(
            "悬浮窗",
            "电池优化白名单",
            "所有文件访问",
            "自启动 / 后台活动",
            "后台定位（到达提醒）",
            "无障碍"
        )

        /** 是否还需要显示引导页：没写过 onboardingDone 就返回 true。 */
        @JvmStatic
        fun shouldShow(ctx: Context): Boolean = try {
            !ctx.getSharedPreferences(SP_ONEOFF, Context.MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDING_DONE, false)
        } catch (t: Throwable) {
            // sp 损坏 / 存储不可用时按「未完成」处理：宁可多展示一次引导，也不要漏授权。
            DiagLog.log("[PERM] 读取 onboardingDone 失败，按未完成处理: ${t.message}")
            true
        }

        /** 用 commit（同步落盘）而不是 apply，避免刚点完成就被杀进程导致下次又弹。 */
        @JvmStatic
        fun markDone(ctx: Context) {
            try {
                ctx.getSharedPreferences(SP_ONEOFF, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ONBOARDING_DONE, true).commit()
                DiagLog.log("[PERM] onboardingDone=true 已写入（commit）")
            } catch (t: Throwable) {
                DiagLog.log("[PERM:SYSTEM] 写入 onboardingDone 失败: ${t.message}")
            }
        }

        /** 从任意 Context 拉起本页；非 Activity 上下文补 NEW_TASK，正常调用无需关心。 */
        @JvmStatic
        fun start(ctx: Context) {
            val i = Intent(ctx, PermissionOnboardingActivity::class.java)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    // ===== 配色 =====
    // 【5.0.0】不再由本页判断夜间模式：全部读 @color token，
    // values-night/colors.xml 里有同名覆盖项，系统切深色时自动生效。
    // 原来那套 if(night) 覆写十几个十六进制值的写法，已经和全局色板
    // 对不上（底色 #F7F8FA 与 @color/bg 的 #F2F2F7 差一档，页面看起来「灰得不一样」）。
    private var colorTitle = 0
    private var colorText = 0
    private var colorDesc = 0
    private var colorOk = 0
    private var colorBad = 0
    private var colorNeutral = 0
    private var colorDivider = 0

    /**
     * 访问适配表的统一安全壳。
     *
     * 表是 Kotlin `object`，首次访问会执行 `init` 块里的 `require` 校验；任何一条 spec 写错
     * 都会抛 `ExceptionInInitializerError`（属于 `Error`）。用 `Throwable` 兜住，
     * 保证引导页在最坏情况下仍是一张能用的通用清单，而不是崩溃页。
     */
    private fun <T> safeRom(fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        DiagLog.log(
            "[ROM] 适配表不可用，降级为通用文案：${t.javaClass.simpleName} ${t.message}"
        )
        fallback
    }

    /** 清单里一行的静态信息 + 一个「当前状态」取值器（onResume 时反复调用）。 */
    private class PermItem(
        val name: String,
        val desc: String,
        val type: String,
        val state: () -> PermState
    )

    /** 状态文本 / 文字颜色 / 是否算「待处理」（用于汇总一条 MISSING 日志）。 */
    private data class PermState(val text: String, val color: Int, val missing: Boolean = false)

    /** 每项对应的 TextView，刷新时只更新文本，不重建视图。 */
    private val rows = ArrayList<Pair<PermItem, TextView>>()

    /** 「逐项开启」的游标：每点一次只跳一个设置页，用户从系统设置返回后再点下一项。 */
    private var specialSteps: List<String> = emptyList()
    private var specialIndex = 0

    /**
     * 本机 UI 生态（P1）。只在 onCreate 判一次：`Build.*` 在进程生命周期内不会变，
     * 每次 onResume 重算既浪费也容易在日志里刷出重复行。
     */
    private var romId: RomId = RomId.AOSP

    /** ROOT 探测结果在 onCreate 里算一次即可（su 是否存在不会在页面存活期间变化）。 */
    private var rootAvailable = false

    /** 【5.3.0】首次安装的「重启系统界面」卡片是否还在等用户处理（点「完成」时据此二次确认） */
    private var restartPending = false

    // ============================ 生命周期 ============================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 本页可能是冷启动后第一个入口，日志出口未必初始化过，先补一下（与 AutoStartActivity 一致）。
        try {
            DiagLog.init(applicationContext)
        } catch (_: Throwable) {
        }
        DiagLog.log("[PERM] 首次安装权限引导页 onCreate")
        // 5.0.0 起不再手工切深浅配色：布局走 @color token，values-night 自动生效。

        // 【P1 多 ROM】先认本机属于哪一家，再决定「自启动」这一类文案怎么写、往哪儿跳。
        // 判定只读 Build 的三个字段，不读 prop、不依赖 Hook，冷启动第一页也能用。
        //
        // 【为什么每一步都要包 try/catch】RomRegistry 是 Kotlin object，表里任何一条 spec
        // 写错都会在**首次访问时**抛 ExceptionInInitializerError（Error，不是 Exception）。
        // 引导页是用户装完 App 打开的第一屏，绝不能因为一张「指路表」白屏 —— 表坏了就退回
        // 通用文案与通用入口，功能一点也不少，只是少了几句本地化提示。
        romId = safeRom(RomId.AOSP) { RomSettings.effective(this) }
        specialSteps = safeRom(FALLBACK_STEPS) { OnboardingSteps.labelsFor(romId) }
        try {
            DiagLog.log("[ROM] " + RomSettings.explain(romId, RomSettings.overrideOf(this)))
            DiagLog.log(
                "[ROM] " + RomDetector.explain(Build.MANUFACTURER, Build.BRAND, Build.PRODUCT)
            )
            DiagLog.log("[ROM] " + RomRegistry.describe(romId))
            DiagLog.log(
                "[ROM] 自启动去处：" + RomRegistry.routeHint(romId, TargetKind.AUTOSTART) +
                    "｜电池：" + RomRegistry.routeHint(romId, TargetKind.BATTERY)
            )
        } catch (t: Throwable) {
            DiagLog.log("[ROM] 适配表不可用，本次只打基础环境：${t.javaClass.simpleName}")
        }

        rootAvailable = detectRoot()
        DiagLog.log(
            if (rootAvailable) {
                "[PERM:ROOT] 检测到 su，『重启系统界面』兜底可用（仅说明状态，不弹授权框）"
            } else {
                "[PERM:ROOT] 未检测到 su，『重启系统界面』兜底不可用（仅说明状态，不弹授权框）"
            }
        )

        // 【5.0.0 版式统一】布局交给 activity_permission.xml，复用 PageScroll /
        // SectionLabel / CardStyle / PrimaryButton 这套全局 style。
        // 这一页从此与其他二级页同款：同样 40dp 顶部内边距（不再顶到状态栏）、
        // 同样卡片与分隔线、同样按钮。代码只负责动态插条目与刷状态。
        setContentView(R.layout.activity_permission)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // 配色一律读 @color token：values-night 已一一对应，
        // 不再需要本页自己判断夜间模式、逐个覆写十六进制值。
        colorTitle = getColor(R.color.text_primary)
        colorText = getColor(R.color.text_body)
        colorDesc = getColor(R.color.text_hint)
        colorOk = getColor(R.color.accent_green)
        colorBad = getColor(R.color.seal_red)
        colorNeutral = getColor(R.color.text_hint)
        colorDivider = getColor(R.color.divider)

        // ---- 说明（一句话讲清用途与操作）----
        findViewById<TextView>(R.id.tv_perm_summary).text =
            "权限仅用于本机功能，不上传数据。点下方按钮一次性申请；" +
                "标注「需手动开启」的会逐项引导到系统设置，返回后状态自动刷新。"

        // ---- 本机 UI 识别结果（一行，省掉「为什么找不到自启动」的半页问答）----
        findViewById<TextView>(R.id.tv_perm_rom).text = safeRom(
            "本机 UI：未识别 · 按标准 Android 设置引导"
        ) {
            if (romId.hasVendorPages) {
                "本机 UI：${romId.displayName} · 自启动在「" +
                    RomRegistry.routeHint(romId, TargetKind.AUTOSTART) + "」"
            } else {
                "本机 UI：${romId.displayName} · 无厂商私有开关，全部走标准设置"
            }
        }

        // ---- 权限清单（精简版）----
        // 【BUG 修复】旧版 12 项 × 4 行 + 12 条分隔线，页面长得要一直滚，
        // 用户反馈「授权页面太长了简短一些」。现在按「自动申请 / 手动开启 /
        // 仅说明」合并为 8 项，每项两行，行间一条分隔线。
        val container = findViewById<LinearLayout>(R.id.perm_rows)
        val items = buildItems()
        items.forEachIndexed { index, item ->
            val tv = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(colorText)
                setLineSpacing(dp(2).toFloat(), 1.1f)
                setPadding(dp(16), dp(13), dp(16), dp(13))
            }
            container.addView(
                tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            rows.add(item to tv)
            if (index != items.lastIndex) {
                container.addView(
                    View(this).apply { setBackgroundColor(colorDivider) },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    ).apply { leftMargin = dp(16) }
                )
            }
        }

        // ---- 底部操作区 ----
        findViewById<Button>(R.id.btn_apply_runtime)
            .setOnClickListener { requestAllRuntime() }

        val btnSpecial = findViewById<Button>(R.id.btn_special)
        btnSpecial.text = specialButtonText()
        btnSpecial.setOnClickListener { onSpecialClicked(btnSpecial) }

        val btnDone = findViewById<Button>(R.id.btn_done)
        btnDone.setOnClickListener {
            // 【5.3.0】首次安装还没重启系统界面就点「完成」：把后果说清楚再放人走。
            // 不拦着（用户可能想先去 LSPosed 勾作用域），但必须让他知道
            // 接下来会看到「开关打开了却什么都没发生」。
            if (restartPending) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("还没重启系统界面")
                    .setMessage(RestartReminderPolicy.skipWarning())
                    .setPositiveButton("立即重启") { _, _ -> doRestartSystemUi() }
                    .setNegativeButton("稍后再说") { _, _ -> finishOnboarding() }
                    .show()
                return@setOnClickListener
            }
            finishOnboarding()
        }

        // 【5.3.0】首次安装：「重启系统界面」卡片。放在最后初始化，
        // 因为它依赖 onCreate 早前算出的 rootAvailable。
        setupRestartCard()

        // 文本由 onResume() 的 refreshStates() 统一填充（onCreate 之后必然紧跟 onResume）。
    }

    /**
     * 首次安装的「重启系统界面」卡片（5.3.0）。
     *
     * 为什么放在权限页而不是弹一个对话框：这一页是用户装完 App 打开的第一屏，
     * 也是他「一路点下去」的地方。弹窗会被手快地关掉，而卡片留在页面上，
     * 即使这次跳过了，下次进「权限与无障碍」还能看到（只要还没点过重启）。
     */
    private fun setupRestartCard() {
        val block = findViewById<View>(R.id.block_restart_sui)
        val ack = try {
            getSharedPreferences(RestartReminderPolicy.SP_ONEOFF, Context.MODE_PRIVATE)
                .getBoolean(RestartReminderPolicy.KEY_ACK, false)
        } catch (_: Throwable) {
            false
        }
        // shouldShow 为 true = 还没走过引导页 = 刚装上。老用户从「关于」进来时
        // 这里是 false，卡片不会出现 —— 每次打开设置都提醒「请重启」只会变成狼来了。
        restartPending = RestartReminderPolicy.shouldPrompt(shouldShow(this), ack)
        if (!restartPending) {
            block.visibility = View.GONE
            return
        }
        block.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_restart_title).text = RestartReminderPolicy.title()
        findViewById<TextView>(R.id.tv_restart_body).text =
            RestartReminderPolicy.body(rootAvailable)
        findViewById<TextView>(R.id.tv_restart_state).text =
            RestartReminderPolicy.pendingText()
        findViewById<Button>(R.id.btn_restart_sui).apply {
            text = RestartReminderPolicy.actionText()
            setOnClickListener { doRestartSystemUi() }
        }
        DiagLog.log(
            "[PERM] 首次安装：显示「重启系统界面」提醒（rootAvailable=$rootAvailable）"
        )
    }

    /**
     * 发起重启，并把结果写回卡片状态行。
     *
     * 走 [SystemUiRestarter] 而不是自己拼 su 命令：它已经处理了
     * 「Hook 自尽 → su 兜底」两条通道、广播鉴权、命令超时和 pid 精确 kill。
     * 这里只负责把结果翻译成用户能看懂的一句话。
     */
    private fun doRestartSystemUi() {
        val btn = findViewById<Button>(R.id.btn_restart_sui)
        val state = findViewById<TextView>(R.id.tv_restart_state)
        btn.isEnabled = false
        state.setTextColor(colorNeutral)
        state.text = "正在发送重启指令…"
        DiagLog.log("[PERM] 用户点击「重启系统界面」（首次安装引导）")
        SystemUiRestarter.restart(this) { ok: Boolean, viaRoot: Boolean ->
            if (isFinishing || isDestroyed) return@restart
            btn.isEnabled = true
            if (ok) {
                restartPending = false
                markRestartAcknowledged()
                state.setTextColor(colorOk)
                state.text = RestartReminderPolicy.successText(viaRoot)
            } else {
                state.setTextColor(colorBad)
                state.text = RestartReminderPolicy.failureText()
            }
        }
    }

    /** 重启成功后落一个一次性标记，之后不再显示这张卡片 */
    private fun markRestartAcknowledged() {
        try {
            getSharedPreferences(RestartReminderPolicy.SP_ONEOFF, Context.MODE_PRIVATE)
                .edit().putBoolean(RestartReminderPolicy.KEY_ACK, true).commit()
            DiagLog.log("[PERM] 已记录「重启系统界面」完成标记")
        } catch (t: Throwable) {
            DiagLog.log("[PERM:SYSTEM] 写入重启标记失败：${t.message}")
        }
    }

    /** 「跳过 / 完成」的统一出口：写标记 + 结束页面 */
    private fun finishOnboarding() {
        DiagLog.log("[PERM] 用户点击「跳过 / 完成」，结束引导")
        markDone(this)
        finish()
    }

    override fun onResume() {
        super.onResume()
        // 用户每从一个系统设置页返回，这里都会重跑：刚开的开关立刻反映到清单上。
        DiagLog.log("[PERM] onResume：刷新权限状态")
        refreshStates()
    }

    // ============================ 权限清单 ============================

    /**
     * 【BUG 修复 · 精简】把旧版 12 项按「自动申请 / 手动开启 / 仅说明」合并为 7 项。
     * 合并只改变**清单呈现**，底层能力一字未减：
     *   · requestAllRuntime() 仍一次性申请全部 7 个运行时权限；
     *   · specialSteps 仍逐项引导 5 类特殊权限设置页；
     *   · ROOT / 传感器仍只说明状态、不弹授权框。
     */
    private fun buildItems(): List<PermItem> = listOf(
        PermItem(
            "① 运行时权限（7 项）",
            "通知、身体活动、相机、电话/短信/定位（急救卡撞击预警自动求助）",
            "点下方按钮一键申请",
            {
                // 与 requestAllRuntime() 用同一套按 SDK 过滤的组装逻辑，
                // 避免「已全授予」却因请求了本机不存在的权限而误报缺失。
                val perms = ArrayList<String>(7)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
                }
                perms.add(Manifest.permission.CAMERA)
                perms.add(Manifest.permission.CALL_PHONE)
                perms.add(Manifest.permission.SEND_SMS)
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
                perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                multiRuntimeState(perms.toTypedArray())
            }
        ),
        PermItem(
            "② 悬浮窗",
            "角落小窗 / 全屏路况叠加显示（SYSTEM_ALERT_WINDOW）",
            "需手动开启",
            { overlayState() }
        ),
        PermItem(
            "③ 电池优化白名单",
            "避免系统在后台杀掉行走检测与悬浮窗",
            "需手动开启",
            { batteryState() }
        ),
        PermItem(
            "④ 自启动 / 后台活动",
            safeRom("开机或被回收后自动恢复行走模式") { OnboardingSteps.autostartDesc(romId) },
            if (romId.hasVendorPages) "需手动开启" else "本系统无需设置",
            { manualState() }
        ),
        PermItem(
            "⑤ 所有文件访问",
            "写入 /storage/emulated/0/Android/Travel safely 诊断日志",
            "需手动开启",
            { allFilesState() }
        ),
        PermItem(
            "⑥ 后台定位（到达提醒）",
            "屏幕黑着、手机装兜里也能识别「到了车站/景区/商圈」并提醒",
            "需手动开启 · 位置选「始终允许」",
            { backgroundLocationState() }
        ),
        PermItem(
            "⑦ 无障碍（可选）",
            "在系统无障碍里提供本应用入口；开启后可自动避让相机类应用",
            "可选 · 手动开启",
            { a11yState() }
        ),
        PermItem(
            "⑧ ROOT 与传感器",
            "ROOT 仅用于「重启系统界面」兜底；加速度计 / 光线 / 计步器由系统直接提供",
            "仅说明 · 不申请、不弹授权框",
            { rootState() }
        )
    )

    // ============================ 状态取值（一律用原生 API，不引 androidx） ============================

    /** 单项运行时权限：用 Context.checkSelfPermission，按 SDK 过滤不存在的权限。 */
    private fun runtimeState(perm: String, since: Int): PermState {
        if (Build.VERSION.SDK_INT < since) {
            return PermState("系统版本无需申请（自动视为已授予）", colorOk)
        }
        val ok = try {
            checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        return if (ok) {
            PermState("已授予", colorOk)
        } else {
            PermState("未授予（点『一键申请』）", colorBad, true)
        }
    }

    /** 一组运行时权限（电话/短信/定位）：统计已授予数量。 */
    private fun multiRuntimeState(perms: Array<String>): PermState {
        var ok = 0
        for (p in perms) {
            if (try {
                    checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
                } catch (_: Throwable) {
                    false
                }
            ) ok++
        }
        return if (ok == perms.size) {
            PermState("已全部授予（${ok}/${perms.size}）", colorOk)
        } else {
            PermState("未全部授予（${ok}/${perms.size}，点『一键申请』）", colorBad, true)
        }
    }

    /** 悬浮窗是 AppOps 特殊权限，唯一可靠的查询入口是 Settings.canDrawOverlays。 */
    private fun overlayState(): PermState {
        val ok = try {
            Settings.canDrawOverlays(this)
        } catch (_: Throwable) {
            false
        }
        return if (ok) {
            PermState("已开启", colorOk)
        } else {
            PermState("未开启（需手动开启）", colorBad, true)
        }
    }

    /** 电池优化白名单：PowerManager.isIgnoringBatteryOptimizations。 */
    private fun batteryState(): PermState {
        val pm = try {
            getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (_: Throwable) {
            null
        }
        val ignored = try {
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        } catch (_: Throwable) {
            false
        }
        return if (ignored) {
            PermState("已加入白名单", colorOk)
        } else {
            PermState("未加入（需手动开启）", colorBad, true)
        }
    }

    /** 所有文件访问（API 30+；minSdk 33 已覆盖，无需 SDK 分支）。 */
    private fun allFilesState(): PermState {
        val ok = try {
            Environment.isExternalStorageManager()
        } catch (_: Throwable) {
            false
        }
        return if (ok) {
            PermState("已授予", colorOk)
        } else {
            PermState("未授予（需手动开启）", colorBad, true)
        }
    }

    /**
     * 自启动 / 后台活动：AOSP 与 HyperOS 都没有对外查询接口，只能靠用户去系统设置里
     * 亲手确认，所以状态固定提示「需手动确认」，并计入待处理清单。
     */
    private fun manualState(): PermState =
        if (!romId.hasVendorPages) {
            // AOSP 没有这个开关，标成「无需设置」且不计入待处理，否则用户会一直看到红字。
            PermState("本系统无此开关（无需设置）", colorOk)
        } else {
            PermState("需手动确认（系统不提供查询接口，点『逐项开启』跳转）", colorNeutral, true)
        }

    /**
     * 后台定位：只有「始终允许」才能在锁屏 / 后台继续拿到位置。
     *
     * 旧版这里根本没有这一项，而 [PlaceArrivalMonitor] 只在应用前台有效 ——
     * 用户真正「到达某地」时手机通常黑屏装兜里，于是到达提醒几乎从不触发。
     */
    private fun backgroundLocationState(): PermState {
        val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }
        if (bg) return PermState("已开启（后台也能判类）", colorOk)
        val fine = try {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        return PermState(
            if (fine) "未开启（仅为「使用期间」，后台收不到位置）"
            else "未开启（前台定位也还没授予）",
            colorNeutral,
            true
        )
    }

    /**
     * 无障碍：查**本应用的服务**有没有被勾选。
     *
     * 旧版读的是 `ACCESSIBILITY_ENABLED` 总开关 —— 只要系统里开着任意一个无障碍服务
     * （输入法、手势、屏幕朗读）就显示「已开启」，而本应用从来没声明过服务，
     * 系统列表里根本没有条目。用户按提示去找，自然找不到「打开应用」的入口。
     */
    private fun a11yState(): PermState =
        if (SafeWalkA11yService.isEnabled(this)) {
            PermState("已开启（可从系统无障碍打开本应用；相机类应用自动避让）", colorOk)
        } else {
            PermState("未开启（可选，点『逐项开启』直达本服务页）", colorNeutral)
        }

    private fun rootState(): PermState =
        if (rootAvailable) {
            PermState("已检测到 su（可用于『重启系统界面』兜底）", colorOk)
        } else {
            PermState("未检测到 su（兜底不可用，仅说明状态）", colorNeutral)
        }

    // ============================ 渲染 ============================

    /**
     * 【BUG 修复 · 精简】每项压成两行：
     *   第一行「名称 · 状态」（名称加粗，状态整段着色：绿=就绪 / 红=缺失 / 灰=中性）；
     *   第二行一句「用途 + 类型标签」。
     * 旧版每项 4 行（用途 / 类型 / 状态各占一行），12 项下来页面长得要一直滚。
     */
    private fun render(item: PermItem, st: PermState): CharSequence {
        val sb = SpannableStringBuilder()

        val nameStart = sb.length
        sb.append(item.name)
        sb.setSpan(
            StyleSpan(Typeface.BOLD),
            nameStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        sb.append("  ·  ")
        val stateStart = sb.length
        sb.append(st.text)
        sb.setSpan(
            ForegroundColorSpan(st.color),
            stateStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        sb.append("\n").append(item.desc)
        if (item.type.isNotEmpty()) sb.append(" · ").append(item.type)
        return sb
    }

    private fun refreshStates() {
        val pending = ArrayList<String>()
        for ((item, tv) in rows) {
            val st = try {
                item.state()
            } catch (t: Throwable) {
                PermState("状态读取失败：${t.message}", colorBad, true)
            }
            tv.text = render(item, st)
            if (st.missing) pending.add(item.name)
        }
        if (pending.isEmpty()) {
            DiagLog.log("[PERM:GRANTED] 权限状态刷新：清单内无待处理项")
        } else {
            DiagLog.log(
                "[PERM:MISSING] 权限状态刷新，仍待处理 ${pending.size} 项：" +
                    pending.joinToString("、")
            )
        }
    }

    // ============================ 一键申请运行时权限 ============================

    private fun requestAllRuntime() {
        val perms = ArrayList<String>(7)
        // 按 SDK 过滤：minSdk 33 下这两项恒为真，但显式判断便于日后下调 minSdk
        // 或适配改过权限模型的定制 ROM，避免请求一个本机不存在的权限。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        perms.add(Manifest.permission.CAMERA)
        perms.add(Manifest.permission.CALL_PHONE)
        perms.add(Manifest.permission.SEND_SMS)
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (perms.isEmpty()) {
            DiagLog.log("[PERM] 本机没有需要申请的运行时权限")
            return
        }
        DiagLog.log("[PERM] 一键申请 ${perms.size} 项运行时权限：${perms.joinToString(", ")}")
        try {
            requestPermissions(perms.toTypedArray(), REQ_RUNTIME)
        } catch (t: Throwable) {
            DiagLog.log("[PERM:SYSTEM] requestPermissions 失败：${t.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BG_LOCATION) {
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            DiagLog.log(
                if (ok) "[PERM:GRANTED] 后台定位已授予 → 到达提醒可在后台判类"
                else "[PERM:MISSING] 后台定位未授予 → 到达提醒只在打开 App 时生效"
            )
            refreshStates()
            return
        }
        if (requestCode != REQ_RUNTIME) return
        DiagLog.log("[PERM] 收到运行时权限回调，共 ${permissions.size} 项")

        for (i in permissions.indices) {
            val perm = permissions[i]
            // 用户直接取消时 grantResults 可能是空数组或短于 permissions，必须防越界。
            val granted = i < grantResults.size &&
                grantResults[i] == PackageManager.PERMISSION_GRANTED

            val verdict = if (granted) {
                "[PERM:GRANTED] 已授予：$perm"
            } else {
                // shouldShowRequestPermissionRationale=false 有两种含义：勾选了「不再询问」
                // （永久拒绝）或系统策略直接拒发。本回调里权限刚被请求过，按永久拒绝处理，
                // 提示用户去应用详情页手动开，而不是反复弹一个再也不会出现的框。
                val permanent = try {
                    !shouldShowRequestPermissionRationale(perm)
                } catch (_: Throwable) {
                    false
                }
                if (permanent) {
                    "[PERM:MISSING] 永久拒绝（不再询问 / 系统策略）：$perm，需到应用详情页手动开启"
                } else {
                    "[PERM:MISSING] 被拒绝（可再次申请）：$perm"
                }
            }
            DiagLog.log(verdict)
        }
        refreshStates()
    }

    // ============================ 逐项引导特殊权限 ============================

    /**
     * 按钮文案。步骤名里的括号补充（系统名 / 「可跳过」提示）只用于清单，不进按钮：
     * 按钮是单行，塞进去会被系统按 ellipsize 截成「…（本系统无此开」，反而看不懂。
     */
    private fun specialButtonText(): String =
        if (specialIndex >= specialSteps.size) {
            "开启特殊权限（已完成，点此重来）"
        } else {
            val short = specialSteps[specialIndex].substringBefore("（")
            "开启特殊权限 ${specialIndex + 1}/${specialSteps.size} · $short"
        }

    /**
     * 一次点击只跳一个设置页。不能把五个 startActivity 连着发出去：它们会叠成一摞，
     * 用户只会看到最后一个，前面的全被压在下面，等于没引导。用户从设置页返回
     * （触发 onResume 刷新状态）后再点，游标自然推进到下一项。
     */
    private fun onSpecialClicked(btn: Button) {
        if (specialIndex >= specialSteps.size) specialIndex = 0
        val name = specialSteps[specialIndex]
        DiagLog.log("[PERM] 逐项引导特殊权限 ${specialIndex + 1}/${specialSteps.size}：$name")
        try {
            Toast.makeText(this, "即将打开系统设置：$name", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
        launchSpecial(specialIndex)
        specialIndex++
        btn.text = specialButtonText()
    }

    private fun launchSpecial(index: Int) {
        when (index) {
            0 -> openOverlay()
            1 -> openBattery()
            2 -> openAllFiles()
            3 -> openAutoStart()
            4 -> openBackgroundLocation()
            5 -> openAccessibility()
        }
    }

    /** 轻量提示：本页所有 Toast 都走这里，避免各分支重复 try/catch（部分 ROM 后台禁止弹 Toast）。 */
    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    /** 统一的跳转壳：任何 ActivityNotFoundException / SecurityException 都记 SYSTEM 日志，不崩。 */
    private fun startSafely(label: String, factory: () -> Intent): Boolean = try {
        startActivity(factory())
        DiagLog.log("[PERM] 已拉起系统设置页：$label")
        true
    } catch (t: Throwable) {
        DiagLog.log("[PERM:SYSTEM] 无法拉起「$label」设置页：${t.javaClass.simpleName} ${t.message}")
        false
    }

    /** ① 悬浮窗：SYSTEM_ALERT_WINDOW 只能在这个页里由用户亲手开。 */
    private fun openOverlay() {
        val ok = startSafely("悬浮窗") {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        }
        if (!ok) fallbackAppDetails("悬浮窗")
    }

    /**
     * ② 电池优化：先试「直接请求加入白名单」的弹窗式 Intent（多数 ROM 支持），
     * 不支持再退到「电池优化列表」页，最后退到应用详情页。
     */
    private fun openBattery() {
        val direct = startSafely("电池优化白名单 · 直接请求") {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
        }
        if (direct) return
        val list = startSafely("电池优化白名单 · 列表") {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        if (!list) fallbackAppDetails("电池优化白名单")
    }

    /**
     * ③ 所有文件访问：用字符串 action + data=package:。
     * 部分 ROM 根本没这个设置页，失败就回退，不视为崩溃。
     */
    private fun openAllFiles() {
        val ok = startSafely("所有文件访问") {
            Intent(ACTION_MANAGE_ALL_FILES).setData(Uri.parse("package:$packageName"))
        }
        if (!ok) fallbackAppDetails("所有文件访问")
    }

    /**
     * 自启动 / 后台活动：没有公开 API 能查、能改，只能把用户送到系统设置里的某一页。
     *
     * 【P1 多 ROM】路径不再写死小米两条 component，而是查 [RomRegistry] 的候选表，
     * 由 [RomPageLauncher] 逐条 `resolveActivity` 校验 → 打开 → 失败换下一条 →
     * 全失败退应用详情页。表写错的最坏结果是「多点两下」，不会变成「点了没反应」。
     */
    private fun openAutoStart() {
        if (!romId.hasVendorPages) {
            toast("本系统没有自启动开关，按标准策略管理后台即可")
            DiagLog.log("[ROM] ${romId.displayName} 无自启动页，跳过（不计入待处理）")
            return
        }
        val used = RomPageLauncher.openOrDetails(this, TargetKind.AUTOSTART, romId)
        if (used != null) {
            toast("已打开：${used.label}")
        } else {
            toast("无法打开系统设置，请到应用详情页手动允许自启动")
        }
    }

    /**
     * ⑤ 后台定位：到达提醒要在「屏幕黑着、手机装兜里」时采样。
     *
     * 只授「使用应用期间」是无效的 —— Android 10 起应用一进后台，定位回调就停，
     * 而「到达某个地方」恰恰发生在用户不看手机的时候。所以这里补一次后台定位申请：
     * Android 11+ 允许单独请求该权限，系统会弹「允许所有时间」的说明页并跳设置；
     * 请求失败再退到应用详情页，让用户自己选「位置 → 始终允许」。
     */
    private fun openBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast("系统版本无需后台定位权限")
            return
        }
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            toast("请先授予「定位」前台权限，再来开后台定位")
            fallbackAppDetails("后台定位（先授前台定位）")
            return
        }
        try {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQ_BG_LOCATION
            )
            DiagLog.log("[PERM] 已申请后台定位（ACCESS_BACKGROUND_LOCATION）")
            return
        } catch (t: Throwable) {
            DiagLog.log("[PERM:SYSTEM] 后台定位申请失败：${t.message}，退回应用详情页")
        }
        fallbackAppDetails("后台定位")
    }

    /** ⑥ 无障碍：直达**本服务**的详情页，而不是让用户在系统总列表里找一个看不见的服务。 */
    private fun openAccessibility() {
        val ok = try {
            startActivity(SafeWalkA11yService.detailsIntent(this))
            DiagLog.log("[PERM] 已拉起本应用无障碍服务的系统详情页")
            true
        } catch (t: Throwable) {
            DiagLog.log("[PERM:SYSTEM] 无障碍详情页不可用：${t.javaClass.simpleName}，退回总列表")
            startSafely("无障碍 · 系统列表") {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
        }
        if (!ok) fallbackAppDetails("无障碍")
    }

    /** 所有特殊权限入口都失败时的最后兜底：应用详情页。 */
    private fun fallbackAppDetails(label: String) {
        val ok = startSafely("$label · 应用详情页兜底") {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        }
        if (!ok) {
            DiagLog.log("[PERM:SYSTEM] 「$label」所有入口均不可用（本机无对应设置页）")
        }
    }

    // ============================ ROOT 探测 / 工具 ============================

    /**
     * 只读常见路径判断 su 是否存在。
     *
     * 刻意**不**执行 `Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))`：在已 root
     * 设备上那会触发 Magisk / KernelSU 的授权弹窗，违背本页「仅说明状态、不弹授权框」。
     * 除固定路径外再扫一遍 PATH，覆盖把 su 放在自定义目录的 root 方案。
     */
    private fun detectRoot(): Boolean {
        val candidates = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/bin/failsafe/su", "/system/sd/xbin/su",
            "/data/local/bin/su", "/data/local/xbin/su", "/data/local/su",
            "/su/bin/su"
        )
        for (p in candidates) {
            if (try {
                    File(p).exists()
                } catch (_: Throwable) {
                    false
                }
            ) return true
        }
        val pathEnv = try {
            System.getenv("PATH")
        } catch (_: Throwable) {
            null
        }
        if (!pathEnv.isNullOrEmpty()) {
            for (dir in pathEnv.split(':')) {
                if (dir.isEmpty()) continue
                if (try {
                        File(dir, "su").exists()
                    } catch (_: Throwable) {
                        false
                    }
                ) return true
            }
        }
        return false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun itemLp(topDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }
}
