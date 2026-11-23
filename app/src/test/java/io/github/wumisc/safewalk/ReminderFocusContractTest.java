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
 * 【5.6.12 提醒上岛】接线契约（纯 JVM 读源码）。
 *
 * 锁：① baseBuilder 用 putString 写正确的焦点 JSON（旧 putBundle 写法已删除）；
 * ② 夜间照明岛内按钮接线齐全；③ SOS（CrashAlertReceiver）不接焦点，保持原样。
 */
public class ReminderFocusContractTest {

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

    private static final String NM = "src/main/java/io/github/wumisc/safewalk/SafeWalkNotificationManager.kt";
    private static final String SOS = "src/main/java/io/github/wumisc/safewalk/CrashAlertReceiver.java";

    @Test
    public void baseBuilderWritesJsonStringNotBundle() throws IOException {
        String s = read(NM);
        assertTrue("必须 putString 焦点 JSON",
                s.contains("putString(\"miui.focus.param\", FocusNotifyParam.json(title, msg, islandTag(title)))"));
        assertFalse("旧 Bundle 写法必须删除", s.contains("putBundle(\"miui.focus.param\""));
        assertFalse("旧 template 字段必须删除", s.contains("putString(\"miui.focus.template\""));
        assertTrue("必须有岛短标签归类", s.contains("private fun islandTag(title: String)"));
    }

    @Test
    public void flashlightIslandButtonWired() throws IOException {
        String s = read(NM);
        assertTrue("必须有带按钮的 notify 重载", s.contains("private fun notifyWithActions("));
        assertTrue("夜间照明必须走带按钮通知", s.contains("notifyWithActions(ID_TORCH, b, focusJson, focusActions)"));
        assertTrue("岛内按钮 key 必须挂 Bundle", s.contains("putParcelable(\"miui.focus.action_torch\""));
        assertTrue("JSON 必须含同名 action", s.contains("actions = listOf(\"miui.focus.action_torch\")"));
    }

    @Test
    public void sosUntouched_noFocusParam() throws IOException {
        assertFalse("SOS 不接焦点通知（安全攸关，保持原样）",
                read(SOS).contains("miui.focus.param"));
    }
}