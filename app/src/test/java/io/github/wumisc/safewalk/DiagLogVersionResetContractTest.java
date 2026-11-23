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
 * 「升级即清日志」源码契约（5.6.9）。
 *
 * <p>用户口径（已确认）：**配置一律不动，只清日志；每次版本更新都执行。**
 * 锁五条：
 * ① 有版本记录 key，且只写这一个 key（不得顺手 clear() 整个 SP）；
 * ② 只在 App 进程执行（Hook 在 SystemUI 里读不到本应用 SP，也不该清用户文件）；
 * ③ 版本一致早退、不一致删文件 + 写升级头；
 * ④ init 与「目录切回用户路径」两处都接了判定（降级目录里清过 ≠ 用户目录也干净）；
 * ⑤ 整个流程 try/catch，清失败不能把日志功能弄崩。
 */
public class DiagLogVersionResetContractTest {

    private static String src;

    private static String read() throws IOException {
        String rel = "src/main/java/io/github/wumisc/safewalk/DiagLog.kt";
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
        throw new IOException("找不到 DiagLog.kt");
    }

    private static String body() throws IOException {
        if (src == null) src = read();
        int i = src.indexOf("private fun clearOnVersionChangeLocked(");
        assertTrue("找不到 clearOnVersionChangeLocked", i >= 0);
        int end = src.indexOf("\n    private fun probe(", i);
        if (end < 0) end = Math.min(src.length(), i + 2_500);
        return src.substring(i, end);
    }

    @Test
    public void onlyOneSpKeyWritten_neverClearsPrefs() throws IOException {
        String b = body();
        assertTrue("必须记录版本号", b.contains("putInt(SP_KEY_LOG_VER, cur)"));
        assertFalse("绝不允许 clear() 整个 SP（配置不动是硬约束）", b.contains(".clear()"));
        assertFalse("不得 remove 其他 key", b.contains(".remove("));
    }

    @Test
    public void appProcessOnly() throws IOException {
        assertTrue("必须限定 App 进程（Hook 不得清用户文件）",
                body().contains("ctx.packageName != BuildConfig.APPLICATION_ID"));
    }

    @Test
    public void sameVersionEarlyReturn_changedVersionDeletesAndHeaders() throws IOException {
        String b = body();
        assertTrue("版本一致必须早退", b.contains("if (old == cur) return"));
        assertTrue("版本变化必须删旧文件", b.contains("logFile?.delete()"));
        assertTrue("必须写升级头", b.contains("旧版本日志已清空"));
        assertTrue("删文件前必须关流（FUSE 上删已打开文件会失败）",
                b.indexOf("closeOut()") < b.indexOf("logFile?.delete()"));
    }

    @Test
    public void wiredAtInitAndDirUpgrade() throws IOException {
        if (src == null) src = read();
        int init = src.indexOf("fun init(ctx: Context)");
        int initEnd = src.indexOf("private fun upgradeToUserDir()", init);
        String initBody = src.substring(init, initEnd);
        assertTrue("init 必须接版本判定", initBody.contains("clearOnVersionChangeLocked(app)"));
        int up = src.indexOf("private fun upgradeToUserDirLocked()");
        int upEnd = src.indexOf("private fun clearOnVersionChangeLocked(", up);
        String upBody = src.substring(up, upEnd);
        assertTrue("目录切换后必须补做判定",
                upBody.contains("if (!versionCleaned) appCtx?.let { clearOnVersionChangeLocked(it) }"));
    }

    @Test
    public void failureNeverBreaksLogging() throws IOException {
        String b = body();
        assertTrue("整个流程必须 try/catch 兜底", b.contains("catch (_: Throwable)"));
    }
}
