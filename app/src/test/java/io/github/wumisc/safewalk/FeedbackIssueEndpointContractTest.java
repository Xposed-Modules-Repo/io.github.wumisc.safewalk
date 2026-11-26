package io.github.wumisc.safewalk;

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
 * 【5.7.6 · 126】反馈通道契约。
 *
 * 原来「功能建议」直接拼 Xposed-Modules-Repo/io.github.wumisc.safewalk/issues/new，
 * 而该组织（LSPosed 官方镜像）把 Issues 统一关闭，实测：
 *   · gh api repos/Xposed-Modules-Repo/io.github.wumisc.safewalk → has_issues=false
 *   · GET .../issues/new → 404
 * 用户写完一屏建议点提交，只能落到 GitHub 的错误页。
 *
 * 现在改指个人反馈仓库（Issues 常开）。两条断言锁住：不能再指回镜像组织，
 * 也不能把地址写死成任意别的仓库。如果哪天换仓库，先改这里再改代码。
 */
@RunWith(JUnit4.class)
public class FeedbackIssueEndpointContractTest {

    private static final String ISSUE_ORG_REPO =
            "https://github.com/Xposed-Modules-Repo/io.github.wumisc.safewalk/issues/new";
    private static final String FEEDBACK_REPO =
            "https://github.com/wumisc/safewalk-feedback/issues/new";

    private String readFeedbackActivity() throws IOException {
        File f = new File("src/main/java/io/github/wumisc/safewalk/FeedbackActivity.java");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void issueEndpoint_pointsToFeedbackRepo() throws IOException {
        String src = readFeedbackActivity();
        assertTrue("功能建议必须指向反馈仓库 " + FEEDBACK_REPO, src.contains(FEEDBACK_REPO));
        assertFalse("不能再指向镜像组织的 issues/new（该组织已关闭 Issues，页面 404）",
                src.contains(ISSUE_ORG_REPO));
    }

    @Test
    public void copyFallbackStillOffered() throws IOException {
        String src = readFeedbackActivity();
        assertTrue("打不开浏览器时必须回退到复制", src.contains("copy()"));
        assertTrue("复制路径要在剪贴板不可用时给出提示", src.contains("ClipboardManager"));
    }
}
