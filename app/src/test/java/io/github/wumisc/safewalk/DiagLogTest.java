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
}
