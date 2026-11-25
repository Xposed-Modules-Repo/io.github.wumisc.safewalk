package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 预览服务稳定性契约。
 *
 * 这几条都是沙盒里真实踩出来的坑，不是推测：
 * 1. 每次 startForegroundService 都必须有对应的 startForeground，否则 30 秒后
 *    data_app_crash 记录 FGS 超时，整个 App 进程被杀；
 * 2. 用户刚拨开行走模式时，看门狗不能拿旧心跳判死，否则会和用户自己的启动
 *    抢跑，制造第 1 条的场景；
 * 3. 启动前必须先告诉 Hook「我要起了」，把心跳基线挪到当前；
 * 4. 相机分步日志只能由 Overlay 写一次，服务侧重复写会让每条日志翻倍。
 */
@RunWith(JUnit4.class)
public class PreviewServiceStabilityContractTest {

    private static final String SRC = "src/main/java/io/github/wumisc/safewalk";
    private static final String LAYOUT = "src/main/res/layout";

    private static String read(String rel) throws Exception {
        File f = new File(rel);
        assertTrue("缺少文件: " + rel, f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String slice(String text, String from, String to) {
        int a = text.indexOf(from);
        assertTrue("找不到片段起点: " + from, a >= 0);
        int b = text.indexOf(to, a + from.length());
        assertTrue("找不到片段终点: " + to, b > a);
        return text.substring(a, b);
    }

    @Test
    public void everyStartCommandReforwardsForeground() throws Exception {
        String svc = read(SRC + "/WalkPreviewService.java");
        String onStart = slice(svc,
                "public int onStartCommand(@Nullable Intent intent",
                "// 服务起来了：磁贴副标题");
        assertTrue("裸启动（startForegroundService）必须强制补一次 startForeground；"
                        + "服务已在前台时的重复 start 也曾让系统等 30 秒后杀进程",
                onStart.contains("boolean needsForeground = intent == null || intent.getAction() == null"));
        assertTrue("onStartCommand 必须把 needsForeground 传给 goForeground",
                onStart.contains("goForeground(needsForeground)"));
        assertFalse("不能退回无条件跳过的旧写法",
                onStart.contains("goForeground();"));
    }

    @Test
    public void forcedForegroundCanAnswerDuplicateStart() throws Exception {
        String svc = read(SRC + "/WalkPreviewService.java");
        String go = slice(svc,
                "private void goForeground(boolean force)",
                "private static final int[] FGS_TYPE_CHAIN");
        assertTrue("必须缓存上一条前台通知，重复 start 时才能低成本补提",
                go.contains("lastFgsNotification"));
        assertTrue("force 分支必须真的调用 startForeground",
                go.contains("startForeground(NOTIF_ID, lastFgsNotification"));
        assertTrue("补提失败要降级完整重建，不能把这次 start 吞掉",
                go.contains("foregroundEstablished = false"));
    }

    @Test
    public void startPingsWatchdogBeforeRequestingService() throws Exception {
        String svc = read(SRC + "/WalkPreviewService.java");
        String start = slice(svc,
                "public static void start(Context ctx, boolean fromRevive)",
                "public static void send(Context ctx");
        int ping = start.indexOf("pingHookStarting(ctx)");
        int request = start.indexOf("startForegroundService(i)");
        assertTrue("启动前必须先 ping Hook", ping >= 0);
        assertTrue("ping 必须早于 startForegroundService", request > ping);
        assertTrue("ping 走与心跳相同的签名权限通道",
                start.contains("KeepAlivePolicy.ACTION_HEARTBEAT"));
    }

    @Test
    public void watchdogResetsBaselineWhenUserTurnsWalkModeOn() throws Exception {
        String hook = read(SRC + "/SafeWalkLSPHook.kt");
        assertTrue("看门狗必须识别「上一拍关、这一拍开」",
                hook.contains("KeepAlivePolicy.resetBaselineOnStart(lastWatchdogDesired, desired)"));
        assertTrue("识别到刚开启时，必须把心跳基线挪到此刻",
                hook.contains("lastHeartbeatAt = now"));
        assertTrue("日志要能看出是「刚开启重置」而不是真失联",
                hook.contains("看门狗重置心跳基线"));
        String offBranch = slice(hook,
                "if (!masterEnabled() || !pref(SafeWalkPrefKey.enableRearCamera, false))",
                "sendOverlayCmd(false)");
        assertTrue("关闭预览的早退分支必须把期望状态记成 false；"
                        + "否则用户隔几分钟再打开时，看门狗会把它当成「一直开着」拿旧心跳判死",
                offBranch.contains("lastWatchdogDesired = false"));
    }

    @Test
    public void cameraStageIsLoggedOnlyOnce() throws Exception {
        String svc = read(SRC + "/WalkPreviewService.java");
        assertFalse("Overlay 已写一次 [cam] 分步日志，服务侧再写会整条翻倍",
                svc.contains("DiagLog.log(\"[cam] \" + step)"));
    }

    @Test
    public void walkModeDoesNotDoubleStartRunningService() throws Exception {
        String act = read(SRC + "/WalkModeActivity.java");
        String method = slice(act,
                "private void startPreviewService(String mode)",
                "// ========== 权限申请 ==========");
        assertTrue("服务已在跑时只发 SHOW，不再重复 startForegroundService",
                method.contains("if (WalkPreviewService.isAlive())"));
        assertTrue("服务没跑时才裸启动", method.contains("WalkPreviewService.start(this)"));
        assertFalse("不能退回「无条件 start + SHOW」：两条裸 start 会触发两次 doShow/两次开相机",
                method.contains("WalkPreviewService.start(this);\n        WalkPreviewService.send(this, WalkPreviewService.ACTION_SHOW, mode);"));
    }

    @Test
    public void generatedQrIsBroughtIntoView() throws Exception {
        String activity = read(SRC + "/EmergencyCardActivity.java");
        String layout = read(LAYOUT + "/activity_emergency.xml");
        assertTrue("滚动容器必须有 id，代码才能滚到二维码",
                layout.contains("android:id=\"@+id/sv_emergency\""));
        assertTrue("生成二维码后必须滚动到它，否则用户点完看不到任何反馈",
                activity.contains("smoothScrollTo(0, Math.max(0, ivQr.getTop() - 24))"));
    }
}
