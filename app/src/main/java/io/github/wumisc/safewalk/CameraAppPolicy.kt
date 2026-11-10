package io.github.wumisc.safewalk

/**
 * 「前台应用是不是相机类」判定（纯逻辑，便于单测）。
 *
 * 用途：无障碍通道拿到前台包名后，决定要不要把角落小窗收起来。
 * 小窗是 `TYPE_APPLICATION_OVERLAY`，会盖在相机取景框上；同时我们自己的预览
 * 会话还占着摄像头，对方 openCamera 会直接 CAMERA_IN_USE。所以只要前台是
 * 相机 / 录像 / 视频通话类应用，就先让路，离开后自动恢复。
 *
 * 刻意**不做**「任何应用都收起」：那等于把「常显小窗」这个功能废掉。
 */
object CameraAppPolicy {

    /**
     * 包名精确匹配；带 `:子进程` 后缀也算同一应用。
     *
     * ## 为什么这张表这么长
     *
     * 漏判的代价是**小窗盖住取景框 + 对方 openCamera 直接 CAMERA_IN_USE**（功能坏掉）；
     * 误判的代价只是「打开这个 App 时小窗暂时收起」（体验略差，退出即恢复）。
     * 两者不对称，所以宁可多收几个真·相机包名。
     *
     * 但也不能无脑加：只收「打开后会独占摄像头」的应用。社交类（微信 / WhatsApp）
     * 只在视频通话时占用相机，平时刷消息不该收起小窗，因此刻意不在表内。
     *
     * ## 几个容易写错的包名
     *
     * · 三星是 `com.sec.android.app.camera`，不是 `com.samsung.android.camera`
     *   （后者在部分地区版本存在，两个都留着）；
     * · AOSP 原生相机 `com.android.camera` 与 `com.android.camera2` 是**两个**真实包名，
     *   取并集而不是二选一；
     * · 一加在氧 OS 时代是 `com.oneplus.camera`，ColorOS 合并后改用 `com.oplus.camera`。
     */
    private val CAMERA_LIKE = arrayOf(
        // AOSP / 类原生
        "com.android.camera",
        "com.android.camera2",
        // 小米 / Redmi / POCO
        "com.miui.camera",
        // Google
        "com.google.android.GoogleCamera",
        // OPPO / 一加 / realme
        "com.oplus.camera",
        "com.oneplus.camera",
        // 三星（sec 才是主包名）
        "com.sec.android.app.camera",
        "com.samsung.android.camera",
        // 华为 / 荣耀
        "com.huawei.camera",
        "com.hihonor.camera",
        // vivo / iQOO
        "com.vivo.camera",
        // 摩托罗拉 / 索尼 / 华硕
        "com.motorola.camera2",
        "com.motorola.camera3",
        "com.sonyericsson.android.camera",
        "com.asus.camera",
        // 第三方
        "com.meitu.meiyancamera",
        "us.zoom.videomeetings",
        "com.tencent.wemeet"
    )

    @JvmStatic
    fun isCameraLike(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        for (p in CAMERA_LIKE) {
            if (pkg == p || pkg.startsWith("$p:")) return true
        }
        return false
    }

    /**
     * 通用相机包名表（只读副本）。
     *
     * 供 [RomRegistry] 与各 [RomProfile] 的厂商表做并集。**这里永远是「所有 ROM 通用」
     * 的部分**，厂商专属包名放在各自的 profile 里，两边不重复维护。
     *
     * 返回 `List` 而不是内部数组：调用方拿不到可变引用，改不动这张表。
     */
    @JvmStatic
    fun basePackages(): List<String> = CAMERA_LIKE.toList()
}
