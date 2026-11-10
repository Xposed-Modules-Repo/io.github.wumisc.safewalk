package io.github.wumisc.safewalk

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import java.lang.reflect.Method
import java.util.Locale

/**
 * HyperOS 3 / HyperOS 4 双版本适配工具类（纯 Kotlin object，全部对外方法带 [JvmStatic]）。
 *
 * ## 设计取舍：方案 A（自动识别优先）+ 方案 B（手动兜底）
 *
 * 本模块是 Xposed 模块：Hook 跑在 SystemUI 进程，App 跑在自己进程，二者通过
 * `SharedPreferences("safewalk_xsp")` 导出的 JSON 同步配置。两个进程都可能调用本类，
 * 所以这里**不持有任何进程状态以外的引用**，也不依赖项目里的 `SafeWalkPrefKey` 常量表
 * （`modeKey` 由调用方传入，App 与 Hook 各用各的 key 即可）。
 *
 * - **方案 A（默认，[MODE_AUTO]）**：开机后自动读 `Build.*` 与一批 MIUI/HyperOS 专属
 *   system property，正则提取大版本号，判断 HOS3 / HOS4 / 非小米系。对绝大多数用户零配置，
 *   是首选路径。
 * - **方案 B（兜底）**：ROM 裁剪了 prop、hidden API 反射被拦、或自动识别结论明显不对时，
 *   允许用户在实验区强制指定 [MODE_HOS3] / [MODE_HOS4]。[resolve] / [current] 是统一入口，
 *   手动值直接短路自动识别，避免"识别错了还必须改代码"。
 * - 取 [Os.OTHER] 一律走**最保守**策略：宁可少用激进适配，也不要把普通 Android ROM
 *   误判成 HOS4 后套用更严的降级逻辑。
 *
 * ## 为什么用反射读 SystemProperties
 *
 * 判断 HyperOS 大版本最可靠的证据是 `ro.mi.os.version.name` 之类的 prop（形如
 * `HyperOS 3.0` / `OS4.0.1.0`），但它们只能通过 `android.os.SystemProperties.get()`
 * 读取。该类是 **@hide API**，SDK 里没有编译期符号，也拿不到公开常量，因此只能
 * `Class.forName(...) + getMethod("get", String) + invoke(...)`。
 *
 * 必须 try/catch 的原因有两层：
 * 1. **系统 API 变更**：Android 9（P）起有 hidden API 限制，普通 App 进程反射 @hide
 *    成员可能抛 `NoSuchMethodError` / `IllegalAccessException`；后续版本也可能改签名、
 *    改类名。Xposed/SystemUI 进程常常绕过该限制，但 App 进程不一定，两个进程不能假设
 *    行为一致。
 * 2. **ROM 限制**：不同 HyperOS 版本写入的 prop 集合不同，有的被 SELinux 策略挡掉，
 *    有的精简版安全中心干脆不写。`get()` 返回空串或抛异常都属正常现象。
 *
 * 所以 prop 拿不到时**绝不崩**，退回 `Build.VERSION.SDK_INT` 近似兜底，并在注释与日志里
 * 反复注明"兜底仅为近似，prop 优先"。
 *
 * ## 为什么缓存
 *
 * Hook 侧有约 2s 一次的 tick，[resolve] / [current] 可能被高频调用。每次都做一遍
 * `Class.forName` + 十个 prop 的反射 `invoke`，既浪费又在部分 ROM 上刷 SELinux avc
 * 拒绝日志。识别结果在**单个进程生命周期内不会变**（系统版本不会运行时跳变），所以用
 * `@Volatile cached` 缓存：[detect] 只在首次（或 [reset] 之后）真正反射一次，其余调用
 * 直接读 volatile 字段，配合 `synchronized` 双重检查防止多进程内多线程重复计算。
 *
 * ## HyperOS 3 / HyperOS 4 的实际差异（本类各策略函数的落点）
 *
 * | 维度 | HyperOS 3 | HyperOS 4 及以后 |
 * | --- | --- | --- |
 * | 后台启动 FGS | 已收紧 camera/dataSync 类型的后台启动，但"启动瞬间有可见 Activity"豁免仍稳定可用 | 进一步收紧，BOOT_COMPLETED 之后也常直接抛 `ForegroundServiceStartNotAllowedException`；退到 `plain` 类型 + 电池白名单更稳 |
 * | 悬浮窗权限 | 授予后基本稳定 | 更频繁地回收 `ACTION_MANAGE_OVERLAY_PERMISSION`，每次使用前最好复核 |
 * | 闪光灯 / 相机互斥 | 预览与 torch 一般可共存 | Camera2 与手电抢占更严，需先释放预览再开灯，否则开灯失败或预览黑屏 |
 * | ROOT / 进程管理 | su 相对宽松 | KernelSU/APatch 策略更紧、后台进程回收更狠；杀 SystemUI 严禁 `pkill -f` 模糊匹配 |
 * | 保活开关 | 白名单较少被重置 | 电池优化白名单 / 自启动 / 后台弹出界面 / 任务锁定 更容易被系统升级或清理后回收 |
 *
 * ## 向前兼容：为什么这张表不会成为「每年改一次」的负担
 *
 * 上表描述的是**系统倾向**，不是**代码分支**。三个真正影响功能的策略
 * （[foregroundServiceStrategy] / [torchOpenStrategy] / [overlayPermissionStrategy]）
 * 全部返回与 ROM 版本无关的常量，由运行时结果决定走哪一档降级：
 *
 * - 系统不放行 → 降级，用户仍然能用；
 * - 系统放行 → 直接用首选路径，根本不进降级分支；
 * - 新版本改了限制 → 无需改代码，降级链自动适配。
 *
 * 版本识别的结论（[Os]）只影响**给用户看的文案**（[describe] / [supportNote] /
 * [backgroundKeepAliveHint]）与 `[VER]` 日志，识别错了不会把任何功能阉掉。
 * 这是刻意设计：版本号识别本质上不可靠（prop 会被裁剪、hidden API 会被拦），
 * 绝不能让它握有功能开关的生杀权。
 *
 * 枚举保持三档（HOS3 / HOS4 / OTHER）不复膨胀：`major >= 4` 的统一归入 HOS4
 * （见 [MAJOR_MAX]），所以 HyperOS 5 / 6 出现时不需要新增枚举、不需要改测试。
 *
 * ## 适配优先级
 *
 * 本类返回的是**低优先级建议值**（字符串），不改变现有核心逻辑；是否采纳、怎么落地由
 * 调用方决定。所有失败分支都会经 [DiagLog.log] 打印，并注明属于「系统 API 变更」还是
 * 「ROM 限制」，绝不静默吞掉。仅依赖 Android SDK + Kotlin 标准库，可在 compileSdk 37
 * 编译，不需要任何 manifest 变更。
 */
object HyperOsCompat {

    /**
     * 自动识别（默认，方案 A）。Java 侧可直接 `HyperOsCompat.MODE_AUTO` 访问。
     *
     * 注：三个模式常量用 `const val`，编译器已生成 `public static final` 字段，Java 天然可访问；
     * 对 `const val` 再加 `@JvmStatic` 会编译报错（"redundant for const"），故此处不加，
     * 其余所有对外**方法**均带 `@JvmStatic`。
     */
    const val MODE_AUTO = "auto"

    /** 手动强制 HyperOS 3（方案 B）。 */
    const val MODE_HOS3 = "hos3"

    /** 手动强制 HyperOS 4（方案 B）。 */
    const val MODE_HOS4 = "hos4"

    /**
     * 适配目标版本。
     * - [HOS3] / [HOS4]：确认是小米系且识别到对应大版本；
     * - [OTHER]：非小米系，或证据不足无法判定，一律走保守策略。
     */
    enum class Os { HOS3, HOS4, OTHER }

    /** 日志默认前缀；[logEnvironment] 的 tag 缺省值与之相同。 */
    private const val DEFAULT_TAG = "[VER]"

    /** 前台服务类型的降级链，由 `WalkPreviewService.goForeground()` 逐档尝试。 */
    private const val FGS_CHAIN = "camera→specialUse→none"

    /** 高频路径失败日志的节流窗口，避免 2s tick 刷屏（与 DiagLog 的省电约定一致）。 */
    private const val WARN_INTERVAL_MS = 5_000L

    /**
     * 待读取的 system property，按"判断大版本的可信度"从高到低排列。
     * 前 6 个是任务指定项，后 4 个是"尽量多试几个"的补充，提升精简 ROM 上的命中率。
     */
    private val PROP_KEYS = arrayOf(
        "ro.mi.os.version.name",            // 最有效：常见值 HyperOS 3.0 / OS4.0.1.0
        "ro.miui.ui.version.name",          // MIUI 时代遗留：V816 / V14 之类
        "ro.mi.os.version.code",            // 纯数字版本码，偶尔能凑出 3/4 开头
        "ro.mi.os.version.incremental",     // 例如 OS3.0.1.0.UNBCNXM
        "ro.miui.version.code",
        "ro.miui.version.name",
        "ro.miui.internal.version",
        "ro.build.version.incremental",     // 标准 AOSP 属性，几乎所有设备都有
        "persist.sys.miui.version",
        "ro.miui.ui.version.code"
    )

    /**
     * 可以作为"小米系"证据的 prop。
     *
     * 关键：**排除 `ro.build.version.incremental`**。它是 AOSP 标准属性，任何品牌设备都非空，
     * 若把它算进来，"prop 非空即小米系"这条规则会把所有 Android 设备误判成小米系。其余
     * prop 都是 MIUI/HyperOS 专属，非空即可作为厂商证据。
     */
    private val XIAOMI_PROP_KEYS = setOf(
        "ro.mi.os.version.name",
        "ro.miui.ui.version.name",
        "ro.mi.os.version.code",
        "ro.mi.os.version.incremental",
        "ro.miui.version.code",
        "ro.miui.version.name",
        "ro.miui.internal.version",
        "persist.sys.miui.version",
        "ro.miui.ui.version.code"
    )

    /** manufacturer/brand/product 命中任一关键字即认为小米系。 */
    private val VENDOR_TOKENS = arrayOf("xiaomi", "redmi", "poco", "miui")

    /**
     * HyperOS 大版本号的识别上限。
     *
     * 【为什么是 6 而不是 4】枚举 [Os] 只有 HOS3 / HOS4 / OTHER 三档，但**系统版本会一直往前跑**：
     * HyperOS 5 / 6 迟早会出现（compileSdk 已到 37）。旧正则写死 `[34]`，遇到 `HyperOS 5.0`
     * 匹配不到任何版本号，只能掉进 [sdkFallback] 靠 SDK_INT 猜 —— 猜得出结果，但日志里
     * 会写成「未从 prop 提取到 3/4」，排查时看的人会以为 prop 读坏了。
     *
     * 放宽到 `[3-6]` 后：`major >= 4` 一律归入 [Os.HOS4] 分支（策略同构，见 [foregroundServiceStrategy]），
     * 版本号本身仍能在日志里如实打印。上限取 6 是刻意的 —— 再往上是八字没一撇的版本，
     * 而 `[3-9]` 会把 `V9`（MIUI 9）这类老 prop 误读成新版本。
     */
    private const val MAJOR_MAX = 6

    /** 最高可信：`HyperOS 3` ~ `HyperOS 6`。`(?!\d)` 防止把 `V35` 误读成 3。 */
    private val RE_HYPEROS = Regex("HyperOS\\s*([3-$MAJOR_MAX])(?![0-9])", RegexOption.IGNORE_CASE)

    /** 次级可信：`OS3.0`、`V4`、以及前面带分隔符/行首的 `OS/V` + 3~6。 */
    private val RE_OSV =
        Regex("(?:^|[^A-Za-z0-9])(?:OS|V)\\s*([3-$MAJOR_MAX])(?![0-9])", RegexOption.IGNORE_CASE)

    /** 再次级：prop 直接就是 `3.0.1` / `4.0` 这类纯版本号。 */
    private val RE_LEADING = Regex("^([3-$MAJOR_MAX])\\.")

    /** 方案 A 的结果缓存；null 表示尚未识别或已被 [reset] 清空。 */
    @Volatile
    private var cached: Os? = null

    /** 最近一次 [resolve] 收到的模式串，仅供 [logEnvironment] 展示"是否手动模式"。 */
    @Volatile
    private var lastMode: String? = null

    /** 高频失败日志上次打印时间，配合 [WARN_INTERVAL_MS] 节流。 */
    @Volatile
    private var lastWarnTs = 0L

    /** prop 读取快照：值、读不到/为空的 key、以及反射 API 不可用时的说明。 */
    private data class Props(
        val values: Map<String, String>,
        val failures: List<String>,
        val apiError: String?
    )

    /** 一次识别的完整依据，供 [logVerdict] 打印 `[VER:LOGIC]` 结论。 */
    private data class Verdict(
        val os: Os,
        val vendorHit: Boolean,
        val vendorSource: String,
        val major: Int?,
        val majorSource: String,
        val usedFallback: Boolean,
        val sdk: Int
    )

    /**
     * 方案 A 自动识别入口：带缓存，返回 HOS3 / HOS4 / OTHER。
     *
     * 首次调用（或 [reset] 之后）会反射读取所有 prop 并打印 `[VER:LOGIC]` 结论；
     * 后续调用直接命中 [cached]，不再做反射。Java 侧可 `HyperOsCompat.detect()`。
     */
    @JvmStatic
    fun detect(): Os {
        val c = cached
        if (c != null) return c
        return synchronized(this) {
            val c2 = cached
            if (c2 != null) {
                c2
            } else {
                val v = classify(readProps(DEFAULT_TAG))
                logVerdict(DEFAULT_TAG, v)
                cached = v.os
                v.os
            }
        }
    }

    /**
     * 清空自动识别缓存，下次 [detect] / [resolve] 会重新反射读取 prop。
     *
     * 典型用途：用户在实验区切换了系统版本、或刚升级完 ROM 想立刻复核结论。
     * 高频 tick 不要调用它，否则就失去了缓存意义。
     */
    @JvmStatic
    fun reset() {
        cached = null
        lastWarnTs = 0L
        DiagLog.log("${marker(DEFAULT_TAG, "LOGIC")} 已清空自动识别缓存，下次 detect() 将重新反射读取 prop")
    }

    /**
     * 方案 B 统一入口：把"用户选择的模式"解析成实际适配目标。
     *
     * - `null` / 空串 / [MODE_AUTO] → 走方案 A 的 [detect]；
     * - [MODE_HOS3] → [Os.HOS3]；[MODE_HOS4] → [Os.HOS4]；
     * - 其它任何值 → 记一条节流警告后仍回退到自动识别（约定为"其它 → 自动"）。
     *
     * @param mode 来自 SharedPreferences 的模式串，可为 null。
     */
    @JvmStatic
    fun resolve(mode: String?): Os {
        lastMode = mode
        return when (mode?.trim()?.lowercase(Locale.US)) {
            null, "", MODE_AUTO -> detect()
            MODE_HOS3 -> Os.HOS3
            MODE_HOS4 -> Os.HOS4
            else -> {
                warn(
                    DEFAULT_TAG, "LOGIC", "ROM 限制/配置异常",
                    "未知模式 '$mode'，按约定回退到自动识别；合法值仅 auto / hos3 / hos4"
                )
                detect()
            }
        }
    }

    /**
     * 从 `SharedPreferences` 读出模式串再交给 [resolve]。
     *
     * 为避免依赖项目里的 `SafePrefs`（本类要同时给 App 与 SystemUI 两个进程用，保持零依赖），
     * 这里直接 `sp.getString(modeKey, MODE_AUTO)`；并用 try/catch 吞掉历史类型不符触发的
     * `ClassCastException`（以及其它 ROM 定制实现抛出的异常），失败时按 [MODE_AUTO] 处理并打日志。
     *
     * @param sp      `safewalk_xsp` 或其它承载开关的 SharedPreferences。
     * @param modeKey 保存模式串的 key，由调用方提供。
     */
    @JvmStatic
    fun current(sp: SharedPreferences, modeKey: String): Os {
        val mode = try {
            sp.getString(modeKey, MODE_AUTO) ?: MODE_AUTO
        } catch (t: Throwable) {
            warn(
                DEFAULT_TAG, "API", "系统 API 变更/ROM 限制",
                "读取 sp[$modeKey] 失败（${t.javaClass.simpleName}，多为历史类型不符触发 ClassCastException），按 auto 处理"
            )
            MODE_AUTO
        }
        return resolve(mode)
    }

    /**
     * 从任意一组字符串里提取 HyperOS 大版本号（3 ~ [MAJOR_MAX]），提不到返回 **-1**。
     *
     * 公开出来有两个用途，都不是"给业务代码判断版本"：
     * 1. **单测**：正则行为（`V816` 不能被读成 3、`HyperOS 5.0` 要读成 5）在没有真机、
     *    甚至没有 Android 运行时的 JVM 单测里也能锁死，避免以后手滑改正则把识别搞坏；
     * 2. **现场排查**：可以把 prop 原文单独喂进来跑一遍，区分「根本没读到」和
     *    「读到了但解析不出来」—— 这两种情况在日志里长得一样，但修法完全不同。
     *
     * 不要用它替代 [detect]：这里不做厂商判定、不做 SDK 兜底，只回答"这串文字里有没有版本号"。
     */
    @JvmStatic
    fun majorFrom(vararg values: String): Int {
        val pool = ArrayList<Pair<String, String>>(values.size)
        values.forEachIndexed { i, v -> if (v.isNotEmpty()) pool.add("arg$i" to v) }
        return extractMajor(pool).first ?: -1
    }

    /**
     * 判断给定模式是否为"手动强制"（方案 B）。
     *
     * 仅 [MODE_HOS3] / [MODE_HOS4] 返回 true；[MODE_AUTO]、null、未知值均为 false。
     */
    @JvmStatic
    fun isManual(mode: String?): Boolean {
        val m = mode?.trim()?.lowercase(Locale.US)
        return m == MODE_HOS3 || m == MODE_HOS4
    }

    /**
     * 前台服务（FGS）类型的**尝试顺序**，不再是「该用哪个 type」。
     *
     * 【4.0 修正】3.3~3.5 这里返回 `plain` / `specialUse` / `camera` 三选一，但：
     * 1. 返回值只被打进日志，`WalkPreviewService` 一直硬编码 CAMERA，等于没落地；
     * 2. 就算落地也是错的 —— camera 是 Android 11 起「后台能访问相机」的唯一合法类型，
     *    换成 plain 会让小窗彻底拍不到画面，是把功能阉掉而不是适配。
     *
     * 现在统一返回降级链 `camera→specialUse→none`，由 `WalkPreviewService.goForeground()`
     * 逐档尝试。这条链对 HOS3 / HOS4 / 未来的 HOS5 都成立：能过第一档就不降级，
     * 过不了就往下走 —— **不需要判断 ROM 版本**，也就不会因为识别错了把功能废掉。
     */
    @JvmStatic
    fun foregroundServiceStrategy(os: Os): String = FGS_CHAIN

    /**
     * 手电（torch）策略。
     *
     * 【4.0 修正】旧返回值 `releasePreviewFirst` 已经过时：3.2 起手电统一开在
     * **预览会话内部**（`CaptureRequest.FLASH_MODE_TORCH`），不再用
     * `CameraManager.setTorchMode()` 单独抢设备，因此不存在「先释放预览再开灯」这一步。
     * 返回 `inSession` 表示：灯跟小窗同生同灭，由预览会话自己管理。
     */
    @JvmStatic
    fun torchOpenStrategy(os: Os): String = "inSession"

    /**
     * 悬浮窗权限策略。
     *
     * 【4.0 修正】`strict` 以前只是日志里的建议。现在 `WalkPreviewService.doShow()`
     * 每次显示前都真的查一次 `Settings.canDrawOverlays()`，失权时拦下并发通知，
     * 不再让 `WindowManager.addView` 抛 `BadTokenException` 后被静默吞掉。
     * 三种 ROM 都走这条路径（查询开销极低，调用频率也只有「走起来/停下」级别）。
     */
    @JvmStatic
    fun overlayPermissionStrategy(os: Os): String = "verifyBeforeShow"

    /**
     * ROOT 命令形式建议（用于 Hook 未激活时的 su 兜底，例如重启 SystemUI）。
     *
     * 统一优先返回基于**精确 pid** 的形式：
     * `su -c 'kill -9 $(pidof com.android.systemui)'`。
     *
     * 为什么严禁 `pkill -f com.android.systemui`：`-f` 按**完整命令行**模糊匹配，连
     * `su -c "pkill -f com.android.systemui"` 这条 shell 自身、以及任何 cmdline 里恰好含该串的
     * 进程都会被一起杀掉，属于典型的误杀；HOS4 上还可能连带干掉 SystemUI 的子进程导致黑屏。
     * `pidof` 取到的是精确进程名对应的 pid，`kill -9` 只针对它，安全得多。
     *
     * - [Os.HOS4] → 强调 su 授权/后台进程管理更严，优先走"Hook 自尽"这条不需要 root 的通道；
     * - [Os.HOS3] → 可保留 `killall com.android.systemui` 作为次选（仍不用 `-f`）；
     * - [Os.OTHER] → 无 ROM 额外限制，仍建议精确 pid。
     */
    @JvmStatic
    fun rootCmdStrategy(os: Os): String {
        val cmd = "su -c 'kill -9 $(pidof com.android.systemui)'"
        return when (os) {
            Os.HOS4 -> "$cmd｜HOS4 及更新版本 su 授权与后台进程回收更严：优先走 Hook 自尽（无需 root）；若必须 root 只用精确 pid，严禁 pkill -f"
            Os.HOS3 -> "$cmd｜HOS3 可用 killall com.android.systemui 作次选；仍严禁 pkill -f 按命令行模糊匹配"
            Os.OTHER -> "$cmd｜无 ROM 额外限制，仍建议精确 pid 而非 pkill -f"
        }
    }

    /**
     * 给用户看的中文保活建议。
     *
     * 覆盖四项关键开关（电池优化白名单、自启动、后台弹出界面、最近任务锁定），并按版本
     * 强调复核频率：HOS4 更容易在系统升级 / 一键清理后回收这些授权，需要定期复查。
     * 返回值可直接塞进 TextView 或 Toast。
     */
    @JvmStatic
    fun backgroundKeepAliveHint(os: Os): String {
        val base = "请完成四项保活设置：① 电池与性能 → 省电策略/电池优化 → 选「无限制」并把本应用加入白名单；" +
                "② 安全中心 → 授权管理 → 自启动 → 允许 SafeWalk 自启动；" +
                "③ 安全中心 → 权限 → 后台弹出界面/悬浮窗 → 允许；" +
                "④ 最近任务里长按本应用卡片 → 锁定（加锁），避免一键清理被杀。"
        return when (os) {
            Os.HOS4 -> "$base 【HyperOS 4 及更新版本】这些开关被系统回收得更频繁，每次系统升级或大版本更新后请重新逐项复核。"
            Os.HOS3 -> "$base 【HyperOS 3】若夜间手电或后台预览偶发不触发，优先复查①与②。"
            Os.OTHER -> "$base 【非 HyperOS/未识别】按系统原生设置项完成即可。"
        }
    }

    /**
     * 构造小米 / HyperOS「自启动设置页」Intent；全部不可用时返回 null。
     *
     * 依次尝试两个已知 component（不同 HyperOS 版本把 Activity 放在不同包路径下）：
     * 1. `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`
     * 2. `com.miui.securitycenter/.permission.autostart.AutoStartManagementActivity`
     *
     * 用 `resolveActivity` 逐个校验可用性，命中即返回带 `FLAG_ACTIVITY_NEW_TASK` 的 Intent。
     * **Android 11（API 30）+ 包可见性**：未声明 `<queries>` 时，`resolveActivity` 对第三方包名
     * 可能直接返回 null，即使 Activity 真实存在 —— 这属于「系统 API 变更」，不是代码错误；
     * 精简版安全中心没有该页面则属于「ROM 限制」。两种情况都按"不可用"处理，调用方应回退到
     * `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` 应用详情页。本文件本身**不需要 manifest 变更**。
     *
     * 全部路径都包在 try/catch 里：包可见性过滤、`PackageManager` 实现差异、ROM 定制抛异常
     * 都不会让调用方崩溃，且每个失败分支都会打日志注明归因。
     *
     * @return 可用的 Intent；若两个 component 都解析不到则返回 null。
     */
    @JvmStatic
    @SuppressLint("QueryPermissionsNeeded")
    fun autostartSettingsIntent(ctx: Context, os: Os): Intent? {
        val paths = arrayOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter/.permission.autostart.AutoStartManagementActivity"
        )
        val pm = try {
            ctx.packageManager
        } catch (t: Throwable) {
            DiagLog.log("${marker(DEFAULT_TAG, "API")} [系统 API 变更/ROM 限制] 取 PackageManager 失败：" +
                    "${t.javaClass.simpleName}，自启动页不可用，调用方请回退到应用详情页")
            return null
        }
        for (path in paths) {
            try {
                val cn = ComponentName.unflattenFromString(path) ?: continue
                val intent = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Android 11+ 包可见性会过滤未声明 <queries> 的包；try/catch 兜住 resolveActivity 异常
                if (pm.resolveActivity(intent, 0) != null) {
                    DiagLog.log("$DEFAULT_TAG 自启动设置页命中：$path（os=$os）")
                    return intent
                }
                DiagLog.log("${marker(DEFAULT_TAG, "API")} [系统 API 变更/ROM 限制] 自启动页解析为 null：" +
                        "$path；可能是 Android 11+ 包可见性过滤（未声明 <queries>），也可能是精简版安全中心未暴露该 Activity")
            } catch (t: Throwable) {
                DiagLog.log("${marker(DEFAULT_TAG, "API")} [系统 API 变更/ROM 限制] resolveActivity 异常：" +
                        "$path，${t.javaClass.simpleName}；继续尝试下一个 component")
            }
        }
        DiagLog.log("${marker(DEFAULT_TAG, "API")} [系统 API 变更/ROM 限制] 两个自启动 component 均不可用" +
                "（os=$os）；调用方请回退到 ACTION_APPLICATION_DETAILS_SETTINGS 应用详情页")
        return null
    }

    /**
     * 一行中文摘要，供设置页 / Toast / 日志展示。
     *
     * 【4.0 改版】旧版输出形如「前台服务=plain｜手电=releasePreviewFirst｜悬浮窗=strict」，
     * 三件事都有问题：一是全是内部代号，普通用户看不懂；二是 `plain` 与 `strict` 当时
     * 根本没被执行，属于「日志里写了、实际没做」；三是把「策略」写成静态值，容易让人
     * 以为 HOS3 和 HOS4 跑的是两套完全不同的代码。
     *
     * 现在只讲事实：**两种版本走同一条运行时链路**，差别只在系统会不会触发降级。
     */
    @JvmStatic
    fun describe(os: Os, mode: String?): String {
        val name = when (os) {
            Os.HOS3 -> "HyperOS 3"
            // HOS4 是「比 3 更严」这一类的代表名字，HyperOS 5/6 也归到这里
            Os.HOS4 -> "HyperOS 4 及更新版本"
            Os.OTHER -> "非 HyperOS / 未识别"
        }
        return "适配目标=$name｜前台服务 $FGS_CHAIN｜显示前复核悬浮窗权限｜手电随小窗"
    }

    /**
     * 给用户看的「HyperOS 3 到底能不能用」一句话回答。
     *
     * 刻意不写「完美支持」：本应用不针对某个 HyperOS 版本做硬判断，而是所有版本共用
     * 一条「先按标准路径做，被系统拒绝就自动降级」的链路，所以 HOS3 / HOS4 / 后续版本
     * 的行为是一致的、可预期的；真遇到系统拦截时日志里会写清是哪一档被拒。
     *
     * HOS4 分支的文案写「及更新版本」，因为 HyperOS 5/6 也会落到这个分支（见 [MAJOR_MAX]）；
     * 写死「HyperOS 4」等于给未来的用户一个看起来过期的结论。
     */
    @JvmStatic
    fun supportNote(os: Os): String = when (os) {
        Os.HOS3 -> "HyperOS 3 走标准路径：camera 前台服务 + 常显小窗 + 悬浮窗。"
        Os.HOS4 -> "HyperOS 4 及更新版本的相机前台服务更容易被拒，会按 camera → specialUse 自动降级。"
        Os.OTHER -> "非 HyperOS：走标准 Android 路径，无厂商额外限制。"
    }

    /**
     * 打印完整环境快照，用于现场排查"为什么识别成了这个版本"。
     *
     * 逐行经 [DiagLog.log] 输出，默认每行以 `[VER]` 开头；三类诊断标记由 tag 派生：
     * - `[VER:PROP]`：读取某个 prop 失败或为空（[readProps] 内输出）；
     * - `[VER:API]`：反射 / 系统 API 不可用；
     * - `[VER:LOGIC]`：厂商判定、大版本线索、SDK 兜底、最终结论（[logVerdict] 内输出）。
     *
     * 打印内容：manufacturer / brand / model / product / SDK_INT / RELEASE / INCREMENTAL /
     * DISPLAY、全部 prop 的值、识别结论、缓存状态、以及最近一次模式是否手动。
     * 这里会**重新读一次 prop**（诊断用，不写缓存），便于和 [detect] 的缓存结论对比。
     *
     * @param tag 普通环境行的前缀，默认 `[VER]`；派生标记形如 `[VER:PROP]`。
     */
    @JvmStatic
    @JvmOverloads
    fun logEnvironment(tag: String = DEFAULT_TAG) {
        val snap = readProps(tag)
        DiagLog.log("$tag manufacturer=${s(Build.MANUFACTURER)} brand=${s(Build.BRAND)} " +
                "model=${s(Build.MODEL)} product=${s(Build.PRODUCT)}")
        DiagLog.log("$tag SDK_INT=${Build.VERSION.SDK_INT} RELEASE=${s(Build.VERSION.RELEASE)} " +
                "INCREMENTAL=${s(Build.VERSION.INCREMENTAL)} DISPLAY=${s(Build.DISPLAY)}")
        for ((k, v) in snap.values) {
            DiagLog.log("$tag prop $k=${if (v.isEmpty()) "<空>" else v}")
        }
        logVerdict(tag, classify(snap))
        DiagLog.log("$tag 缓存=${cached?.name ?: "未缓存"} 最近模式=${lastMode ?: "无(默认 auto)"} " +
                "是否手动=${if (isManual(lastMode)) "是" else "否"}")
        DiagLog.log("$tag 提示：结论仅用于低优先级策略选择；强制版本请用 resolve(\"hos3\"/\"hos4\") 或 current(sp, key)")
    }

    // ---------------------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------------------

    /**
     * 反射读取全部 prop。失败一律记日志并注明归因，绝不静默吞掉。
     * 由于 [detect] 有缓存，本函数每个缓存周期只跑一次，不会在 2s tick 里刷屏。
     */
    private fun readProps(tag: String): Props {
        val get: Method? = try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
        } catch (t: Throwable) {
            DiagLog.log("${marker(tag, "API")} [系统 API 变更] 反射 android.os.SystemProperties" +
                    ".get(String) 失败：${t.javaClass.simpleName}；@hide 类被隐藏、方法签名改动，" +
                    "或 Android 9+ hidden API 限制拦截反射。将仅用 Build.* 与 SDK_INT 兜底")
            null
        }

        val values = LinkedHashMap<String, String>()
        val failures = ArrayList<String>()
        for (k in PROP_KEYS) {
            if (get == null) {
                values[k] = ""
                failures.add(k)
                continue
            }
            try {
                val v = get.invoke(null, k) as? String ?: ""
                values[k] = v
                if (v.isEmpty()) failures.add(k)
            } catch (t: Throwable) {
                values[k] = ""
                failures.add(k)
                DiagLog.log("${marker(tag, "PROP")} [系统 API 变更/ROM 限制] 读取 prop 失败：key=$k，" +
                        "${t.javaClass.simpleName}；可能是 hidden API 限制（系统 API 变更），" +
                        "也可能是该 ROM 未写入此属性或被 SELinux 拦截（ROM 限制）")
            }
        }
        if (get == null) {
            DiagLog.log("${marker(tag, "API")} [系统 API 变更] ${PROP_KEYS.size} 个 prop 全部无法读取：" +
                    "SystemProperties.get 不可用，转 Build/SDK 兜底")
        } else if (failures.isNotEmpty()) {
            DiagLog.log("${marker(tag, "PROP")} [ROM 限制] ${failures.size} 个 prop 为空或读取失败：" +
                    "${failures.joinToString(",")}；不同 HyperOS 版本暴露的 prop 集合不同，属正常现象")
        }
        return Props(values, failures, if (get == null) "SystemProperties.get 反射不可用" else null)
    }

    /** 方案 A 核心：厂商判定 → 大版本判定 → SDK 兜底，产出带完整依据的 [Verdict]。 */
    private fun classify(snap: Props): Verdict {
        val manufacturer = s(Build.MANUFACTURER)
        val brand = s(Build.BRAND)
        val product = s(Build.PRODUCT)
        val display = s(Build.DISPLAY)
        val incremental = s(Build.VERSION.INCREMENTAL)
        val release = s(Build.VERSION.RELEASE)
        val sdk = Build.VERSION.SDK_INT

        // ① 厂商：manufacturer/brand/product 含小米系关键字，或 MIUI/HyperOS 专属 prop 非空
        var vendorHit = false
        var vendorSource = "无小米系特征（字段无关键字且专属 prop 全空）"
        for (f in arrayOf(manufacturer, brand, product)) {
            val lower = f.lowercase(Locale.US)
            val token = VENDOR_TOKENS.firstOrNull { lower.contains(it) }
            if (token != null) {
                vendorHit = true
                vendorSource = "Build 字段 '$f' 含 '$token'"
                break
            }
        }
        if (!vendorHit) {
            for (k in XIAOMI_PROP_KEYS) {
                if (snap.values[k].orEmpty().isNotEmpty()) {
                    vendorHit = true
                    vendorSource = "MIUI/HyperOS 专属 prop '$k' 非空"
                    break
                }
            }
        }

        // ② 大版本：把 prop 值与 Build 里的版本类字段汇成候选池，按正则可信度依次匹配
        val pool = ArrayList<Pair<String, String>>()
        for (k in PROP_KEYS) {
            val v = snap.values[k].orEmpty()
            if (v.isNotEmpty()) pool.add(k to v)
        }
        if (display.isNotEmpty()) pool.add("Build.DISPLAY" to display)
        if (incremental.isNotEmpty()) pool.add("Build.VERSION.INCREMENTAL" to incremental)
        if (release.isNotEmpty()) pool.add("Build.VERSION.RELEASE" to release)
        val (major, majorSource) = extractMajor(pool)

        // ③ 定案：非小米系直接 OTHER；小米系优先用 prop 提取到的版本号，没命中才用 SDK 兜底
        //
        // 【为什么 major>=4 都归 HOS4】适配策略只有「HOS3 / 新版」两种形态（见
        // foregroundServiceStrategy 的说明：两种 ROM 走同一条降级链，区别只在系统会不会触发降级）。
        // 将来 HyperOS 5/6 出现时，把它归进 HOS4 分支是最准确的选择 —— 它一定属于"比 HOS3 更严"的那一类，
        // 而不是退化成 OTHER 走最保守策略。
        val os: Os
        val usedFallback: Boolean
        if (!vendorHit) {
            os = Os.OTHER
            usedFallback = false
        } else if (major != null && major >= 4) {
            os = Os.HOS4
            usedFallback = false
        } else if (major == 3) {
            os = Os.HOS3
            usedFallback = false
        } else {
            os = sdkFallback(sdk)
            usedFallback = true
        }
        return Verdict(os, vendorHit, vendorSource, major, majorSource, usedFallback, sdk)
    }

    /**
     * 从候选字符串池里提取 HyperOS 大版本（3 ~ [MAJOR_MAX]）。
     * 按「HyperOS 显式 > OS/V 前缀 > 纯版本号开头」的优先级整池扫描，先命中先返回，
     * 返回 (版本号或 null, 命中的来源说明)。
     */
    private fun extractMajor(pool: List<Pair<String, String>>): Pair<Int?, String> {
        for (re in arrayOf(RE_HYPEROS, RE_OSV, RE_LEADING)) {
            for ((src, value) in pool) {
                val m = re.find(value) ?: continue
                val g = m.groupValues[1].toIntOrNull() ?: continue
                if (g in 3..MAJOR_MAX) return g to "$src 命中「${m.value.trim()}」"
            }
        }
        return null to "所有 prop / Build 字段均未出现 HyperOS 3~$MAJOR_MAX 版本号"
    }

    /**
     * SDK_INT 兜底：SDK>=36 → HOS4，>=35 → HOS3，再低 → OTHER。
     *
     * **兜底仅为近似，prop 优先**：Android 大版本与 HyperOS 大版本不是一一对应，
     * 这里只用于 prop 全拿不到（hidden API 被拦 / ROM 裁剪）时给一个保守代际估计。
     * 用裸整数 36/35 而非 Build.VERSION_CODES 常量名，避免依赖某个 compileSdk 才有的符号。
     * 低版本返回 OTHER 是刻意的：宁可走保守策略，也不要把 MIUI/普通 Android 误判成 HOS3/4。
     *
     * 这里用 `>=` 而不是 `==`：Android 16 之后的 SDK 37/38（HyperOS 5/6 基座）继续落在
     * HOS4 分支，与 [classify] 里 `major >= 4` 的归并规则保持一致。写成 `==` 就会让
     * 每一代新 Android 都掉回 OTHER，等于每年都要改一次代码。
     */
    private fun sdkFallback(sdk: Int): Os = when {
        sdk >= 36 -> Os.HOS4   // Android 16 及以后 ≈ HyperOS 4 及更新版本
        sdk >= 35 -> Os.HOS3   // Android 15 ≈ HyperOS 3 代际
        else -> Os.OTHER
    }

    /** 打印 `[VER:LOGIC]` 系列的识别依据与结论。 */
    private fun logVerdict(tag: String, v: Verdict) {
        val mk = marker(tag, "LOGIC")
        DiagLog.log("$mk 厂商判定=${if (v.vendorHit) "小米系（${v.vendorSource}）" else "非小米系（${v.vendorSource}）→ OTHER"}")
        DiagLog.log("$mk 大版本线索=${v.major ?: "未命中"}（来源=${v.majorSource}）")
        if (v.usedFallback) {
            DiagLog.log("$mk 未从 prop 提取到 3~$MAJOR_MAX 版本号，改用 SDK_INT=${v.sdk} 兜底：>=36→HOS4，>=35→HOS3，否则 OTHER；兜底仅为近似，prop 优先")
        }
        val merged = if (v.major != null && v.major > 4) {
            "（HyperOS ${v.major} 按「>=4 归入同一分支」的规则并到 HOS4，策略同构，无需新增枚举）"
        } else {
            ""
        }
        DiagLog.log("$mk 识别结论=${v.os}$merged")
    }

    /**
     * 高频路径（[resolve] / [current]）的失败日志：同一进程 5s 内最多一条，
     * 避免配置错误时在 2s tick 里刷屏（DiagLog 里也强调过这点）。
     */
    private fun warn(tag: String, name: String, attribution: String, text: String) {
        val now = System.currentTimeMillis()
        if (now - lastWarnTs < WARN_INTERVAL_MS) return
        lastWarnTs = now
        DiagLog.log("${marker(tag, name)} [$attribution] $text")
    }

    /** 由 tag 派生诊断标记：`[VER]` + `PROP` → `[VER:PROP]`。 */
    private fun marker(tag: String, name: String): String =
        if (tag.endsWith("]")) tag.dropLast(1) + ":$name]" else "$tag:$name"

    /** Build 的静态 String 是平台类型，统一空安全化，避免个别 ROM 返回 null。 */
    private fun s(v: String?): String = v ?: ""
}
