package io.github.wumisc.safewalk;

import android.hardware.SensorEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class WalkDetector {
    private static final int WINDOW_SIZE = 25;
    private static final float MIN_STEP_FREQ = 80f;
    private static final float MAX_STEP_FREQ = 140f;
    private static final float STATIC_VAR_THRESHOLD = 0.7f;

    private final ArrayDeque<Float> magBuffer = new ArrayDeque<>();
    private final List<Long> peakList = new ArrayList<>();

    public static class WalkResult{
        public boolean isWalking;
        public float stepFreq;
        public float variance;
        public WalkResult(boolean w, float f, float v){
            isWalking = w; stepFreq = f; variance = v;
        }
    }

    public WalkResult feed(SensorEvent event) {
        float ax = event.values[0];
        float ay = event.values[1];
        float az = event.values[2];
        float mag = (float) Math.sqrt(ax*ax + ay*ay + az*az);

        magBuffer.addLast(mag);
        if (magBuffer.size() > WINDOW_SIZE) magBuffer.removeFirst();

        boolean isPeak = false;
        if (magBuffer.size() >= 3) {
            Float[] arr = magBuffer.toArray(new Float[0]);
            int len = arr.length;
            isPeak = arr[len-2] > arr[len-3] && arr[len-2] > arr[len-1];
        }
        if (isPeak) peakList.add(System.currentTimeMillis());

        long now = System.currentTimeMillis();
        peakList.removeIf(ts -> now - ts > 10000);

        float stepFreq = 0f;
        if (peakList.size() >= 2) {
            long span = peakList.get(peakList.size()-1) - peakList.get(0);
            if (span > 0) {
                stepFreq = (peakList.size() - 1) * 1000f / span * 60f;
            }
        }

        float mean = 0f;
        for (float v : magBuffer) mean += v;
        mean /= magBuffer.size();
        float variance = 0f;
        for (float v : magBuffer) variance += (v - mean) * (v - mean);
        variance /= magBuffer.size();

        boolean isWalking = stepFreq >= MIN_STEP_FREQ && stepFreq <= MAX_STEP_FREQ && variance > STATIC_VAR_THRESHOLD;
        return new WalkResult(isWalking, stepFreq, variance);
    }
}
