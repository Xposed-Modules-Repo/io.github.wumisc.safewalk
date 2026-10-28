package io.github.wumisc.safewalk;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 安全出行模块配置。
 * 配置文件：/data/local/tmp/safewalk.conf（App 经 su 写入，SystemUI 进程读取）
 */
public final class Config {

    public static final String CONF_PATH = "/data/local/tmp/safewalk.conf";

    /** 总开关 */
    public static boolean enable = false;
    /** 行走模式（手动开启摄像头路况） */
    public static boolean walkMode = false;
    /** 自动检测行走：走起来自动开、停下自动关 */
    public static boolean autoWalk = false;
    /** 摄像头画面形态：0=角落小窗 1=全屏半透明叠加 */
    public static int overlayForm = 1;
    /** 摄像头画面不透明度 0-100（全屏半透明时生效） */
    public static int alpha = 55;
    /** 角落小窗位置：1左上 2右上 3左下 4右下 */
    public static int corner = 2;
    /** 低头看路提醒开关 */
    public static boolean lookdown = true;
    /** 低头触发提醒的持续时长（秒） */
    public static int lookdownSec = 3;
    /** 夜间手电筒提醒开关 */
    public static boolean nightFlash = true;
    /** 磁贴开关状态 */
    public static boolean tileState = false;
    /** 诊断日志总闸：默认关闭（省电省存储），排查问题时再开 */
    public static boolean debug = false;

    private Config() {
    }

    /** 每次调用都重新读取文件，保证 SystemUI 能即时拿到 App 保存的新配置 */
    public static void reload() {
        File f = new File(CONF_PATH);
        if (!f.exists()) {
            return;
        }
        Map<String, String> kv = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    kv.put(line.substring(0, eq).trim(),
                            line.substring(eq + 1).trim());
                }
            }
        } catch (Throwable t) {
            return;
        }
        enable = kv.getOrDefault("enable", "false").equals("true");
        walkMode = kv.getOrDefault("walk_mode", "false").equals("true");
        autoWalk = kv.getOrDefault("auto_walk", "false").equals("true");
        overlayForm = parseInt(kv.get("overlay_form"), 1);
        alpha = clamp(parseInt(kv.get("alpha"), 55), 10, 100);
        corner = clamp(parseInt(kv.get("corner"), 2), 1, 4);
        lookdown = kv.getOrDefault("lookdown", "true").equals("true");
        lookdownSec = Math.max(1, parseInt(kv.get("lookdown_sec"), 3));
        nightFlash = kv.getOrDefault("night_flash", "true").equals("true");
        tileState = kv.getOrDefault("tile_state", "false").equals("true");
        debug = kv.getOrDefault("debug", "false").equals("true");
    }

    private static int parseInt(String v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
