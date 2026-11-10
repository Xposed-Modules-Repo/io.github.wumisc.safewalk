package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「前台是不是相机类应用」判定回归测试。
 *
 * 背景：无障碍服务把前台包名交给预览服务，决定要不要收起角落小窗。
 * 判错两个方向都有代价：
 * ① 漏判 → 小窗盖在取景框上，还占着摄像头让对方 CAMERA_IN_USE；
 * ② 误判 → 随便打开个应用小窗就消失，常显模式形同虚设。
 */
public class CameraAppPolicyTest {

    @Test
    public void commonCameraAppsAreDetected() {
        assertTrue(CameraAppPolicy.isCameraLike("com.android.camera"));
        assertTrue(CameraAppPolicy.isCameraLike("com.miui.camera"));
        assertTrue(CameraAppPolicy.isCameraLike("com.google.android.GoogleCamera"));
        assertTrue(CameraAppPolicy.isCameraLike("us.zoom.videomeetings"));
    }

    @Test
    public void subProcessOfCameraAppCounts() {
        assertTrue(CameraAppPolicy.isCameraLike("com.miui.camera:remote"));
        assertTrue(CameraAppPolicy.isCameraLike("com.android.camera:capture"));
    }

    @Test
    public void ordinaryAppsAreNotCameraLike() {
        assertFalse(CameraAppPolicy.isCameraLike("com.tencent.mm"));
        assertFalse(CameraAppPolicy.isCameraLike("com.android.settings"));
        assertFalse(CameraAppPolicy.isCameraLike("io.github.wumisc.safewalk"));
        // 只是名字里带 camera 的第三方应用不能被误伤
        assertFalse(CameraAppPolicy.isCameraLike("com.example.cameraplus"));
    }

    @Test
    public void emptyAndNullAreSafe() {
        assertFalse(CameraAppPolicy.isCameraLike(null));
        assertFalse(CameraAppPolicy.isCameraLike(""));
    }

    /**
     * P2 补的包名：每个都是「打开后会独占摄像头」的真·相机。
     *
     * 三星那条尤其重要 —— 旧表写的是 `com.samsung.android.camera`，而三星实际用的
     * 是 `com.sec.android.app.camera`。写错的效果是「三星用户开相机，小窗照样盖在
     * 取景框上」，且日志里一切正常，极难排查。
     */
    @Test
    public void oemCamerasAddedInP2AreDetected() {
        assertTrue("三星主包名", CameraAppPolicy.isCameraLike("com.sec.android.app.camera"));
        assertTrue("AOSP 第二个真实包名", CameraAppPolicy.isCameraLike("com.android.camera2"));
        assertTrue("一加氧 OS", CameraAppPolicy.isCameraLike("com.oneplus.camera"));
        assertTrue("Moto", CameraAppPolicy.isCameraLike("com.motorola.camera2"));
        assertTrue("Moto 新机", CameraAppPolicy.isCameraLike("com.motorola.camera3"));
        assertTrue("索尼", CameraAppPolicy.isCameraLike("com.sonyericsson.android.camera"));
        assertTrue("华硕", CameraAppPolicy.isCameraLike("com.asus.camera"));
    }

    /**
     * 社交类不能进表：它们只在视频通话时占用相机，平时刷消息收起小窗属于误伤。
     * 这条测试是给「顺手把微信加进去」的改动设的闸。
     */
    @Test
    public void socialAppsStayOutEvenThoughTheyCanUseCamera() {
        assertFalse(CameraAppPolicy.isCameraLike("com.tencent.mm"));
        assertFalse(CameraAppPolicy.isCameraLike("com.whatsapp"));
        assertFalse(CameraAppPolicy.isCameraLike("org.telegram.messenger"));
    }
}
