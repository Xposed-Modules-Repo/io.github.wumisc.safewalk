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
     * 调整会改变交叉授权设备的判定结果（虽然四家的关键字目前互不包含）。
     */
    private val TOKENS: List<Pair<RomId, Array<String>>> = listOf(
        RomId.HYPEROS to arrayOf("xiaomi", "redmi", "poco", "miui", "hyperos"),
        RomId.MAGICOS to arrayOf("honor", "hihonor", "magicos"),
        // oneplus 放前面：部分一加设备的 PRODUCT 里同时含 oppo 与 oneplus，
        // 两者都属于 COLOROS，顺序不影响结果，但先命中先返回能少扫几个关键字
        RomId.COLOROS to arrayOf("oppo", "oneplus", "realme", "oplus", "coloros"),
        RomId.ORIGINOS to arrayOf("vivo", "iqoo", "originos", "bbk")
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
        val hay = listOf(manufacturer, brand, product)
            .filterNotNull()
            .joinToString("|") { it.lowercase(Locale.US) }
        if (hay.isEmpty()) return RomId.AOSP

        for ((id, tokens) in TOKENS) {
            for (t in tokens) {
                if (hay.contains(t)) return id
            }
        }
        return RomId.AOSP
    }

    /**
     * 运行时判定：读真实 `Build` 字段。
     *
     * 单独一个方法而不是重载 `detect()`，是为了让「无参调用」在代码里一眼可辨——
     * 它依赖 Android 运行时，本地单测碰不得。
     */
    @JvmStatic
    fun detectFromBuild(): RomId = detect(Build.MANUFACTURER, Build.BRAND, Build.PRODUCT)

    /**
     * 一行判定依据，供日志排查「为什么认成了这一家」。
     *
     * 与 [HyperOsCompat.logEnvironment] 的区别：那边打全量 prop 快照，这边只打
     * 命中关键字，因为本类的判定输入只有三个字段。
     */
    @JvmStatic
    fun explain(manufacturer: String?, brand: String?, product: String?): String {
        val id = detect(manufacturer, brand, product)
        val hay = listOf(manufacturer, brand, product).filterNotNull()
            .joinToString("|") { it.lowercase(Locale.US) }
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
