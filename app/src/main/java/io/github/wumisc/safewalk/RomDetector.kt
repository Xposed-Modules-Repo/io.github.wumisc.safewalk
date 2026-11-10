package io.github.wumisc.safewalk

import android.os.Build
import java.util.Locale

/**
 * UI 生态判定（P0 交付物）。
 *
 * ## 只认三个 Build 字段，不碰 prop
 *
 * `HyperOsCompat` 那套反射读 `ro.mi.os.version.name` 的做法**不在这里复用**：
 * 它的目的是区分 HyperOS 3 / 4（大版本），而本类只需要区分「哪一家 UI」。
 * 厂商身份看 `MANUFACTURER` / `BRAND` / `PRODUCT` 就够了，比读 prop 稳得多，
 * 也不需要 hidden API 反射和缓存。
 *
 * 代价是**分不出 MIUI 与 HyperOS**——但这对本计划没影响：小米从 MIUI 14 到
 * HyperOS 3，自启动页的包路径一直是那两套，用同一个档案反而更省事。
 *
 * ## 判定顺序即优先级
 *
 * OEM 交叉授权导致一个设备可能同时命中多组（例如 realme 的 `PRODUCT` 里也可能
 * 出现 `oplus`）。按 [RomId] 声明顺序取第一个命中，保证结果稳定、可预测：
 * 同样的输入永远得到同样的输出，单测才有意义。
 *
 * ## 未识别一律 AOSP
 *
 * 认不出来时给最保守的档案：只有标准 action，不会把用户引到一个厂商私有页门口
 * 然后打不开。宁可少指一条路，也不指错一条路。
 */
object RomDetector {

    /**
     * 关键字 → 生态。**顺序即优先级**，不要随意调整顺序：
     * 调整会改变交叉授权设备的判定结果（华为/荣耀是唯一会同时命中的一对，见 TOKENS 末尾注释）。
     */
    private val TOKENS: List<Pair<RomId, Array<String>>> = listOf(
        RomId.HYPEROS to arrayOf("xiaomi", "redmi", "poco", "miui", "hyperos"),
        RomId.MAGICOS to arrayOf("honor", "hihonor", "magicos"),
        // oneplus 放前面：部分一加设备的 PRODUCT 里同时含 oppo 与 oneplus，
        // 两者都属于 COLOROS，顺序不影响结果，但先命中先返回能少扫几个关键字
        RomId.COLOROS to arrayOf("oppo", "oneplus", "realme", "oplus", "coloros"),
        RomId.ORIGINOS to arrayOf("vivo", "iqoo", "originos", "bbk"),
        // 华为放最后：荣耀老机型 manufacturer 也是 HUAWEI、brand 是 HONOR，
        // MAGICOS 排在前面优先命中，正好落在「MagicUI 时期」那份档案里
        RomId.HARMONYOS to arrayOf("huawei", "harmony", "emui")
    )

    /**
     * 明确属于「原生阵营」的关键字。
     *
     * 与 [TOKENS] 的区别：[TOKENS] 命中五家厂商，[AOSP_TOKENS] 命中「本来就是原生」。
     * 两者都不命中 → **认不出来**，见 [isUnrecognized]。
     */
    private val AOSP_TOKENS = arrayOf(
        "google", "pixel", "nexus", "lineage", "aosp", "pixelexperience", "generic"
    )

    /**
     * 纯函数判定：给三个 Build 字段，返回生态。可空、可乱大小写、可只给一个字段。
     *
     * 单测直接调这个，不需要 Android 运行时。
     */
    @JvmStatic
    fun detect(manufacturer: String?, brand: String?, product: String?): RomId {
        // 拼成一个 haystack 再扫，避免「每个字段扫一遍、漏掉跨字段组合」的写法；
        // 用 | 分隔防止 "mi" + "phone" 这类跨界拼接产生假命中
        val hay = haystack(manufacturer, brand, product)
        if (hay.isEmpty()) return RomId.AOSP

        for ((id, tokens) in TOKENS) {
            for (t in tokens) {
                if (hay.contains(t)) return id
            }
        }
        return RomId.AOSP
    }

    /**
     * 是否「认不出这是哪一家」。
     *
     * 为什么不直接用 `detect(...) == AOSP`：Pixel 和 LineageOS 的判定结果也是 [RomId.AOSP]，
     * 但那是「它本来就是原生」，跟用户说「本机 UI：原生」完全正确；而三星、摩托罗拉、
     * 索尼同样落到 [RomId.AOSP]，如果也显示「原生」，用户看到的是一句**明确错误**的话
     * ——他手机上是 One UI，不是原生 Android。
     *
     * 所以分成三种语义：
     * - 命中 [TOKENS] → 五家之一；
     * - 命中 [AOSP_TOKENS] → 真是原生；
     * - 都不命中 → 未收录，UI 上写「未识别（按原生处理）」，让用户知道可以手动指定。
     */
    @JvmStatic
    fun isUnrecognized(manufacturer: String?, brand: String?, product: String?): Boolean {
        val hay = haystack(manufacturer, brand, product)
        if (hay.isEmpty()) return true
        if (TOKENS.any { (_, ts) -> ts.any { hay.contains(it) } }) return false
        return AOSP_TOKENS.none { hay.contains(it) }
    }

    /**
     * 运行时判定：读真实 `Build` 字段。
     *
     * 单独一个方法而不是重载 `detect()`，是为了让「无参调用」在代码里一眼可辨——
     * 它依赖 Android 运行时，本地单测碰不得。
     */
    @JvmStatic
    fun detectFromBuild(): RomId = detect(Build.MANUFACTURER, Build.BRAND, Build.PRODUCT)

    /** 运行时版本：[isUnrecognized] 的 Build 字段入口。 */
    @JvmStatic
    fun isUnrecognizedOnBuild(): Boolean =
        isUnrecognized(Build.MANUFACTURER, Build.BRAND, Build.PRODUCT)

    /**
     * 把三个字段拼成小写 haystack。
     *
     * 用 `|` 分隔而不是直接相连：否则 `mi` + `phone` 会拼成 `miphone` 之类的假命中。
     * 三处（[detect] / [isUnrecognized] / [explain]）共用同一个拼法，避免判定与日志
     * 用两套规则、结果对不上。
     */
    private fun haystack(manufacturer: String?, brand: String?, product: String?): String =
        listOf(manufacturer, brand, product)
            .filterNotNull()
            .joinToString("|") { it.lowercase(Locale.US) }

    /**
     * 一行判定依据，供日志排查「为什么认成了这一家」。
     *
     * 与 [HyperOsCompat.logEnvironment] 的区别：那边打全量 prop 快照，这边只打
     * 命中关键字，因为本类的判定输入只有三个字段。
     */
    @JvmStatic
    fun explain(manufacturer: String?, brand: String?, product: String?): String {
        val id = detect(manufacturer, brand, product)
        val hay = haystack(manufacturer, brand, product)
        val hit = TOKENS.firstOrNull { it.first == id }
            ?.second?.firstOrNull { hay.contains(it) }
        return if (hit == null) {
            "manufacturer=${manufacturer ?: "-"} brand=${brand ?: "-"} product=${product ?: "-"} " +
                    "→ ${id.displayName}（无关键字命中，按未识别处理）"
        } else {
            "manufacturer=${manufacturer ?: "-"} brand=${brand ?: "-"} product=${product ?: "-"} " +
                    "→ ${id.displayName}（命中「$hit」）"
        }
    }
}
