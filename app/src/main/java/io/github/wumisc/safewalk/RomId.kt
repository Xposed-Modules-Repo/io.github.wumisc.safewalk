package io.github.wumisc.safewalk

/**
 * 五大目标 UI 生态（见 `docs/rom-adaptation-plan.md`）。
 *
 * ## 为什么用枚举而不是字符串
 *
 * 字符串常量在 Java/Kotlin 混编里拼错了编译器不报错，只会在运行时悄悄走 [AOSP] 分支——
 * 那正是「明明适配了却没生效」的典型症状。枚举把这些拼写错误拦在编译期。
 *
 * ## 为什么只有五个值
 *
 * 这里枚举的是**UI 生态**，不是厂商。同一生态下的子品牌（Redmi/POCO 属 HyperOS，
 * OnePlus/realme 属 ColorOS，iQOO 属 OriginOS）共用一个 profile：
 * 它们的自启动页、电池页、相机包名是同一套，拆开只会得到几份内容重复的表。
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
    val vendor: String
) {
    /** 小米 / Redmi / POCO，含 MIUI 时代设备（统一按 HyperOS 的页面路径处理）。 */
    HYPEROS("HyperOS", "Xiaomi / Redmi / POCO"),

    /** 荣耀 MagicOS。 */
    MAGICOS("MagicOS", "HONOR"),

    /** OPPO ColorOS，含 OnePlus（氧/色合并后同源）与 realme。 */
    COLOROS("ColorOS", "OPPO / OnePlus / realme"),

    /** vivo OriginOS，含 iQOO。 */
    ORIGINOS("OriginOS", "vivo / iQOO"),

    /**
     * 原生 Android 与类原生（Pixel、LineageOS、PixelExperience 等）。
     *
     * 也是**所有未识别设备的兜底**：不认识的厂商一律按 AOSP 处理，
     * 因为 AOSP 的路径只有公开 API，不会把用户引到一个不存在的页面。
     */
    AOSP("AOSP / 类原生", "Google / LineageOS / PixelExperience");

    /** 是否为「有厂商私有页」的 ROM。AOSP 没有自启动/后台弹窗这类概念。 */
    val hasVendorPages: Boolean get() = this != AOSP
}
