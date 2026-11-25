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
 * 「升级 / 覆盖安装即清空重建日志」源码契约（5.6.9 起，5.6.15 扩到同版本覆盖安装）。
 *
 * <p>用户口径（已确认）：**配置一律不动，只清日志；每次版本更新都执行**，
 * 5.6.15 追加一条：**同版本号覆盖安装也要清空并重建**。
 * 锁六条：
 * ① 版本 key + 两个安装指纹 key，只写这几个 key（不得顺手 clear() 整个 SP）；
 * ② 只在 App 进程执行（Hook 在 SystemUI 里读不到本应用 SP，也不该清用户文件）；
 * ③ 决策委托纯策略 [LogResetPolicy]，不再只看 versionCode；
 * ④ 清空必须同时清内存环形缓冲（否则「回传日志」仍带出旧内容），并写新头重建文件；
 * ⑤ init 与「目录切回用户路径」两处都接了判定（降级目录里清过 ≠ 用户目录也干净）；
 * ⑥ 整个流程 try/catch，清失败不能把日志功能弄崩。
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

    /** Hook 源码读取（复位广播的接收侧必须存在，否则 Hook 日志会写进已删除的文件） */
    private static String readSource(String rel) throws IOException {
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(Paths.get(rel));
        candidates.add(Paths.get("app", rel));
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isEmpty()) {
            Path cwd = Paths.get(userDir);
            candidates.add(cwd.resolve(rel));
            candidates.add(cwd.resolve("app").resolve(rel));
        }
        for (Path p : candidates) {
            if (Files.isRegularFile(p) && Files.isReadable(p)) {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("找不到 " + rel);
    }

    @Test
    public void onlyOneSpKeyWritten_neverClearsPrefs() throws IOException {
        String b = body();
        assertTrue("必须记录版本号", b.contains("putInt(SP_KEY_LOG_VER, cur)"));
        assertTrue("必须记录安装指纹（同版本覆盖安装靠它识别）", b.contains("SP_KEY_LOG_STAMP"));
        assertTrue("必须记录 APK 字节数", b.contains("SP_KEY_LOG_APK"));
        assertFalse("绝不允许 clear() 整个 SP（配置不动是硬约束）",
                b.contains("edit().clear()") || b.contains("sp.clear()"));
        assertFalse("不得 remove 其他 key", b.contains(".remove("));
        assertTrue("环形缓冲的 clear 只允许作用于 ring", b.contains("ring.clear()"));
    }

    @Test
    public void appProcessOnly() throws IOException {
        assertTrue("必须限定 App 进程（Hook 不得清用户文件）",
                body().contains("ctx.packageName != BuildConfig.APPLICATION_ID"));
    }

    @Test
    public void sameVersionEarlyReturn_changedVersionDeletesAndHeaders() throws IOException {
        String b = body();
        assertTrue("决策必须委托纯策略（不再只看 versionCode）", b.contains("LogResetPolicy.decide("));
        assertFalse("不得再用「版本一致就早退」的旧写法（那样同版本覆盖安装永远不清）",
                b.contains("if (old == cur) return"));
        assertTrue("无需清空时必须早退", b.contains("if (!d.reset)"));
        assertTrue("必须删旧文件", b.contains("logFile?.delete()"));
        assertTrue("必须写升级头", b.contains("旧版本日志已清空"));
        assertTrue("删文件前必须关流（FUSE 上删已打开文件会失败）",
                b.indexOf("closeOut()") < b.indexOf("logFile?.delete()"));
    }

    @Test
    public void ringBufferClearedAndFileRebuilt() throws IOException {
        String b = body();
        assertTrue("内存环形缓冲必须一起清（否则回传日志仍带旧内容）", b.contains("ring.clear()"));
        assertTrue("清完必须重建文件头（appendLine 会按需重开流）", b.contains("appendLine("));
        assertTrue("删文件后、写头前必须是清 ring 的位置",
                b.indexOf("logFile?.delete()") < b.indexOf("ring.clear()"));
        assertTrue("写头必须在清 ring 之后", b.indexOf("ring.clear()") < b.indexOf("appendLine("));
    }

    @Test
    public void hookSideStreamAlsoReleased() throws IOException {
        String b = body();
        assertTrue("清空后必须通知 Hook 释放旧文件流（否则 Hook 日志写进已删除的 inode）",
                b.contains("notifyHookReset("));
        if (src == null) src = read();
        int reset = src.indexOf("fun resetForRebuild(");
        assertTrue("必须有 Hook 侧的流复位入口", reset >= 0);
        String resetBody = src.substring(reset, Math.min(src.length(), reset + 700));
        assertTrue("复位必须关流", resetBody.contains("closeOut()"));
        assertTrue("复位必须清 ring（Hook 的 snapshot 也是回传数据源）",
                resetBody.contains("ring.clear()"));
        int hook = readSource("src/main/java/io/github/wumisc/safewalk/SafeWalkLSPHook.kt")
                .indexOf("DiagLog.ACTION_LOG_RESET");
        assertTrue("Hook 必须真的监听复位广播", hook >= 0);
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
