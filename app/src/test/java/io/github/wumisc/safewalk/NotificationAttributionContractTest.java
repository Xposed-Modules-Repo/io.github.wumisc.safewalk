package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * 【5.6.16 上岛归属】通知必须由本应用包名发出 + 焦点参数不得被模块判断砍掉。
 *
 * <p>根因：Hook 跑在 SystemUI 进程里，用它的 Context 发通知，notification 的
 * package 就是 `com.android.systemui`。系统界面自己的通知会被系统压制，超级岛
 * （和「超级岛增强」模块）也不会把它算成本应用的岛 —— 用户侧就是
 * 「只有前台服务那条上岛，提醒一条都没有」。
 *
 * <p>锁四条：
 * ① Hook 不得再直接 new 通知管理器 / 直接 notify；
 * ② Hook 必须通过 ACTION_HOOK_NOTIFY 委托 App；
 * ③ App 必须注册该 action 并按类型分发；
 * ④ 焦点参数必须无条件下发（模块的「破解焦点白名单」一开，原生参数是真生效的）。
 */
public class NotificationAttributionContractTest {

    private static final String HOOK = "src/main/java/io/github/wumisc/safewalk/SafeWalkLSPHook.kt";
    private static final String SERVICE = "src/main/java/io/github/wumisc/safewalk/WalkPreviewService.java";
    private static final String NM = "src/main/java/io/github/wumisc/safewalk/SafeWalkNotificationManager.kt";

    private static String read(String rel) throws IOException {
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(Paths.get(rel));
        candidates.add(Paths.get("app", rel));
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isEmpty()) {
            Path cwd = Paths.get(userDir);
            candidates.add(cwd.resolve(rel));
            candidates.add(cwd.resolve("app").resolve(rel));
            Path parent = cwd.getParent();
            if (parent != null) {
                candidates.add(parent.resolve("app").resolve(rel));
                candidates.add(parent.resolve(rel));
            }
        }
        for (Path p : candidates) {
            if (Files.isRegularFile(p) && Files.isReadable(p)) {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("找不到 " + rel);
    }

    @Test
    public void hookNeverPostsNotificationItself() throws IOException {
        String hook = read(HOOK);
        assertFalse("Hook 不得再自己 new 通知管理器（包名会变成 com.android.systemui）",
                hook.contains("SafeWalkNotificationManager("));
    }

    @Test
    public void hookDelegatesToApp() throws IOException {
        String hook = read(HOOK);
        assertTrue("必须有委托入口", hook.contains("private fun notifyApp(kind: String)"));
        assertTrue("必须发 ACTION_HOOK_NOTIFY", hook.contains("WalkPreviewService.ACTION_HOOK_NOTIFY"));
        assertTrue("委托必须带令牌（跨进程防伪造）",
                hook.contains("Intent(WalkPreviewService.ACTION_HOOK_NOTIFY)"));
    }

    @Test
    public void appReceivesAndDispatches() throws IOException {
        String svc = read(SERVICE);
        assertTrue("必须注册委托 action", svc.contains("f.addAction(WalkPreviewService.ACTION_HOOK_NOTIFY)"));
        assertTrue("必须有分发方法", svc.contains("private void handleHookNotify(String kind)"));
        assertTrue("久坐必须能分发", svc.contains("case NOTIFY_SIT:"));
        assertTrue("电量必须能分发", svc.contains("case NOTIFY_BATTERY:"));
        assertTrue("夜间照明必须能分发", svc.contains("case NOTIFY_TORCH:"));
    }

    @Test
    public void focusParamNeverGatedByModulePresence() throws IOException {
        String nm = read(NM);
        assertTrue("原生焦点参数必须无条件下发",
                nm.contains("putString(\"miui.focus.param\", FocusNotifyParam.json(title, msg, islandTag(title)))"));
        assertFalse("不得因检测到增强模块就不下发焦点参数（破解白名单后它才生效）",
                nm.contains("if (!HyperIslandCompat.installed"));
    }

    @Test
    public void torchLitNoticeExposed() throws IOException {
        String nm = read(NM);
        assertTrue("必须有「已点亮」通知", nm.contains("fun torchLitNotice()"));
        assertTrue("必须有撤销入口（灯灭/收窗）", nm.contains("fun cancelTorchNotice()"));
        assertTrue("关灯按钮必须走 OFF 动作",
                nm.contains("io.github.wumisc.safewalk.torch.ACTION_APP_TORCH_OFF"));
    }
}
