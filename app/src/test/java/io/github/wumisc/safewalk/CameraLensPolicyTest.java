package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * 镜头选择策略回归测试（5.4.17）。
 *
 * 纯 JVM，不碰相机硬件。核心契约：默认「超广角优先」，没有超广角就回退主摄，
 * 且绝不把长焦/景深误当成超广角。
 */
public class CameraLensPolicyTest {

    private static CameraLensPolicy.Candidate c(String id, int facing, float focal) {
        return new CameraLensPolicy.Candidate(id, facing, focal);
    }

    @Test
    public void ultraWidePreferredByDefault() {
        // 主摄 5.4mm(id=0)、超广角 2.1mm(id=1)、长焦 12mm(id=2)
        List<CameraLensPolicy.Candidate> cams = Arrays.asList(
                c("0", CameraLensPolicy.FACING_BACK, 5.4f),
                c("1", CameraLensPolicy.FACING_BACK, 2.1f),
                c("2", CameraLensPolicy.FACING_BACK, 12.0f));
        assertEquals("1", CameraLensPolicy.chooseCameraId(cams, CameraLensPolicy.LENS_ULTRAWIDE));
        assertEquals("1", CameraLensPolicy.chooseCameraId(cams, null)); // 默认=超广角优先
        assertTrue(CameraLensPolicy.hasUltraWide(cams));
    }

    @Test
    public void singleCameraFallsBackToMain() {
        List<CameraLensPolicy.Candidate> cams = Collections.singletonList(
                c("0", CameraLensPolicy.FACING_BACK, 5.4f));
        assertEquals("0", CameraLensPolicy.chooseCameraId(cams, CameraLensPolicy.LENS_ULTRAWIDE));
        assertFalse(CameraLensPolicy.hasUltraWide(cams));
    }

    @Test
    public void noUltraWideOnlyTelephotoFallsBackToMain() {
        // 两颗后置，第二颗是长焦（焦距更大），不能被误选成超广角
        List<CameraLensPolicy.Candidate> cams = Arrays.asList(
                c("0", CameraLensPolicy.FACING_BACK, 5.4f),
                c("2", CameraLensPolicy.FACING_BACK, 12.0f));
        assertEquals("0", CameraLensPolicy.chooseCameraId(cams, CameraLensPolicy.LENS_ULTRAWIDE));
        assertFalse(CameraLensPolicy.hasUltraWide(cams));
    }

    @Test
    public void mainModeAlwaysPicksFirstBack() {
        List<CameraLensPolicy.Candidate> cams = Arrays.asList(
                c("2", CameraLensPolicy.FACING_FRONT, 3.0f), // 前摄排前面也不该被选中
                c("0", CameraLensPolicy.FACING_BACK, 5.4f),
                c("1", CameraLensPolicy.FACING_BACK, 2.1f));
        assertEquals("0", CameraLensPolicy.chooseCameraId(cams, CameraLensPolicy.LENS_MAIN));
    }

    @Test
    public void missingFocalFallsBackToMain() {
        // 机型没上报焦距（<=0），不臆测，回退主摄
        List<CameraLensPolicy.Candidate> cams = Arrays.asList(
                c("0", CameraLensPolicy.FACING_BACK, 0f),
                c("1", CameraLensPolicy.FACING_BACK, 2.1f));
        assertEquals("0", CameraLensPolicy.chooseCameraId(cams, CameraLensPolicy.LENS_ULTRAWIDE));
        assertFalse(CameraLensPolicy.hasUltraWide(cams));
    }

    @Test
    public void noBackCameraReturnsNull() {
        List<CameraLensPolicy.Candidate> cams = Collections.singletonList(
                c("2", CameraLensPolicy.FACING_FRONT, 3.0f));
        assertNull(CameraLensPolicy.chooseCameraId(cams, CameraLensPolicy.LENS_ULTRAWIDE));
        assertNull(CameraLensPolicy.chooseCameraId(Collections.emptyList(), null));
    }

    @Test
    public void nearEqualFocalIsNotUltraWide() {
        // 两颗焦距几乎一样（差 < 15%），不算超广角，回退主摄
        List<CameraLensPolicy.Candidate> cams = Arrays.asList(
                c("0", CameraLensPolicy.FACING_BACK, 5.4f),
                c("1", CameraLensPolicy.FACING_BACK, 5.0f));
        assertFalse(CameraLensPolicy.hasUltraWide(cams));
        assertEquals("0", CameraLensPolicy.chooseCameraId(cams, CameraLensPolicy.LENS_ULTRAWIDE));
    }

    @Test
    public void normalizeIsLenient() {
        assertEquals(CameraLensPolicy.LENS_MAIN, CameraLensPolicy.normalize("main"));
        assertEquals(CameraLensPolicy.LENS_ULTRAWIDE, CameraLensPolicy.normalize("ultrawide"));
        assertEquals(CameraLensPolicy.LENS_ULTRAWIDE, CameraLensPolicy.normalize("typo"));
        assertEquals(CameraLensPolicy.LENS_ULTRAWIDE, CameraLensPolicy.normalize(null));
    }
}
