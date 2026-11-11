package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 日志落盘测试。
 *
 * 存在的理由：上一版靠 `File.canWrite()` 选目录，在分区存储下会误报 true，
 * 于是日志一条都写不出来，用户按路径去找永远是空的，而所有测试都是绿的。
 * 这里锁住两件事：
 * ① init 之后必须真的选出一个**可写**目录（探针写入成功）；
 * ② log() 的内容必须能被 snapshot() 读回来（回传给 App 依赖它）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DiagLogTest {

    @Test
    public void init_picksAWritableDirectory() {
        Context ctx = ApplicationProvider.getApplicationContext();
        DiagLog.init(ctx);

        String status = DiagLog.status();
        assertFalse("日志目录没解析出来: " + status, status.startsWith("未落盘"));
        assertTrue("日志文件路径为空", DiagLog.logFilePath().length() > 0);
    }

    @Test
    public void log_isVisibleInSnapshot() {
        Context ctx = ApplicationProvider.getApplicationContext();
        DiagLog.init(ctx);

        String marker = "单测写入标记-" + System.nanoTime();
        DiagLog.log(marker);

        String snap = DiagLog.snapshot();
        assertTrue("snapshot 里找不到刚写的日志", snap.contains(marker));
        assertTrue("snapshot 缺少进程头信息", snap.contains("pid="));
    }

    /**
     * 统一节流出口：真机 19:11:38 一秒内 80+ 条同原因日志，就是缺了这一层。
     * 用唯一 key 隔离，避免单例状态在测试间串味；不依赖精确时钟，只验证
     * 「首条发出、窗口内第二条被吞、reset 后又能发、不同 key 互不干扰」。
     */
    @Test
    public void logThrottled_suppressesDuplicateAndResetsByKey() {
        Context ctx = ApplicationProvider.getApplicationContext();
        DiagLog.init(ctx);

        String key = "test.throttle." + System.nanoTime();
        String first = "throttle-first-" + System.nanoTime();
        String duplicate = "throttle-duplicate-" + System.nanoTime();
        String afterReset = "throttle-after-reset-" + System.nanoTime();
        String otherKey = "throttle-other-" + System.nanoTime();

        DiagLog.logThrottled(key, "reason", first, 60_000L, 60_000L);
        DiagLog.logThrottled(key, "reason", duplicate, 60_000L, 60_000L);
        // 另一个 key 首条必须照常发出
        DiagLog.logThrottled(key + ".other", "reason", otherKey, 60_000L, 60_000L);

        String snap = DiagLog.snapshot();
        assertTrue("首条被吞了", snap.contains(first));
        assertFalse("窗口内同原因重复日志没被节住", snap.contains(duplicate));
        assertTrue("不同 key 被误伤", snap.contains(otherKey));

        DiagLog.resetThrottle(key);
        DiagLog.logThrottled(key, "reason", afterReset, 60_000L, 60_000L);
        assertTrue("reset 后仍发不出", DiagLog.snapshot().contains(afterReset));
    }
}
