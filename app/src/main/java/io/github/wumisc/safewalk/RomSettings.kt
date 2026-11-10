package io.github.wumisc.safewalk

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * 用户选择的 UI 生态（4.3 新增，取代首页原来的「HyperOS 版本适配」）。
 *
 * ## 为什么需要它
 *
 * 4.2 及更早，首页让用户选「自动 / HyperOS 3 / HyperOS 4」。那个 UI 有两个问题：
 *
 * 1. **只对小米有意义**。荣耀、OPPO、vivo、原生用户看到一张「HyperOS 版本适配」卡片，
 *    既不知道跟自己有没有关系，也不敢点。
 * 2. **它选的是"版本"，而代码根本不看版本**。三个策略函数（前台服务 / 手电 / 悬浮窗）
 *    全是运行时降级链，`os` 参数一次都没被读过。让用户为一个不影响行为的选项做决定，
 *    是纯粹的困惑来源。
 *
 * 现在改成「本机 UI 是哪一家」，因为这件事**确实影响行为**：决定自启动页跳哪儿、
 * 第 4 步引导文案怎么写、以及用哪张相机包名表（并集）。而且它是所有厂商都存在的概念。
 *
 * ## override 与自动识别的关系
 *
 * 自动识别看 `Build.MANUFACTURER/BRAND/PRODUCT`，刷过机、改装机、工程机都可能认错
 * （例如 LineageOS 刷在小米机器上，manufacturer 仍是 Xiaomi）。所以保留手动指定：
 * **手动优先，自动兜底**，且手动只影响「指路与文案」，不影响功能链路——功能链路
 * 永远在运行时试，见 [RomPageLauncher] 与 `WalkPreviewService.goForeground()`。
 *
 * 这里的函数全部无副作用（读 SP 的两个除外），单测直接调纯函数部分。
 */
object RomSettings {

    /** override 的「跟随自动识别」值。同时也是缺省值。 */
    const val OVERRIDE_AUTO = "auto"

    /**
     * 主配置文件名。
     *
     * 与 `MainActivity.xsp()`、`ConfigProvider.PREFS` 是同一个文件（"safewalk_xsp"）。
     * 三处各写一遍字面量确实不够优雅，但抽公共常量的改动会牵到 ConfigProvider 的
     * 跨进程契约，收益不抵风险；这里加注释锁住，改名前先搜这三个文件。
     */
    private const val PREFS = "safewalk_xsp"

    /**
     * 把任意输入规范成合法 override 值；认不出一律 [OVERRIDE_AUTO]。
     *
     * 大小写不敏感、允许前后空格。非法值**不能**抛异常也不能原样保留：
     * 旧配置里可能存着已删除的模式（如 hos3），原样保留会让 [overrideId] 返回 null
     * 而又不等于 auto，UI 上出现「一个芯片都不亮」的状态。
     */
    @JvmStatic
    fun normalize(raw: String?): String {
        val t = raw?.trim()?.uppercase(Locale.US)
        if (t.isNullOrEmpty() || t == "AUTO") return OVERRIDE_AUTO
        return RomId.values().firstOrNull { it.name == t }?.name ?: OVERRIDE_AUTO
    }

    /** override 串 → 生态；auto 返回 null（语义是「跟着自动识别走」）。 */
    @JvmStatic
    fun overrideId(raw: String?): RomId? {
        val n = normalize(raw)
        if (n == OVERRIDE_AUTO) return null
        return RomId.values().firstOrNull { it.name == n }
    }

    /**
     * 生效的生态：手动指定优先，否则用 [detected]。
     *
     * `detected` 做成参数而不是内部调用 `RomDetector.detectFromBuild()`，
     * 是为了这条「优先级」能被 JVM 单测覆盖——单测里没有 Android 运行时。
     */
    @JvmStatic
    @JvmOverloads
    fun resolve(raw: String?, detected: RomId = RomDetector.detectFromBuild()): RomId =
        overrideId(raw) ?: detected

    /** 读本机保存的 override（永远不会抛，读不到按 auto）。 */
    @JvmStatic
    fun overrideOf(ctx: Context?): String {
        val sp: SharedPreferences = prefs(ctx) ?: return OVERRIDE_AUTO
        return try {
            normalize(SafePrefs.getString(sp, SafeWalkPrefKey.romOverride, OVERRIDE_AUTO))
        } catch (_: Throwable) {
            OVERRIDE_AUTO
        }
    }

    /** 当前真正生效的生态。**所有**业务代码都该走这个，而不是直接 `RomDetector.detectFromBuild()`。 */
    @JvmStatic
    fun effective(ctx: Context?): RomId = resolve(overrideOf(ctx), RomDetector.detectFromBuild())

    /**
     * 写入 override（同步 commit，与全 App「改完立即落盘」的约定一致）。
     *
     * 为什么不用 `apply()`：用户点完芯片可能立刻从最近任务划掉 App，而 `apply()` 的
     * 异步写盘在有后台任务/进程被杀时理论上可能丢。这一项只有一次写入、数据量极小，
     * 用 commit 换「点了就一定记住」是划算的（与 MainActivity 的其它配置项同一策略）。
     */
    @Suppress("ApplySharedPref", "UseKtx")
    @JvmStatic
    fun save(ctx: Context?, raw: String?) {
        val sp = prefs(ctx) ?: return
        try {
            sp.edit().putString(SafeWalkPrefKey.romOverride, normalize(raw)).commit()
        } catch (_: Throwable) {
        }
    }

    private fun prefs(ctx: Context?): SharedPreferences? {
        val app = ctx?.applicationContext ?: return null
        return try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 首页那唯一一行注明文字。
     *
     * 三处刻意取舍：
     *
     * 1. **用 [RomId.shortName] 而不是 displayName**。这一行是 `maxLines=1` + ellipsize，
     *    AOSP 的 displayName 是「AOSP / 类原生」，加上后缀会超过 360dp 机型的可用宽度，
     *    被截成「…手动指定（只影响…」——用户看到的就是一句断掉的话。而且芯片上写的就是
     *    短名，用户刚点的也是它，两处一致更好认。
     * 2. **必须写明「仅影响指路」**。否则用户会以为选了 ColorOS 就等于「切到 ColorOS
     *    兼容模式」。实际上功能链路完全不变（见 [RomPageLauncher] 与
     *    `WalkPreviewService.goForeground()` 的运行时降级链），变的只有设置页跳转与文案。
     * 3. **认不出来时不能写「原生」**。三星 One UI、摩托 MyOS 都落在 [RomId.AOSP] 兜底，
     *    但用户手机上明明不是原生 Android，一句话写错就会让人怀疑整页数据。
     *    这种情况写「未识别 · 按原生处理」，既说了实话，也暗示「可以往下手动指定」。
     *
     * @param unrecognized 自动识别时厂商关键字一个都没命中（见 [RomDetector.isUnrecognized]）。
     *   手动指定时忽略此参数——用户既然选了，就以他的选择为准。
     */
    @JvmStatic
    @JvmOverloads
    fun caption(id: RomId, raw: String?, unrecognized: Boolean = false): String = when {
        overrideId(raw) != null -> "本机 UI：${id.shortName} · 手动指定（仅影响指路）"
        unrecognized && id == RomId.AOSP -> "未识别系统 UI · 按原生处理（可手动指定）"
        else -> "本机 UI：${id.shortName} · 自动识别"
    }

    /**
     * 日志一行，写清「为什么是这个结论」：自动命中了什么，还是用户手选的。
     *
     * `detected` 同样是参数而非内部直调 `detectFromBuild()`，理由同上：让「手动时
     * 日志里要带上被忽略的自动结果」这条行为能被 JVM 单测验证。
     */
    @JvmStatic
    @JvmOverloads
    fun explain(id: RomId, raw: String?, detected: RomId = RomDetector.detectFromBuild()): String =
        if (overrideId(raw) == null) {
            "自动识别 → ${id.displayName}"
        } else {
            "手动指定 → ${id.displayName}（自动识别结果为 ${detected.displayName}，已忽略）"
        }
}
