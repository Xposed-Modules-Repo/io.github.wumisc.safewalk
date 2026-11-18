package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 路况预览「镜头选择」策略回归测试。
 *
 * 真机上摄像头 id 顺序、有几颗后置完全看厂商，且 Camera2 不保证哪个是主摄。
 * 这里用纯数据锁住三件事：选主摄（第一颗后置）、选超广角（焦距最短且够短）、
 * 以及「没有超广角时回退主摄」——最后一条正是用户要求的「无超广角可用主摄代替」。
 */
public class CameraLensPolicyTest {

    private static CameraLensPolicy.Candidate cam(
            String id, boolean back, Float minF, Float maxF) {
        return new CameraLensPolicy.Candidate(id, back, minF, maxF);
    }

    @Test
    public void normalize_defaultsToUltrawide() {
        // 写错的值与 auto 都归一到超广角（用户认可的默认档，带回退）
        assertEquals(CameraLensPolicy.MODE_ULTRAWIDE, CameraLensPolicy.normalize(null));
        assertEquals(CameraLensPolicy.MODE_ULTRAWIDE, CameraLensPolicy.normalize(""));
        assertEquals(CameraLensPolicy.MODE_ULTRAWIDE, CameraLensPolicy.normalize("auto"));
        assertEquals(CameraLensPolicy.MODE_ULTRAWIDE, CameraLensPolicy.normalize("ultrawide"));
        assertEquals(CameraLensPolicy.MODE_MAIN, CameraLensPolicy.normalize("main"));
        assertEquals(CameraLensPolicy.MODE_ULTRAWIDE, CameraLensPolicy.normalize("garbage"));
    }

    @Test
    public void ultrawidePicksShortestFocalBackCamera() {
        // 典型三摄：主摄 4.3mm（第一颗后置）、超广角 2.0mm、长焦 8.0mm、前置 3.0mm
        List<CameraLensPolicy.Candidate> cams = Arrays.asList(
                cam("0", true, 4.3f, 4.3f),     // 主摄
                cam("1", false, 3.0f, 3.0f),    // 前置，必须忽略
                cam("2", true, 2.0f, 2.0f),     // 超广角
                cam("3", true, 8.0f, 8.0f));    // 长焦
        assertEquals("2", CameraLensPolicy.select(cams, CameraLensPolicy.MODE_ULTRAWIDE));
        // auto 与 ultrawide 等价
        assertEquals("2", CameraLensPolicy.select(cams, CameraLensPolicy.MODE_AUTO));
    }

    @Test
    public void mainPicksFirstBackCameraById() {
        // 主摄 = 第一颗后置，而不是长焦（焦距最长那颗）
        List<CameraLensPolicy.Candidate> cams = Arrays.asList(
                cam("0", true, 4.3f, 4.3f),
                cam("1", false, 3.0f, 3.0f),
                cam("2", true, 2.0f, 2.0f),
                cam("3", true, 8.0f, 8.0f));
        assertEquals("0", CameraLensPolicy.select(cams, CameraLensPolicy.MODE_MAIN));
    }

    @Test
    public void noUltrawide_fallsBackToMain() {
        // 双摄但都不是广角：主摄 4.3mm + 长焦 8.0mm。最短焦距（也是主摄）不满足
        // 「比主摄还短到阈值以下」，判无广角，回退主摄。绝不会误选长焦。
        List<CameraLensPolicy.Candidate> tele = Arrays.asList(
                cam("0", true, 4.3f, 4.3f),
                cam("1", true, 8.0f, 8.0f));
        assertEquals("0", CameraLensPolicy.select(tele, CameraLensPolicy.MODE_ULTRAWIDE));

        // 两颗同焦距：比值 =1.0 > 0.75，判无广角，回退第一颗后置
        List<CameraLensPolicy.Candidate> twin = Arrays.asList(
                cam("0", true, 4.3f, 4.3f),
                cam("1", true, 4.3f, 4.3f));
        assertEquals("0", CameraLensPolicy.select(twin, CameraLensPolicy.MODE_ULTRAWIDE));
    }

    @Test
    public void ultrawideMustBeClearlyShorter_notMarginally() {
        // 4.3 与 4.0 只差 7%，不该判成广角（阈值 0.75）
        List<CameraLensPolicy.Candidate> close = Arrays.asList(
                cam("0", true, 4.3f, 4.3f),
                cam("1", true, 4.0f, 4.0f));
        assertEquals("0", CameraLensPolicy.select(close, CameraLensPolicy.MODE_ULTRAWIDE));

        // 4.3 与 2.0 差 53%，判成广角
        List<CameraLensPolicy.Candidate> wide = Arrays.asList(
                cam("0", true, 4.3f, 4.3f),
                cam("1", true, 2.0f, 2.0f));
        assertEquals("1", CameraLensPolicy.select(wide, CameraLensPolicy.MODE_ULTRAWIDE));
    }

    @Test
    public void singleBackCamera_fallsBackToItself() {
        List<CameraLensPolicy.Candidate> single = Arrays.asList(
                cam("0", false, 3.0f, 3.0f),
                cam("1", true, 4.3f, 4.3f));
        assertEquals("1", CameraLensPolicy.select(single, CameraLensPolicy.MODE_ULTRAWIDE));
        assertEquals("1", CameraLensPolicy.select(single, CameraLensPolicy.MODE_MAIN));
    }

    @Test
    public void noFocalInfo_fallsBackToFirstBackByIdOrder() {
        // 部分 ROM 返回 null 焦距：退回旧行为（第一个后置）
        List<CameraLensPolicy.Candidate> none = Arrays.asList(
                cam("0", false, null, null),
                cam("1", true, null, null),
                cam("2", true, null, null));
        assertEquals("1", CameraLensPolicy.select(none, CameraLensPolicy.MODE_ULTRAWIDE));
        assertEquals("1", CameraLensPolicy.select(none, CameraLensPolicy.MODE_MAIN));
    }

    @Test
    public void noBackCamera_returnsNull() {
        List<CameraLensPolicy.Candidate> frontOnly =
                Collections.singletonList(cam("0", false, 3.0f, 3.0f));
        assertEquals(null, CameraLensPolicy.select(frontOnly, CameraLensPolicy.MODE_ULTRAWIDE));
        assertEquals(null, CameraLensPolicy.select(
                Collections.<CameraLensPolicy.Candidate>emptyList(), CameraLensPolicy.MODE_MAIN));
    }

    // ==================== 逻辑摄像头 zoom ratio（0.6x 超广角）====================

    @Test
    public void logicalUltrawide_applies06xWhenRangeSupportsIt() {
        // 典型逻辑摄像头：zoom range [0.6, 8.0]，选超广角 → 下发 0.6
        assertEquals(Float.valueOf(0.6f), CameraLensPolicy.zoomForLogicalUltrawide(
                CameraLensPolicy.MODE_ULTRAWIDE, 0.6f, 8.0f));
        assertEquals(Float.valueOf(0.6f), CameraLensPolicy.zoomForLogicalUltrawide(
                CameraLensPolicy.MODE_AUTO, 0.5f, 10.0f));
    }

    @Test
    public void logicalUltrawide_clampsToDeviceLowerBound() {
        // 下限 0.7（比 0.6 窄）：夹到设备下限 0.7，仍是广角端
        assertEquals(Float.valueOf(0.7f), CameraLensPolicy.zoomForLogicalUltrawide(
                CameraLensPolicy.MODE_ULTRAWIDE, 0.7f, 5.0f));
    }

    @Test
    public void mainMode_neverAppliesZoom() {
        // 用户明确选主摄：不管 range 如何都不下发（保持原生 1.0）
        assertEquals(null, CameraLensPolicy.zoomForLogicalUltrawide(
                CameraLensPolicy.MODE_MAIN, 0.5f, 8.0f));
    }

    @Test
    public void noUltrawideCapability_returnsNull() {
        // 下限 >= 1：没有广角端 → 保持原生，配合回退主摄
        assertEquals(null, CameraLensPolicy.zoomForLogicalUltrawide(
                CameraLensPolicy.MODE_ULTRAWIDE, 1.0f, 8.0f));
        // 读不到 range：走独立 id 路径，这里不干预
        assertEquals(null, CameraLensPolicy.zoomForLogicalUltrawide(
                CameraLensPolicy.MODE_ULTRAWIDE, null, null));
    }
}
