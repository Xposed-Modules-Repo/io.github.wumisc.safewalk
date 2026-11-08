package io.github.wumisc.safewalk;

import android.content.SharedPreferences;

/**
 * SharedPreferences 安全读取。
 *
 * 隐患：SharedPreferences 的类型是写死的。只要某个 key 的历史类型发生过变化
 * （比如早期版本把「手电静置分钟」存成 int，后来改成 float），
 * `getFloat()` 就会直接抛 ClassCastException。
 *
 * 这种崩溃发生在 onCreate 里 —— 表现就是「点进二级菜单，白屏闪一下就没了」，
 * 和此前 MaterialSwitch 崩溃是同一类症状，但原因完全不同，日志里也只会在
 * SharedPreferencesImpl 里报一句 CCE，很难联想到这里。
 *
 * 所以所有读配置的地方统一走这里：类型对就用原生方法，类型不对就按最接近的
 * 语义转换，转换不了返回默认值，永远不抛。
 */
public final class SafePrefs {

    private SafePrefs() {}

    public static boolean getBool(SharedPreferences sp, String key, boolean def) {
        if (sp == null) return def;
        try {
            return sp.getBoolean(key, def);
        } catch (ClassCastException e) {
            Object v = sp.getAll().get(key);
            if (v instanceof String) {
                String s = ((String) v).trim();
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
                return def;
            }
            if (v instanceof Number) return ((Number) v).intValue() != 0;
            return def;
        }
    }

    public static int getInt(SharedPreferences sp, String key, int def) {
        if (sp == null) return def;
        try {
            return sp.getInt(key, def);
        } catch (ClassCastException e) {
            Object v = sp.getAll().get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) {
                try {
                    return Integer.parseInt(((String) v).trim());
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
            return def;
        }
    }

    public static float getFloat(SharedPreferences sp, String key, float def) {
        if (sp == null) return def;
        try {
            return sp.getFloat(key, def);
        } catch (ClassCastException e) {
            Object v = sp.getAll().get(key);
            if (v instanceof Number) return ((Number) v).floatValue();
            if (v instanceof String) {
                try {
                    return Float.parseFloat(((String) v).trim());
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }
    }

    public static String getString(SharedPreferences sp, String key, String def) {
        if (sp == null) return def;
        try {
            return sp.getString(key, def);
        } catch (ClassCastException e) {
            Object v = sp.getAll().get(key);
            return v == null ? def : String.valueOf(v);
        }
    }
}
