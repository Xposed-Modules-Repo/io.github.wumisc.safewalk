package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 【5.6.0】超广角判据从「原始焦距比」换成「等效焦距比」的回归测试。
 *
 * 为什么非换不可（这是多厂商适配的真正难点，不是加几个机型名字）：
 *
 * `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` 报的是**物理焦距**，视角由「物理焦距 +
 * 传感器尺寸」共同决定。长焦模组的传感器往往只有 1/3.5"，物理焦距 4mm；
 * 主摄 1/1.3"，物理焦距 7mm。按原始焦距比大小，长焦（4mm）比主摄（7mm）还「广」，
 * 会被判成超广角 —— 用户点了「超广角」，画面反而变成 3 倍变焦。
 *
 * 等效焦距 = 物理焦距 × 43.27 / 传感器对角尺寸，消掉了尺寸差异，跨厂商可比。
 * 下面第一组用例就是把这个反例锁死。
 */
public class CameraLensEflTest {

    private static CameraLensPolicy.Candidate cam(
            String id, boolean back, float focal, float sensorDiag) {
        return new CameraLensPolicy.Candidate(id, back, focal, focal, sensorDiag);
    }

    /** 无传感器尺寸（魅族 / 部分类原生精简 HAL）：走原始焦距降级判据 */
    private static CameraLensPolicy.Candidate camNoSensor(String id, float focal) {
        return new CameraLensPolicy.Candidate(id, false, focal, focal, null);
    }

    // ---------- 核心反例：长焦不能被当成超广角 ----------

    @Test
    public void telephotoWithSmallSensorIsNotUltrawide() {
        List<CameraLensPolicy.Candidate> l = new ArrayList<>();
        // id0 主摄：7.0mm / 对角 15.0mm → 等效 20.2mm（等效焦距下属于广角主摄）
        l.add(cam("0", true, 7.0f, 15.0f));
        // id1 长焦：4.0mm / 对角 4.5mm → 等效 38.5mm（物理焦距比主摄短，等效却更长）
        l.add(cam("1", true, 4.0f, 4.5f));
        // 按原始焦距：4.0 <= 7.0*0.75=5.25 → 旧逻辑会选 id1（3 倍变焦当广角）
        // 按等效焦距：38.5 > 20.2*0.8 → 不该选
        assertEquals("长焦不该被当成超广角", "0",
                CameraLensPolicy.select(l, CameraLensPolicy.MODE_ULTRAWIDE));
    }

    /**
     * 【5.6.2】跨口径不可比：主摄能算等效焦距、副摄读不到传感器尺寸时，
     * 绝不能拿「副摄物理焦距 / 主摄物理焦距」去跟等效口径的 0.8 阈值比 ——
     * 长焦物理焦距本来就短（5mm vs 主摄 7mm），比值 0.71 会被当成超广角，
     * 点下去画面反而变成变焦。正确行为：该候选无法比较，回退主摄。
     */
    @Test
    public void crossCaliberCandidateIsNotCompared() {
        List<CameraLensPolicy.Candidate> l = new ArrayList<>();
        l.add(cam("0", true, 7.0f, 12.5f));                       // 主摄：有等效焦距 24.2mm
        l.add(new CameraLensPolicy.Candidate("2", true, 5.0f, 5.0f, null)); // 长焦：读不到传感器尺寸
        assertEquals("跨口径不可比，必须回退主摄", "0",
                CameraLensPolicy.select(l, CameraLensPolicy.MODE_ULTRAWIDE));
    }

    @Test
    public void realUltrawideIsSelected() {
        List<CameraLensPolicy.Candidate> l = new ArrayList<>();
        // 主摄 24mm 等效：7.0mm / 对角 12.5mm → 24.2mm
        l.add(cam("0", true, 7.0f, 12.5f));
        // 超广角 13mm 等效：2.2mm / 对角 7.0mm → 13.6mm（比例 0.56，明显更广）
        l.add(cam("2", true, 2.2f, 7.0f));
        assertEquals("2", CameraLensPolicy.select(l, CameraLensPolicy.MODE_ULTRAWIDE));
    }

    @Test
    public void mainModeAlwaysReturnsMainEvenIfUltrawideExists() {
        List<CameraLensPolicy.Candidate> l = new ArrayList<>();
        l.add(cam("0", true, 7.0f, 12.5f));
        l.add(cam("2", true, 2.2f, 7.0f));
        assertEquals("0", CameraLensPolicy.select(l, CameraLensPolicy.MODE_MAIN));
    }

    // ---------- 主摄识别：不再依赖 id 顺序 ----------

    @Test
    public void mainIsPickedByEflNotByIdOrder() {
        List<CameraLensPolicy.Candidate> l = new ArrayList<>();
        // 三星部分机型的 id 顺序里，0 号可能是长焦
        l.add(cam("0", true, 4.0f, 4.5f));     // 等效 38.5mm
        l.add(cam("1", true, 7.0f, 12.5f));    // 等效 24.2mm ← 真正的主摄
        l.add(cam("2", true, 2.2f, 7.0f));     // 等效 13.6mm ← 超广角
        assertEquals("1", CameraLensPolicy.pickMain(l).getId());
        assertEquals("1", CameraLensPolicy.select(l, CameraLensPolicy.MODE_MAIN));
    }

    /** 超广角模式也必须挑到真正广的那颗，而不是「最短的物理焦距」 */
    @Test
    public void ultrawideBeatsTelephotoWhenBothShorterThanMain() {
        List<CameraLensPolicy.Candidate> l = new ArrayList<>();
        l.add(cam("0", true, 7.0f, 12.5f));    // 主摄 24.2mm
        l.add(cam("1", true, 4.0f, 4.5f));     // 长焦 38.5mm
        l.add(cam("2", true, 2.2f, 7.0f));     // 超广 13.6mm
        assertEquals("2", CameraLensPolicy.select(l, CameraLensPolicy.MODE_ULTRAWIDE));
    }

    // ---------- 降级路径：读不到传感器尺寸 ----------

    @Test
    public void missingSensorSizeFallsBackToFocalRatio() {
        List<CameraLensPolicy.Candidate> l = new ArrayList<>();
        l.add(new CameraLensPolicy.Candidate("0", true, 5.0f, 5.0f, null));
        l.add(new CameraLensPolicy.Candidate("1", true, 2.0f, 2.0f, null));
        assertEquals("1", CameraLensPolicy.select(l, CameraLensPolicy.MODE_ULTRAWIDE));
    }

    /** 单摄（Pixel 常见：只有一颗后置）→ 无论选哪档都回退它自己，不会黑屏 */
    @Test
    public void singleBackCameraAlwaysFallsBack() {
        List<CameraLensPolicy.Candidate> l = new ArrayList<>();
        l.add(cam("0", true, 6.8f, 12.5f));
        l.add(new CameraLensPolicy.Candidate("1", false, 3.0f, 10f, null));   // 前摄
        assertEquals("0", CameraLensPolicy.select(l, CameraLensPolicy.MODE_ULTRAWIDE));
        assertEquals("0", CameraLensPolicy.select(l, CameraLensPolicy.MODE_MAIN));
    }

    @Test
    public void noBackCameraReturnsNull() {
        List<CameraLensPolicy.Candidate> l = new ArrayList<>();
        l.add(new CameraLensPolicy.Candidate("1", false, 3.0f, 10f, null));
        assertNull(CameraLensPolicy.select(l, CameraLensPolicy.MODE_ULTRAWIDE));
    }

    // ---------- 等效焦距换算 ----------

    @Test
    public void eflMatchesKnownSensor() {
        // 1/1.3" 传感器对角约 12.5mm，物理焦距 7mm → 等效约 24mm（业界公开数据）
        CameraLensPolicy.Candidate c = cam("0", true, 7.0f, 12.5f);
        assertNotNull(c.getEflMm());
        float efl = c.getEflMm();
        assertEquals("等效焦距应约 24mm，实际 " + efl, 24.0f, efl, 1.5f);
    }

    @Test
    public void eflIsNullWhenDataMissing() {
        CameraLensPolicy.Candidate c = camNoSensor("0", 5.0f);
        assertNull(c.getEflMm());
    }

    /** 诊断行必须能打印出每颗后摄的焦距/对角/等效，用户回传才能校准 */
    @Test
    public void describeListsEveryCandidate() {
        List<CameraLensPolicy.Candidate> l = new ArrayList<>();
        l.add(cam("0", true, 7.0f, 12.5f));
        l.add(cam("2", true, 2.2f, 7.0f));
        String s = CameraLensPolicy.describe(l);
        assertTrue(s.contains("id=0"));
        assertTrue(s.contains("id=2"));
        assertTrue(s.contains("等效="));
    }
}
