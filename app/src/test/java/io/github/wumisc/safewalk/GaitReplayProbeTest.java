package io.github.wumisc.safewalk;

import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【沙盒回放】把真机导出的 gait_trace 原样喂给线上 [GaitDetector]，看它认几步、卡在哪道闸门。
 *
 * 用法（不提供轨迹时自动跳过，不影响常规回归）：
 * `./gradlew :app:testDebugUnitTest --tests "*GaitReplayProbeTest" -DgaitTrace=/abs/path/gait_trace.json`
 *
 * 为什么要有它：单测喂的是合成波形，只能证明"该认的认、该拦的拦"；真机数据长什么样、
 * 用户实际握机姿态会不会落进盲区，只有回放真轨迹才看得出来。
 */
public class GaitReplayProbeTest {

    private static final Pattern FRAME = Pattern.compile(
            "\\{\"t\":([0-9.]+),\"x\":(-?[0-9.eE+-]+),\"y\":(-?[0-9.eE+-]+),\"z\":(-?[0-9.eE+-]+)\\}");

    @Test
    public void replay() throws Exception {
        String path = System.getProperty("gaitTrace", "");
        Assume.assumeTrue("未提供 -DgaitTrace，跳过回放", !path.isEmpty() && new File(path).isFile());

        List<float[]> frames = new ArrayList<>();   // t, x, y, z
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = FRAME.matcher(line);
                while (m.find()) {
                    frames.add(new float[]{
                            Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2)),
                            Float.parseFloat(m.group(3)), Float.parseFloat(m.group(4))});
                }
            }
        }
        System.out.println("PROBE frames=" + frames.size());

        GaitDetector d = new GaitDetector();
        long t0 = Math.round(frames.get(0)[0]);
        int qualified = 0;
        StringBuilder marks = new StringBuilder();
        for (float[] f : frames) {
            if (d.feedAcc(f[1], f[2], f[3], t0 + Math.round(f[0]))) {
                qualified++;
                marks.append(Math.round(f[0] / 100) / 10.0).append("s ");
            }
        }
        System.out.println("PROBE qualifiedSteps=" + qualified
                + " confirmed=" + d.confirmedStepCount()
                + " freqHz=" + d.stepFreqHz()
                + " attitude=" + d.getAttitude()
                + " tilt=" + d.tiltDegrees()
                + " stableGait=" + d.hasStableGait()
                + " verticalRatio=" + d.verticalRatio());
        System.out.println("PROBE stepMarks=" + marks);
        System.out.println("PROBE debug=" + d.debugInfo().replace('\n', ' '));
    }
}
