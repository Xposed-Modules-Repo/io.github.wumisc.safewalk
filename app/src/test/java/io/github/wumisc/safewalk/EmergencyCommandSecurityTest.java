package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;

import android.content.BroadcastReceiver;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 本轮 bug 排查的回归测试：撞击广播的严格鉴权。
 *
 * 背景：撞击广播一旦被伪造，急救卡会走完 60 秒倒计时，真的给紧急联系人
 * 发短信并拨号。旧鉴权（isTrustedCommand）在「API 34+ 且发送方身份确定不可信」
 * 时只要令牌匹配仍会放行，而令牌是硬编码字符串、反编译即可获得。
 *
 * 这里锁定严格版的最低要求：令牌缺失 / 不匹配 / 期望值为空时必须拒绝，
 * 绝不能出现 fail-open。
 */
@RunWith(RobolectricTestRunner.class)
public class EmergencyCommandSecurityTest {

    private static final String TOKEN = SafeWalkPrefKey.BROADCAST_TOKEN;

    private BroadcastReceiver receiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
            }
        };
    }

    @Test
    public void strictCommand_rejectsMissingToken() {
        assertFalse("令牌缺失必须拒绝，否则任意应用都能伪造撞击",
                ReceiverCompat.isStrictCommand(receiver(), null, TOKEN));
    }

    @Test
    public void strictCommand_rejectsWrongToken() {
        assertFalse("令牌不匹配必须拒绝",
                ReceiverCompat.isStrictCommand(receiver(), "safewalk-bc-forged", TOKEN));
    }

    @Test
    public void strictCommand_rejectsWhenExpectedIsNull() {
        assertFalse("期望令牌为空时不能 fail-open",
                ReceiverCompat.isStrictCommand(receiver(), TOKEN, null));
    }

    @Test
    public void strictCommand_rejectsEmptyToken() {
        assertFalse("空字符串令牌必须拒绝（等同于伪造）",
                ReceiverCompat.isStrictCommand(receiver(), "", TOKEN));
    }
}
