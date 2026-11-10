package io.github.wumisc.safewalk

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 配置外部备份 / 恢复。
 *
 * 【为什么需要】
 * App 的配置存在 `/data/data/<pkg>/shared_prefs/safewalk_xsp.xml`。**卸载重装**
 * （或个别 ROM 清理 app 数据）会把整份文件连同「引导已完成」标记一起清空。
 * 真机日志里 App 启动后打出「首次安装权限引导页」+ 行走模式设置全回默认
 * （rearCam=false / mode=fullscreen / 对比度=false），就是这个原因——
 * 不是某次写盘失败，而是数据目录被清空。
 *
 * 【做法】
 * 每次保存配置时，把 `safewalk_xsp` 的全部键值另存一份到**外部可见目录**：
 *   ① 优先 `/storage/emulated/0/Android/Travel safely/safewalk_config_backup.json`
 *      —— 拿到「所有文件访问」时可用，**卸载重装也不丢**；
 *   ② 退而求其次：应用专属外部目录（不跨卸载，但至少重启不丢）。
 * 首次启动 / SP 为空时若发现备份，自动写回 SP 并重新导出给 Hook。
 *
 * 这样即使换 APK 重装、或系统清了数据，用户的行走模式/对比度/夜间手电等
 * 设置也能自动恢复，不用每次重配。
 */
object ConfigBackup {

    private const val FILE_NAME = "safewalk_config_backup.json"
    private const val SP_NAME = "safewalk_xsp"

    /**
     * 判定「全新安装 / 数据被清」：SP 完全为空才算。
     * 不能只看某个开关有没有——用户可能只改了别的设置、从没开过行走模式，
     * 那 SP 非空但没有行走模式键，用单键判断会误判成「全新」而覆盖掉新设置。
     */
    private fun isFresh(sp: android.content.SharedPreferences): Boolean = sp.all.isEmpty()

    /** 备份目录候选：优先能跨卸载保留的用户可见目录，其次应用专属外部目录。 */
    private fun candidateDirs(ctx: Context): List<File> {
        val app = ctx.applicationContext ?: ctx
        val list = ArrayList<File>(2)
        // ① 用户可见目录（需「所有文件访问」）；跨卸载保留
        list.add(File(DiagLog.USER_DIR))
        // ② 应用专属外部目录：无需权限；卸载会被删，但重启不丢
        try {
            app.getExternalFilesDir(null)?.let { list.add(it) }
        } catch (_: Throwable) {
        }
        return list
    }

    /** 返回第一个「确实可写」的备份目录；都不可写返回 null。 */
    private fun writableDir(ctx: Context): File? {
        for (d in candidateDirs(ctx)) {
            try {
                if (!d.exists() && !d.mkdirs()) continue
                val probe = File(d, ".cfg_probe")
                probe.writeText("1")
                if (!probe.delete()) {
                    // 探针删不掉也无所谓，能写说明目录可用
                }
                return d
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * 把当前 SP 全量镜像到外部备份文件。**每次配置写入后调用**（见 exportConfig）。
     * 用临时文件 + rename，避免写到一半被杀进程留下半截 JSON。
     */
    @JvmStatic
    fun backup(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            val json = JSONObject()
            sp.all.forEach { (k, v) ->
                when (v) {
                    is Boolean, is Int, is Long, is Float, is String -> json.put(k, v)
                }
            }
            if (json.length() == 0) return
            val dir = writableDir(ctx) ?: return
            val f = File(dir, FILE_NAME)
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
            // 备份是尽力而为，失败不影响主功能
        }
    }

    /**
     * 若 SP 看起来是「全新 / 被清空」，且外部有备份，则把备份写回 SP，
     * 并重新导出配置给 SystemUI 侧的 Hook。
     *
     * 只在启动早期调用一次（MainActivity / WalkPreviewService / AutoStartActivity）。
     * 用 [SENTINEL] 判断是否已有有效配置，避免把用户新改的设置冲掉。
     */
    @JvmStatic
    fun restoreIfNeeded(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            if (!isFresh(sp)) return   // 已有配置，不覆盖
            val f = findBackup(ctx) ?: return
            val json = JSONObject(f.readText())
            if (json.length() == 0) return

            val e = sp.edit()
            val keys = json.keys()
            var n = 0
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = json.get(k)) {
                    is Boolean -> { e.putBoolean(k, v); n++ }
                    is Int -> { e.putInt(k, v); n++ }
                    is Long -> { e.putLong(k, v); n++ }
                    is Double -> { e.putFloat(k, v.toFloat()); n++ }
                    is String -> { e.putString(k, v); n++ }
                }
            }
            e.commit()
            DiagLog.log("[backup] 配置为空 → 已从外部备份恢复 $n 项（" + f.absolutePath
                    + "），避免重装/清数据后设置全部回默认")
            // 恢复后立刻重新导出给 Hook，否则 SystemUI 侧仍读旧值
            try {
                SafeWalkConfig.exportConfig(ctx)
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            DiagLog.log("[backup] 恢复配置失败: " + t.message)
        }
    }

    private fun findBackup(ctx: Context): File? {
        for (d in candidateDirs(ctx)) {
            try {
                val f = File(d, FILE_NAME)
                if (f.exists() && f.length() > 0) return f
            } catch (_: Throwable) {
            }
        }
        return null
    }
}
