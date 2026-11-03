package io.github.wumisc.safewalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 纯 JVM 验证（不依赖 Robolectric / 网络下载）：
 * 锁死「自动跟随步行触发」这条关键路径 —— feedAcc 在 confirmSteps 下必须返回 true。
 *
 * 之前两个真实 bug 让预览永远不弹出：
 *  ① peakIntervals 上限只有 3，confirmSteps=5 时 size 永远到不了 5；
 *  ② 步频下限 0.9Hz 太严 + 断步清空太激进，慢速散步被当成断步每次清空。
 */
@RunWith(JUnit4.class)
public class GaitDetectorPureTest {

    private static final long FRAME_MS = 20L;   // 50Hz
    private static final float GRAVITY = 9.8f;  // 重力（竖直握持落在 X 轴）

    @Test
    public void slowWalk_confirmSteps5_feedAccTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 600; i++) { // 50Hz × 12s，足够散步攒满 5 步
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 0.9 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertTrue("0.9Hz 散步 + confirmSteps=5：feedAcc 必须在 12s 内返回 true，"
                + "否则预览永远不弹出（debug=" + g.debugInfo() + "）", triggered);
    }

    @Test
    public void normalWalk_confirmSteps2_feedAccTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(2);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 300; i++) {
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 1.5 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertTrue("1.5Hz + confirmSteps=2：feedAcc 必须触发（debug=" + g.debugInfo() + "）", triggered);
    }

    @Test
    public void legShake_confirmSteps5_feedAccNeverTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 400; i++) {
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 3.0 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertFalse("3Hz 抖腿 + confirmSteps=5：feedAcc 绝不能返回 true（防误触）", triggered);
    }

    @Test
    public void sittingStill_feedAccNeverTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 500; i++) {
            g.feedAcc(GRAVITY, 0f, 0f, t);
            t += FRAME_MS;
        }
        assertFalse("静止不动绝不能触发", triggered);
    }

    /** 拿/放/翻转手机的大瞬态（幅值远超步行峰值）：不计步、不得触发 */
    @Test
    public void bigTransient_doesNotCountAsStep() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(2);
        g.reset();
        long t = settleFeed(g, 0L);
        boolean triggered = false;
        int confirmedBefore = 0;
        // 喂 20 个 11~13 m/s² 的大瞬态尖峰（模拟翻手机/拍打）
        for (int i = 0; i < 20; i++) {
            confirmedBefore = g.confirmedStepCount();
            float ax = (i % 2 == 0) ? GRAVITY + 12.0f : GRAVITY + 11.0f;
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
            if (g.confirmedStepCount() > confirmedBefore) break;
        }
        assertFalse("大瞬态（>8 m/s²）绝不能算成步或触发预览（debug=" + g.debugInfo() + "）", triggered);
    }

    /** 大瞬态之后紧接着正常步行：必须仍能触发（验证瞬态不压制后续判定） */
    @Test
    public void transientThenNormalWalk_stillTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = settleFeed(g, 0L);
        // 先来几下大瞬态（翻手机）
        for (int i = 0; i < 10; i++) {
            g.feedAcc(GRAVITY + 12.0f, 0f, 0f, t);
            t += FRAME_MS * 5;
        }
        // 然后正常散步 1.0Hz
        boolean triggered = false;
        for (int i = 0; i < 600; i++) {
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 1.0 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertTrue("瞬态之后正常散步（confirmSteps=5）必须仍能触发，"
                + "说明过滤没有误伤正常步行（debug=" + g.debugInfo() + "）", triggered);
    }

    private static long settleFeed(GaitDetector g, long t) {
        for (int i = 0; i < 100; i++) {
            g.feedAcc(GRAVITY, 0f, 0f, t);
            t += FRAME_MS;
        }
        return t;
    }

    /** 手里拿手机乱动：晃动方向随机、主轴来回切换，绝不能触发 */
    @Test
    public void handShake_switchingAxis_notTrigger() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(3);
        g.reset();
        long t = 0L;
        boolean triggered = false;
        for (int i = 0; i < 600; i++) {
            double sec = i / 50.0;
            float shake = (float) (2.5 * Math.sin(2 * Math.PI * 1.5 * sec));
            // 主轴在 X/Y 之间来回切换：模拟乱动方向随机
            if ((i / 60) % 2 == 0) {
                if (g.feedAcc(GRAVITY + shake, 0f, 0f, t)) { triggered = true; break; }
            } else {
                if (g.feedAcc(0f, GRAVITY + shake, 0f, t)) { triggered = true; break; }
            }
            t += FRAME_MS;
        }
        assertFalse("主轴来回切换（手机在手里乱动）绝不能触发，否则玩手机又弹预览（debug="
                + g.debugInfo() + "）", triggered);
    }

    /** 走路时主轴固定：必须照常触发（确认门槛不误伤功能） */
    @Test
    public void stableAxisWalking_stillTriggers() {
        GaitDetector g = new GaitDetector();
        g.setConfirmSteps(5);
        g.reset();
        long t = settleFeed(g, 0L);
        boolean triggered = false;
        for (int i = 0; i < 600; i++) {
            double sec = i / 50.0;
            float ax = (float) (GRAVITY + 2.5 * Math.sin(2 * Math.PI * 1.1 * sec));
            if (g.feedAcc(ax, 0f, 0f, t)) { triggered = true; break; }
            t += FRAME_MS;
        }
        assertTrue("主轴固定的正常走路（confirmSteps=5）必须仍能触发（debug="
                + g.debugInfo() + "）", triggered);
    }
}
