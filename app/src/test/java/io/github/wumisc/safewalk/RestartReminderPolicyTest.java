package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「首次安装 → 重启系统界面」提醒的策略单测。
 *
 * 为什么这种东西也要测：这段逻辑决定「什么时候打扰用户」，而打扰的成本很高 ——
 * 老用户每次更新都被提醒一次「请重启系统界面」，几次之后他就会形成
 * 「哦又来了，点掉」的条件反射，真正需要重启（改了作用域）时反而没人当回事。
 * 所以「只对首次安装显示」这条规则必须有断言守者。
 */
public class RestartReminderPolicyTest {

    @Test
    public void promptsOnlyOnFirstInstall() {
        assertTrue("首次安装且没处理过 → 必须提示",
                RestartReminderPolicy.shouldPrompt(true, false));
        assertFalse("首次安装但已经重启过 → 不再提示",
                RestartReminderPolicy.shouldPrompt(true, true));
        assertFalse("老用户（升级）→ 绝不提示，否则就是狼来了",
                RestartReminderPolicy.shouldPrompt(false, false));
        assertFalse(RestartReminderPolicy.shouldPrompt(false, true));
    }

    @Test
    public void bodyExplainsWhyNothingHappens() {
        String withRoot = RestartReminderPolicy.body(true);
        String noRoot = RestartReminderPolicy.body(false);

        // 必须点明「跑在系统界面进程」这个事实，否则用户只会觉得是 bug
        assertTrue(withRoot.contains("系统界面"));
        assertTrue(noRoot.contains("系统界面"));
        // 没有 root 时要把「手动重启手机」这条退路写清楚，不能承诺按钮必然生效
        assertTrue("无 root 时需要给出兜底办法", noRoot.contains("手动重启手机"));
        assertTrue("有 root 时不该提手动重启", !withRoot.contains("手动重启手机"));
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
        assertFalse(RestartReminderPolicy.title().isEmpty());
        assertFalse(RestartReminderPolicy.pendingText().isEmpty());
        assertFalse(RestartReminderPolicy.laterText().isEmpty());
    }

    @Test
    public void ackKeyIsStable() {
        // key 一旦改名，老用户手机里那条「已重启」标记就失联 → 又被提示一次
        assertEquals("restartSuiAcknowledged", RestartReminderPolicy.KEY_ACK);
        assertEquals("safewalk", RestartReminderPolicy.SP_ONEOFF);
    }
}
