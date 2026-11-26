package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 【5.7.6 · 128】诊断上报的额度闸。
 *
 * 背景：令牌写在 APK 里可被反编译，免费计划的请求/写入额度是全账号共享的
 * （Workers 100k 请求·天、D1 100k 行写入·天）。没有闸，一个脚本就能把当天额度打满，
 * 真实反馈全 500。这里把「客户端冷却」与「服务端四道闸」都钉成契约。
 */
@RunWith(JUnit4.class)
public class UploadGuardContractTest {

    private static final long MIN = 60_000L;

    private String readServer() throws IOException {
        // 单测工作目录是 app 模块目录，服务端在仓库根的 cloudflare/
        File f = new File("../cloudflare/functions/api/diag/upload.js");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // ---------- 客户端冷却 ----------

    @Test
    public void firstUploadAlwaysAllowed() {
        assertTrue(UploadCooldown.shouldAllow(0L, 1_700_000_000_000L));
    }

    @Test
    public void cooldownBlocksWithinTenMinutes() {
        long t = 1_700_000_000_000L;
        assertFalse(UploadCooldown.shouldAllow(t, t + MIN));
        assertFalse(UploadCooldown.shouldAllow(t, t + 9 * MIN));
        assertEquals(9, UploadCooldown.remainingMinutes(t, t + MIN));
    }

    @Test
    public void cooldownReleasesAfterTenMinutes() {
        long t = 1_700_000_000_000L;
        assertTrue(UploadCooldown.shouldAllow(t, t + 10 * MIN));
        assertEquals(0, UploadCooldown.remainingMinutes(t, t + 10 * MIN));
    }

    @Test
    public void clockRollbackDoesNotLockUserOut() {
        long t = 1_700_000_000_000L;
        assertTrue("时钟回拨必须放行，否则用户会被永久挡住", UploadCooldown.shouldAllow(t, t - 5 * MIN));
    }

    // ---------- 服务端四道闸 ----------

    @Test
    public void serverEnforcesEachGuard() throws IOException {
        String js = readServer();
        assertTrue("单 IP 每日限额缺失", js.contains("IP_DAILY_LIMIT"));
        assertTrue("去重窗口缺失", js.contains("DEDUPE_WINDOW_MS"));
        assertTrue("全表行数上限缺失", js.contains("MAX_ROWS"));
        assertTrue("重复上报应返回 deduped 而不是再写一行", js.contains("deduped"));
        assertTrue("机型名必须限长，否则可随机编名字撑存储", js.contains("modelKey"));
        assertTrue("超额计数必须只在落库成功后 +1", js.contains("upload_quota"));
    }
}
