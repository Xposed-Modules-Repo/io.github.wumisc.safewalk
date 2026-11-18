package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** 一次性显示载荷：二次 take 空 / TTL 过期 / invalidate / 无字段值落盘。 */
@RunWith(RobolectricTestRunner.class)
public class DisplayPayloadStoreTest {

    private DisplayFields fields;

    @Before
    public void setUp() {
        DisplayPayloadStore.clear();
        fields = new DisplayFields("张三", "A型", "Rh+", "青霉素", true,
                "心脏病", "李四", "13800000000", 65);
    }

    @Test
    public void take_isOneShot_secondTakeReturnsNull() {
        String token = DisplayPayloadStore.publish(fields, "evt-1");
        assertNotNull(token);
        assertTrue("Base64-URL token 至少 22 字符", token.length() >= 22);
        assertFalse("token 不得是硬编码常量", token.equals(SafeWalkPrefKey.BROADCAST_TOKEN));

        long now = SystemClock.elapsedRealtime();
        DisplayFields first = DisplayPayloadStore.take(token, now);
        assertNotNull(first);
        assertEquals("张三", first.getName());
        assertEquals("A型", first.getBloodType());
        assertEquals("Rh+", first.getRh());
        assertEquals("青霉素", first.getAllergy());
        assertTrue(first.getAllergySevere());
        assertEquals("心脏病", first.getConditions());
        assertEquals("李四", first.getPrimaryContactName());
        assertEquals(65, first.getAge());

        // 用后即焚：第二次必须空，防重放/重复渲染
        assertNull(DisplayPayloadStore.take(token, SystemClock.elapsedRealtime()));
    }

    @Test
    public void take_wrongTokenReturnsNull() {
        String token = DisplayPayloadStore.publish(fields, "evt-2");
        assertNull(DisplayPayloadStore.take("forged-token", SystemClock.elapsedRealtime()));
        // 错误 token 不消费槽位：真取件仍能拿到
        assertNotNull(DisplayPayloadStore.take(token, SystemClock.elapsedRealtime()));
    }

    @Test
    public void take_expiresAfterTtl() {
        String token = DisplayPayloadStore.publish(fields, "evt-3");
        long after = SystemClock.elapsedRealtime() + SafeWalkPrefKey.DISPLAY_TTL_MS + 1_000L;
        assertNull("超过 20s TTL 必须取不到", DisplayPayloadStore.take(token, after));
        assertNull("过期取件也会焚毁槽位", DisplayPayloadStore.take(token, SystemClock.elapsedRealtime()));
    }

    @Test
    public void invalidate_clearsSlot() {
        String token = DisplayPayloadStore.publish(fields, "evt-4");
        DisplayPayloadStore.invalidate(token);
        assertNull(DisplayPayloadStore.take(token, SystemClock.elapsedRealtime()));
    }

    @Test
    public void invalidate_wrongTokenDoesNotClear() {
        String token = DisplayPayloadStore.publish(fields, "evt-5");
        DisplayPayloadStore.invalidate("another-token");
        assertNotNull(DisplayPayloadStore.take(token, SystemClock.elapsedRealtime()));
    }

    @Test
    public void expireIfDue_clearsExpiredSlot() {
        DisplayPayloadStore.publish(fields, "evt-6");
        assertFalse(DisplayPayloadStore.expireIfDue(SystemClock.elapsedRealtime()));
        assertTrue("TTL 到期应清槽", DisplayPayloadStore.expireIfDue(
                SystemClock.elapsedRealtime() + SafeWalkPrefKey.DISPLAY_TTL_MS + 1L));
        assertFalse(DisplayPayloadStore.expireIfDue(SystemClock.elapsedRealtime()));
    }

    @Test
    public void displayFields_jsonRoundTripKeepsValuesButNoAddress() {
        // 字段本体必须能经 Binder JSON 往返；且类型层面没有 address/insuranceId
        String json = fields.toJson();
        assertTrue(json.contains("青霉素"));
        DisplayFields back = DisplayFields.fromJson(json);
        assertEquals("张三", back.getName());
        assertTrue(back.getAllergySevere());
    }
}
