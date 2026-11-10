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

    /** 包名精确匹配；带 `:子进程` 后缀也算同一应用。 */
    private val CAMERA_LIKE = arrayOf(
        "com.android.camera",
        "com.miui.camera",
        "com.google.android.GoogleCamera",
        "com.oplus.camera",
        "com.samsung.android.camera",
        "com.huawei.camera",
        "com.hihonor.camera",
        "com.vivo.camera",
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
}
