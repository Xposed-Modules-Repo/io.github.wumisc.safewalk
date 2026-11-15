package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「重启系统界面」提醒的策略单测。
 *
 * 为什么这种东西也要测：这段逻辑决定「什么时候打扰用户」，而打扰的成本很高 ——
 * 每次都能弹的提醒，几次之后用户就形成「哦又来了，点掉」的条件反射。
 *
 * 5.3.2 把判定从「是否首次安装」换成「versionCode 是否变了」：升级安装同样要重启
 * SystemUI，而旧判定在升级时一条提示都不给（真机上已经因此误判过两轮「修了没生效」）。
 * 所以这组断言守的是两个方向：
 *  · 版本变了必须提示（否则升级等于白装）；
 *  · 同一版本确认过之后绝不重复提示（否则就是狼来了）。
 */
public class RestartReminderPolicyTest {

    @Test
    public void promptsWhenVersionIsNewer() {
        // 首次安装：上次确认值 0，当前 57
        assertTrue("首次安装 → 必须提示",
                RestartReminderPolicy.shouldPrompt(57, 0));
        // 覆盖升级：56 → 57
        assertTrue("升级 → 必须提示，否则跑的还是旧代码",
                RestartReminderPolicy.shouldPrompt(57, 56));
        // 同一版本又打开一次：不提示
        assertFalse("同版本已确认 → 不再提示",
                RestartReminderPolicy.shouldPrompt(57, 57));
        // 降级安装（用户回滚）：不提示，代码确实变旧了，但这不是「装了新的没生效」
        assertFalse(RestartReminderPolicy.shouldPrompt(56, 57));
    }

    @Test
    public void firstInstallIsDistinguishedFromUpdate() {
        assertTrue("上次确认为 0 就是首次安装",
                RestartReminderPolicy.isFirstInstall(0));
        assertFalse("确认过任意版本就不是首次安装",
                RestartReminderPolicy.isFirstInstall(56));
        // 两种场景的标题必须不同：用户能一眼看出「我这是刚装」还是「我刚更新」
        assertTrue(RestartReminderPolicy.title(true).contains("首次安装"));
        assertTrue(RestartReminderPolicy.title(false).contains("更新"));
    }

    @Test
    public void bodyExplainsWhyNothingHappens() {
        String withRoot = RestartReminderPolicy.body(true, true);
        String noRoot = RestartReminderPolicy.body(false, true);

        // 必须点明「跑在系统界面进程」这个事实，否则用户只会觉得是 bug
        assertTrue(withRoot.contains("系统界面"));
        assertTrue(noRoot.contains("系统界面"));
        // 没有 root 时要把「手动重启手机」这条退路写清楚，不能承诺按钮必然生效
        assertTrue("无 root 时需要给出兜底办法", noRoot.contains("手动重启手机"));
        assertTrue("有 root 时不该提手动重启", !withRoot.contains("手动重启手机"));
    }

    @Test
    public void updateBodyPointsAtStaleCodeNotMissingModule() {
        // 升级场景和首次安装的归因不同：模块早就加载过了，问题是「跑的是旧类」。
        // 文案写错会把人引到「去 LSPosed 勾作用域」这条错路上。
        String update = RestartReminderPolicy.body(true, false);
        assertTrue("升级文案要说明进程里是旧版本",
                update.contains("旧") || update.contains("上一个版本"));
        assertTrue(update.contains("系统界面"));
    }

    @Test
    public void neutralBodyWorksWithoutRootProbe() {
        String s = RestartReminderPolicy.bodyNeutral();
        assertTrue(s.contains("系统界面"));
        assertTrue("首页提示要给手动兜底", s.contains("手动重启手机"));
    }

    @Test
    public void failureTextIsActionable() {
        String f = RestartReminderPolicy.failureText();
        // 失败时给的是「去哪儿检查」，不是一句「失败了」
        assertTrue(f.contains("LSPosed"));
        assertTrue(f.contains("作用域"));
        assertTrue(f.contains("重启手机"));
    }

    @Test
    public void skipWarningStatesTheConsequence() {
        String w = RestartReminderPolicy.skipWarning();
        // 二次确认不能只写「还没重启」，要说清「不重启会怎样」
        assertTrue(w.contains("不会生效") || w.contains("可能不会生效"));
    }

    @Test
    public void successTextDistinguishesChannels() {
        // 两条通道耗时不同，说清楚用户才不会以为「卡住了」
        assertTrue(RestartReminderPolicy.successText(true).contains("root"));
        assertFalse(RestartReminderPolicy.successText(false).contains("root"));
    }

    @Test
    public void allTextsAreNonEmpty() {
        assertEquals("重启系统界面", RestartReminderPolicy.actionText());
        assertFalse(RestartReminderPolicy.title(true).isEmpty());
        assertFalse(RestartReminderPolicy.title(false).isEmpty());
        assertFalse(RestartReminderPolicy.pendingText(true).isEmpty());
        assertFalse(RestartReminderPolicy.pendingText(false).isEmpty());
        assertFalse(RestartReminderPolicy.laterText().isEmpty());
    }

    @Test
    public void describeExposesBothVersions() {
        // 下次回传日志，一行就能判断拦到的代码是不是新版
        String s = RestartReminderPolicy.describe(57, 56);
        assertTrue(s.contains("57"));
        assertTrue(s.contains("56"));
        assertTrue(s.contains("需要重启"));
        assertTrue(RestartReminderPolicy.describe(57, 57).contains("已确认重启"));
    }

    @Test
    public void ackKeyIsStable() {
        // key 一旦改名，用户手机里那条「已确认」版本号就失联 → 又被提示一次
        assertEquals("restartSuiAckedVersionCode", RestartReminderPolicy.KEY_ACK_VERSION);
        assertEquals("safewalk", RestartReminderPolicy.SP_ONEOFF);
    }
}
