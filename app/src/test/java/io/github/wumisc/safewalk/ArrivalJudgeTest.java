package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「到达某类场所」判定器回归测试。
 *
 * 背景：证件提醒 / 社交提示原来挂在「确认持续步行」上，只要走路就弹，
 * 跟人在哪儿无关。改成地点触发后，判定器必须做到三件事：
 * ① 路过不算到达（要连续命中）；② 同一类场所不连环弹；③ 走开后重来。
 */
public class ArrivalJudgeTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;

    /** 单次命中可能只是路过，或被反编码带进了邻近商圈的名字 —— 不能算到达 */
    @Test
    public void singleSampleIsNotArrival() {
        ArrivalJudge j = new ArrivalJudge();
        assertNull(j.onSample("旅游", 0L));
        assertEquals("旅游", j.getPendingKind());
        assertEquals(1, j.getPendingHits());
    }

    /** 连续两次同类别才放行，且只放行一次 */
    @Test
    public void twoConsecutiveSamplesFires() {
        ArrivalJudge j = new ArrivalJudge();
        assertNull(j.onSample("求职", 0L));
        assertEquals("求职", j.onSample("求职", 2 * MIN));
        // 第三次同类别不再触发（同类冷却内）
        assertNull(j.onSample("求职", 4 * MIN));
    }

    /** 类别来回跳（在商圈里从餐厅走到 KTV 又走回来）不能靠累积次数凑出「到达」 */
    @Test
    public void switchingKindResetsCounter() {
        ArrivalJudge j = new ArrivalJudge();
        j.onSample("约会", 0L);
        j.onSample("聚会", 1 * MIN);
        // 回到约会：计数已重置，这次只是第 1 次
        assertNull(j.onSample("约会", 2 * MIN));
        assertEquals("约会", j.onSample("约会", 3 * MIN));
    }

    /** 走开再回来要重新确认，不能因为「刚才来过」直接放行 */
    @Test
    public void leavingClearsCandidate() {
        ArrivalJudge j = new ArrivalJudge();
        j.onSample("聚会", 0L);
        assertNull(j.onSample(null, 1 * MIN));   // 离开
        assertEquals(0, j.getPendingHits());
        assertNull(j.getPendingKind());
        // 回来只算第 1 次
        assertNull(j.onSample("聚会", 2 * MIN));
    }

    /** 同一类别 6 小时冷却：中午在这吃饭、下午又来，不该弹第二次 */
    @Test
    public void sameKindWithinCooldownIsSuppressed() {
        ArrivalJudge j = new ArrivalJudge();
        j.onSample("约会", 0L);
        assertEquals("约会", j.onSample("约会", 1 * MIN));
        // 5 小时后再次到达同类别
        j.onSample(null, 5 * HOUR);
        j.onSample("约会", 5 * HOUR + MIN);
        assertNull(j.onSample("约会", 5 * HOUR + 2 * MIN));
        // 超过 6 小时（从首次触发算）可以再提醒
        assertTrue(j.lastFiredOf("约会") > 0L);
        j.onSample(null, 7 * HOUR);
        j.onSample("约会", 7 * HOUR + MIN);
        assertEquals("约会", j.onSample("约会", 7 * HOUR + 2 * MIN));
    }

    /** 不同类别互不影响：同一商圈从餐厅（约会）走到 KTV（聚会）可以各提醒一次 */
    @Test
    public void differentKindsHaveIndependentCooldown() {
        ArrivalJudge j = new ArrivalJudge();
        j.onSample("约会", 0L);
        assertEquals("约会", j.onSample("约会", MIN));
        // 立刻换到聚会类，仍然可以触发
        j.onSample("聚会", 2 * MIN);
        assertEquals("聚会", j.onSample("聚会", 3 * MIN));
    }

    /** 自定义停留次数：把门槛提到 3 时，两次不应放行 */
    @Test
    public void customStaySamplesRespected() {
        ArrivalJudge j = new ArrivalJudge(3, ArrivalJudge.DEFAULT_COOLDOWN_MS);
        j.onSample("旅游", 0L);
        assertNull("第 2 次采样还不够 3 次门槛", j.onSample("旅游", MIN));
        assertEquals("旅游", j.onSample("旅游", 2 * MIN));
    }

    /** reset 后完全重新开始 */
    @Test
    public void resetClearsState() {
        ArrivalJudge j = new ArrivalJudge();
        j.onSample("旅游", 0L);
        assertEquals("旅游", j.onSample("旅游", MIN));
        j.reset();
        assertNull(j.getPendingKind());
        assertEquals(0L, j.lastFiredOf("旅游"));
        assertNull(j.onSample("旅游", 2 * MIN));
    }
}
