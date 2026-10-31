package io.github.wumisc.safewalk;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/* loaded from: classes3.dex */
public final class Config {
    public static final String CONF_PATH = "/data/local/tmp/safewalk.conf";
    public static boolean enable = false;
    public static boolean walkMode = false;
    public static boolean autoWalk = false;
    public static int overlayForm = 1;
    public static int alpha = 55;
    public static int corner = 2;
    public static boolean lookdown = true;
    public static int lookdownSec = 3;
    public static boolean nightFlash = true;
    public static boolean safety = true;
    public static boolean tileState = false;
    public static boolean debug = false;

    private Config() {
    }

    public static void reload() {
        int eq;
        File f = new File(CONF_PATH);
        if (!f.exists()) {
            return;
        }
        Map<String, String> kv = new HashMap<>();
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            while (true) {
                String line = r.readLine();
                if (line != null) {
                    String line2 = line.trim();
                    if (!line2.isEmpty() && !line2.startsWith("#") && (eq = line2.indexOf(61)) > 0) {
                        kv.put(line2.substring(0, eq).trim(), line2.substring(eq + 1).trim());
                    }
                } else {
                    r.close();
                    enable = kv.getOrDefault("enable", "false").equals("true");
                    walkMode = kv.getOrDefault("walk_mode", "false").equals("true");
                    autoWalk = kv.getOrDefault("auto_walk", "false").equals("true");
                    overlayForm = parseInt(kv.get("overlay_form"), 1);
                    alpha = clamp(parseInt(kv.get("alpha"), 55), 10, 100);
                    corner = clamp(parseInt(kv.get("corner"), 2), 1, 4);
                    lookdown = kv.getOrDefault("lookdown", "true").equals("true");
                    lookdownSec = Math.max(1, parseInt(kv.get("lookdown_sec"), 3));
                    nightFlash = kv.getOrDefault("night_flash", "true").equals("true");
                    safety = kv.getOrDefault("safety", "true").equals("true");
                    tileState = kv.getOrDefault("tile_state", "false").equals("true");
                    debug = kv.getOrDefault("debug", "false").equals("true");
                    return;
                }
            }
        } catch (Throwable th) {
        }
    }

    private static int parseInt(String v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable th) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
