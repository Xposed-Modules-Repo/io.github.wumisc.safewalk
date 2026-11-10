package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.WindowManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * 锁屏「后视画面打不开」误报的回归测试。
 *
 * 复现路径：HyperOS 锁屏/灭屏时策略性禁用相机
 * （openCamera 抛 CAMERA_DISABLED 或会话回调 ERROR_CAMERA_DISABLED）。
 * 旧实现把它当不可恢复故障 → hide + reportFailure → 弹常驻通知 + 5 分钟封禁，
 * 且 reportFailure 给所有原因都硬套「6 秒内没有收到画面」，
 * 于是日志里明明已有 ✅ 已收到画面，用户却在锁屏上看到打不开的通知。
 *
 * 这三条分别锁住修复后的行为：策略禁用静默、非超时原因不带超时说明、
 * 真正的看门狗超时仍保留诊断说明。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PolicyDisabledNoFalseAlarmTest {

    private final List<String> failures = new ArrayList<>();
    private final List<String> stages = new ArrayList<>();
    private RearCameraOverlay overlay;

    private void build() {
        failures.clear();
        stages.clear();
        Context ctx = ApplicationProvider.getApplicationContext();
        WindowManager wm = (WindowManager) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{WindowManager.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return null;
                    }
                });
        Function1<String, Unit> onFailure = s -> {
            failures.add(s);
            return Unit.INSTANCE;
        };
        Function1<String, Unit> onStage = s -> {
            stages.add(s);
            return Unit.INSTANCE;
        };
        overlay = new RearCameraOverlay(ctx, onFailure, onStage, wm);
    }

    private void invokePrivate(String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = args[i].getClass();
        Method m = RearCameraOverlay.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        m.invoke(overlay, args);
    }

    @Test
    public void policyDisabled_neverRaisesFailureNotification() throws Exception {
        build();
        invokePrivate("handlePolicyDisabled", "onError(3)");
        assertTrue("策略禁用不应触发 onFailure（否则会弹「后视画面打不开」）",
                failures.isEmpty());
        assertFalse("不应再报「相机被系统或策略禁用」",
                stages.toString().contains("相机被系统或策略禁用"));
        assertTrue("应留下一条可诊断的暂时禁用回执",
                stages.toString().contains("暂时禁用相机"));
    }

    @Test
    public void permissionFailure_hasNoMisleadingFrameTimeoutSuffix() throws Exception {
        build();
        invokePrivate("reportFailure", "应用没有相机权限");
        assertEquals(1, failures.size());
        assertFalse("权限缺失不该写成「6 秒内没有收到画面」",
                stages.toString().contains("秒内没有收到画面"));
    }

    @Test
    public void watchdogFailure_keepsFrameTimeoutSuffix() throws Exception {
        build();
        invokePrivate("reportFailure", "相机没有返回画面");
        assertEquals(1, failures.size());
        assertTrue("真正的看门狗超时应保留原因说明",
                stages.toString().contains("6 秒内没有收到画面"));
    }
}
