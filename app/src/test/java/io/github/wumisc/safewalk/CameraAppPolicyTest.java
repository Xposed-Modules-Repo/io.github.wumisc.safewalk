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
}
