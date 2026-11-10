package io.github.wumisc.safewalk

/**
 * 五大厂商 + 原生（见 `docs/rom-adaptation-plan.md`）。
 *
 * ## 为什么用枚举而不是字符串
 *
 * 字符串常量在 Java/Kotlin 混编里拼错了编译器不报错，只会在运行时悄悄走 [AOSP] 分支——
 * 那正是「明明适配了却没生效」的典型症状。枚举把这些拼写错误拦在编译期。
 *
 * ## 为什么是「五个厂商 + 一个原生」这六个值
 *
 * 前五个是中国市场保有量最大的五家：小米、荣耀、OPPO、vivo、华为。
 * 这里枚举的是**UI 生态**，不是厂商本身。同一生态下的子品牌（Redmi/POCO 属 HyperOS，
 * OnePlus/realme 属 ColorOS，iQOO 属 OriginOS）共用一个 profile：
 * 它们的自启动页、电池页、相机包名是同一套，拆开只会得到几份内容重复的表。
 *
 * 第六个 [AOSP] 既是「原生/类原生」，也是所有未识别设备的兜底。
 *
 * ## 为什么不叫「厂商」
 *
 * 因为判定入口是 `Build.MANUFACTURER` + `Build.BRAND` + `Build.PRODUCT` 的组合，
 * 而 OEM 会交叉授权（一加归 OPPO、iQOO 归 vivo）。按 UI 分比按厂商分稳定：
 * 用户关心的是「我这是 ColorOS，该去哪儿开自启动」，而不是「我的厂商代码是什么」。
 */
enum class RomId(
    /** 给用户看的名字，直接进设置页文案。 */
    val displayName: String,
    /** 子品牌说明，只用于日志与文档，不进 UI。 */
    val vendor: String,
    /**
     * 选择器芯片上的短名。
     *
     * 首页那一行要放下 7 个芯片（自动 + 5 个厂商 + 原生），360dp 宽的机器每个只有约 43dp。
     * 用 [displayName] 会溢出：「OriginOS」「HarmonyOS」都只放得下一半。
     * 所以芯片上用厂商短名（小米 / 荣耀 / OPPO / vivo / 华为 / 原生），
     * 这一行注明里用 [displayName] 的短版本（HyperOS / MagicOS …），各司其职。
     */
    val shortName: String
) {
    /** 小米 / Redmi / POCO，含 MIUI 时代设备（统一按 HyperOS 的页面路径处理）。 */
    HYPEROS("HyperOS", "Xiaomi / Redmi / POCO", "HyperOS"),

    /** 荣耀 MagicOS。 */
    MAGICOS("MagicOS", "HONOR", "MagicOS"),

    /** OPPO ColorOS，含 OnePlus（氧/色合并后同源）与 realme。 */
    COLOROS("ColorOS", "OPPO / OnePlus / realme", "ColorOS"),

    /** vivo OriginOS，含 iQOO。 */
    ORIGINOS("OriginOS", "vivo / iQOO", "OriginOS"),

    /**
     * 华为 EMUI / HarmonyOS（4.x 及更早）。
     *
     * 只覆盖仍能装 APK 的版本：HarmonyOS NEXT（5.0 起）不兼容 Android 应用，
     * 本模块在那种机器上根本装不上，不在适配范围内。
     */
    HARMONYOS("HarmonyOS / EMUI", "HUAWEI", "HarmonyOS"),

    /**
     * 原生 Android 与类原生（Pixel、LineageOS、PixelExperience 等）。
     *
     * 也是**所有未识别设备的兜底**：不认识的厂商一律按 AOSP 处理，
     * 因为 AOSP 的路径只有公开 API，不会把用户引到一个不存在的页面。
     */
    AOSP("AOSP / 类原生", "Google / LineageOS / PixelExperience", "原生");

    /** 是否为「有厂商私有页」的 ROM。AOSP 没有自启动/后台弹窗这类概念。 */
    val hasVendorPages: Boolean get() = this != AOSP
}
