package io.github.wumisc.safewalk

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 统一诊断日志出口。
 *
 * 双通道：
 * ① logcat —— `adb logcat -s SafeWalk` 永远可用，是排查首选；
 * ② 用户可见文件 —— 优先写用户指定的 `/storage/emulated/0/Android/Travel safely/diag.log`。
 *
 * 【为什么之前的文件日志一条都没有】
 * 判定目录能不能用，原来靠 `File.canWrite()`。它在 Android 11+ 的分区存储
 * （Scoped Storage）下会**误报 true**：`Android/` 下除 data/obb/media 之外的
 * 目录，普通应用既没有创建权也没有写入权，但 canWrite() 依然返回 true。
 * 于是目录被「选中」，真正写入时抛 EACCES，又被 catch 静默吞掉 ——
 * 结果就是路径永远不存在、日志永远为空，连一句报错都看不到。
 *
 * 现在改成**探针写入**：真的建一个临时文件写一个字节再删掉，
 * 写得进去才算可用。同时把每个候选目录的探测结果写进 logcat，
 * 不用再猜日志到底落哪了。
 *
 * 【为什么还要日志回传】
 * Hook 跑在 SystemUI 进程，它的 `getExternalFilesDir()` 是
 * `Android/data/com.android.systemui/…`，用户根本找不到；而 SystemUI 的 uid
 * 也写不进本应用的私有目录。所以 Hook 侧的日志先进内存环形缓冲，
 * App 启动时向 Hook 要一份（[ACTION_REQUEST_LOG] → [ACTION_LOG_DUMP]），
 * 由 App 进程统一落盘到同一个 diag.log 里。
 */
object DiagLog {
    private const val TAG = "SafeWalk"
    private const val MAX_BYTES = 512 * 1024L
    private const val RING_MAX = 400
    private const val FILE_NAME = "diag.log"

    /**
     * 【5.6.9】日志所属版本号（SP int）。
     *
     * 用户痛点：覆盖安装新版本后，diag.log 里仍混着旧版本几千行日志，
     * 每次抓日志都要先人肉找版本分界线。现在 App 进程 init 时发现
     * versionCode 变了就把旧日志整个清掉、写一行升级头，之后文件里
     * 只会有当前版本的内容。**只清日志，任何功能配置都不动。**
     */
    private const val SP_KEY_LOG_VER = "diag_log_ver"

    /** App 向 Hook 索要日志（Hook 收到后回 [ACTION_LOG_DUMP]） */
    const val ACTION_REQUEST_LOG = "io.github.wumisc.safewalk.REQUEST_LOG"
    /** Hook 回传日志正文 */
    const val ACTION_LOG_DUMP = "io.github.wumisc.safewalk.LOG_DUMP"
    /** 回传正文的 extra key */
    const val EXTRA_LOG_TEXT = "log_text"

    /** 用户指定的可见路径 */
    const val USER_DIR = "/storage/emulated/0/Android/Travel safely"

    @Volatile
    private var logFile: File? = null

    /**
     * 日志总开关。关了以后 log() 直接 return：不再开文件流、不再写环形缓冲，
     * 省掉每条日志一次 FileOutputStream 的 I/O（主要耗电点）。
     * 默认 true，与旧版一致；用户在「智能提醒」里关掉即可省电。
     */
    @JvmField
    @Volatile
    var enabled: Boolean = true

    /**
     * 【医疗急救卡 v1.1】脱敏钩子。
     *
     * DiagLog 本身不认识医疗字段，也不该硬编码字段清单；由 [EmergencyDiag.install]
     * 注入一个"字段名 + 值 → 可安全落盘文本"的函数。任何医疗字段写日志前都必须
     * 经 [redact]，输出形如 `key=***(len=N)`。默认实现原样返回，仅用于非医疗日志；
     * EmergencyDiag 会在 App 进程启动时覆盖它。
     */
    @JvmStatic
    @Volatile
    var redactor: ((String?, String?) -> String)? = { _, value -> value ?: "" }

    /** 走脱敏钩子输出一条可安全落盘的文本（医疗字段永远只看到 *** 与长度） */
    @JvmStatic
    fun redact(key: String?, value: String?): String = try {
        redactor?.invoke(key, value) ?: "***"
    } catch (_: Throwable) {
        "***"
    }

    /** 是否已经做过一次完整探测（失败也算，避免每写一条就重探一遍目录） */
    @Volatile
    private var resolved = false

    /** 本次运行是否已完成「版本变更清日志」判定（含「版本没变」的情况） */
    @Volatile
    private var versionCleaned = false

    /** init 时留存的 App Context：目录切换后补做版本判定要用 */
    @Volatile
    private var appCtx: Context? = null

    /** 最近一次失败原因，给页面/Toast 用 */
    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastProbeReport = ""

    /** 上次检查「能否切回用户目录」的时间，避免 onResume 每次探针写盘 */
    @Volatile
    private var lastUpgradeCheck = 0L

    private val ring = java.util.ArrayDeque<String>()
    private val ringLock = Any()

    /**
     * 按 key 隔离的节流状态表。
     *
     * 4.4.0 之前节流字段散落在各业务类（lastGaitDiagTs / lastActiveDiagTs /
     * lastGuardDiagTs / lastAccelDiagTs / lastStepProbeTs …），新增日志点只要
     * 忘写节流就会在传感器回调里刷屏。现在统一由 [logThrottled] 收口，
     * 判定逻辑在 [DiagThrottle]（纯逻辑、可单测）。
     *
     * DiagLog 是进程内单例：App 进程与 SystemUI 进程各持一份，天然隔离，
     * 不需要跨进程锁；这里只防同一进程内多回调线程并发访问。
     */
    private val throttleStates = java.util.HashMap<String, DiagThrottle.State>()
    private val throttleLock = Any()

    /**
     * 初始化日志目录。可重复调用，只有第一次真正探测。
     *
     * 不依赖调用方是 Activity 还是 Service —— 两个入口都会调到；
     * 也不强制要求已拿到 MANAGE_EXTERNAL_STORAGE，拿不到就往下逐级降级。
     */
    @JvmStatic
    fun init(ctx: Context) {
        // 每次 init 都刷新总开关（用户在设置里改完开关，下次拉起服务即生效）。
        // 注意：Hook 进程读不到本应用 sp，它那边由 SafeWalkLSPHook.diag() 单独按配置过滤。
        try {
            enabled = ctx.getSharedPreferences("safewalk_xsp", Context.MODE_PRIVATE)
                .getBoolean("enableDiagLog", true)
        } catch (_: Throwable) {
        }
        if (resolved) {
            // 授权可能是 App 还活着的时候完成的：之前已经降级到别的目录，
            // 现在能写用户目录了就必须切过去，否则用户授权完还是找不到日志。
            upgradeToUserDir()
            return
        }
        synchronized(ringLock) {
            if (resolved) {
                upgradeToUserDirLocked()
                return
            }
            resolved = true

            val app = ctx.applicationContext ?: ctx
            val candidates = ArrayList<File>(3)
            // ① 用户指定的可见目录（需要「所有文件访问」权限）
            candidates.add(File(USER_DIR))
            // ② 应用专属外部目录：不需要任何特殊权限，路径见 status()
            try {
                app.getExternalFilesDir(null)?.let { candidates.add(File(it, "logs")) }
            } catch (_: Throwable) {
            }
            // ③ 私有目录：永远可写，只有 root 能看到
            candidates.add(File(app.getDir("safewalk", Context.MODE_PRIVATE), "logs"))

            val report = StringBuilder()
            for (d in candidates) {
                val ok = probe(d)
                report.append(d.absolutePath).append(if (ok) " ✓ | " else " ✗ | ")
                if (ok) {
                    logFile = File(d, FILE_NAME)
                    break
                }
            }
            lastProbeReport = report.toString()
            if (logFile == null) lastError = "所有候选目录都不可写"
            Log.i(TAG, "日志目录探测: ${report}选中=${logFile?.parentFile?.absolutePath ?: "无"}")
            Log.i(TAG, "所有文件访问权限: ${hasAllFilesAccess(app)}")

            appCtx = app
            // 【5.6.9】版本变更 → 清旧日志（只清日志，配置不动）
            clearOnVersionChangeLocked(app)

            // 把探测结果落进日志本身，方便一眼确认（此时 logFile 已就绪）
            appendLine(stamp() + " 日志目录: ${logFile?.parentFile?.absolutePath ?: "不可写"}")
        }
    }

    /**
     * 运行期间拿到「所有文件访问」后，把落盘目录切回用户指定路径。
     *
     * 不做这一步的话有个很坑的时序：用户第一次打开 App 时还没授权，
     * 日志已经降级到应用目录并标记 resolved；之后去系统设置授权、切回 App，
     * init 直接 return，日志还是写在原来那个用户找不到的地方 ——
     * 表现就是「我明明授权了，还是没日志」。
     */
    private fun upgradeToUserDir() {
        val now = System.currentTimeMillis()
        if (now - lastUpgradeCheck < 3_000L) return
        synchronized(ringLock) {
            upgradeToUserDirLocked()
        }
    }

    private fun upgradeToUserDirLocked() {
        val cur = logFile?.parentFile?.absolutePath
        if (cur == USER_DIR) return
        lastUpgradeCheck = System.currentTimeMillis()
        val dir = File(USER_DIR)
        if (!probe(dir)) return

        val old = logFile
        val target = File(dir, FILE_NAME)
        try {
            // 把降级期间产生的日志接过去，排查时不会丢前半段
            if (old != null && old.exists() && old.length() in 1..(MAX_BYTES / 2)) {
                target.appendBytes(old.readBytes())
            }
        } catch (_: Throwable) {
        }
        logFile = target
        // 【5.6.9】切到的用户目录里可能躺着旧版本的 diag.log（降级期间没清到它）：
        // 版本判定还没做过就在这里补做，保证换目录后文件里也只有当前版本。
        if (!versionCleaned) appCtx?.let { clearOnVersionChangeLocked(it) }
        appendLine(stamp() + " 日志目录已切换到 $USER_DIR（之前: ${cur ?: "无"}）")
        Log.i(TAG, "日志目录已切换到 $USER_DIR")
    }

    /**
     * 【5.6.9】升级即清日志（调用方必须已持有 [ringLock]）。
     *
     * · 只在 **App 进程**执行：Hook 跑在 SystemUI 里，读不到本应用的 SP，
     *   也不该由它清用户文件（ctx.packageName 不等于本应用 ID 时直接返回）。
     * · versionCode 与 SP 记录一致 → 什么都不做；不一致 → 关流、删旧文件、
     *   写一行升级头、记录新版本号。每次版本更新都会执行（用户确认的口径）。
     * · **只动日志文件与这一个 SP key**，步态/小窗/白名单/急救卡等配置一律不碰。
     */
    private fun clearOnVersionChangeLocked(ctx: Context) {
        try {
            if (ctx.packageName != BuildConfig.APPLICATION_ID) return
            val sp = ctx.getSharedPreferences("safewalk_xsp", Context.MODE_PRIVATE)
            val old = sp.getInt(SP_KEY_LOG_VER, -1)
            val cur = BuildConfig.VERSION_CODE
            versionCleaned = true
            if (old == cur) return
            closeOut()
            try { logFile?.delete() } catch (_: Throwable) {}
            sp.edit().putInt(SP_KEY_LOG_VER, cur).apply()
            val head = if (old < 0) "首次记录版本" else "版本升级 $old→$cur"
            appendLine(stamp() + " [log] $head（v${BuildConfig.VERSION_NAME}）：旧版本日志已清空，本文件只含当前版本")
            Log.i(TAG, "[log] $head：diag.log 已重置")
        } catch (_: Throwable) {
            // 清日志失败不影响日志功能本身：宁可留着旧内容，也不能让 init 崩
        }
    }

    /**
     * 探针写入：能不能写，写一个字节就知道。
     *
     * `canWrite()` 在分区存储下不可信（会误报 true），只作为快速失败的前置判断。
     */
    private fun probe(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!dir.isDirectory) return false
            val p = File(dir, ".safewalk_probe")
            FileOutputStream(p).use { out ->
                out.write(1)
                out.flush()
            }
            p.delete()
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun log(msg: String) {
        if (!enabled) return
        Log.i(TAG, msg)
        val line = stamp() + " " + msg
        synchronized(ringLock) {
            ring.addLast(line)
            while (ring.size > RING_MAX) ring.removeFirst()
        }
        appendLine(line)
    }

    /**
     * 带节流的诊断日志：**高频回调里的重复原因必须用这个**。
     *
     * @param key        节流通道标识，不同通道互不干扰（如 "gait.hw"、"probe"）
     * @param reason     本条原因标识；原因变化时按 [changedMs] 更快放行，
     *                   原因不变时按 [stableMs] 放行
     * @param msg        真正要写的正文
     *
     * 状态跳变类日志（[ACTIVE] ✅ 激活、[walk] 收起、[FGS] 提起）信息量高、
     * 不会连发，仍走 [log]；只有「每帧都可能重复」的拦截/诊断才走这里。
     */
    @JvmStatic
    @JvmOverloads
    fun logThrottled(
        key: String,
        reason: String,
        msg: String,
        changedMs: Long = 2_000L,
        stableMs: Long = 10_000L
    ) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val emit = synchronized(throttleLock) {
            val st = throttleStates[key] ?: DiagThrottle.State().also { throttleStates[key] = it }
            val ok = DiagThrottle.shouldEmit(st, reason, now, changedMs, stableMs)
            if (ok) DiagThrottle.commit(st, reason, now)
            ok
        }
        if (emit) log(msg)
    }

    /** 会话重启时清掉某通道的节流状态，让新会话第一条诊断能立刻打出来。 */
    @JvmStatic
    fun resetThrottle(key: String) {
        synchronized(throttleLock) { throttleStates.remove(key) }
    }

    /**
     * 【5.6.0 省电】常开的写入流 + 批量 flush。
     *
     * 5.5.1 的 `appendLine` 是「每条日志一次 open→write→close」：`gaitTick` 一秒一条、
     * 传感器回调每 60ms 就可能来一条，等于每秒数十次 open/close 系统调用 ——
     * 这是整个模块里最隐蔽也最稳定的一块耗电，且全部发生在**主线程或传感器回调线程**上。
     *
     * 现在改成：文件句柄常开，写进 8KB 缓冲；累计 8 条 或 距上次 flush 超过 800ms
     * 才真实落盘一次。日志内容、顺序、格式一个字节都没变，只是把 I/O 次数压掉 ~90%。
     * 崩溃时最多丢最后 8 条（原来的做法同样会在析构失败时丢最后一条），
     * 进程退出与「停止预览」都会强制 [flushNow]。
     */
    private var out: BufferedOutputStream? = null
    private var outFor: File? = null
    private var pendingLines = 0
    private var lastFlushMs = 0L
    private var lastSizeCheckLines = 0

    private fun ensureOut(f: File): BufferedOutputStream? {
        val cur = out
        if (cur != null && outFor === f) return cur
        closeOut()
        val n = try {
            BufferedOutputStream(FileOutputStream(f, true), 8192)
        } catch (t: Throwable) {
            noteWriteFailure(f, t)
            return null
        }
        out = n
        outFor = f
        pendingLines = 0
        lastSizeCheckLines = 0
        // 新流建立时先记一次基准时间，避免第一条就触发一次 flush 判断
        lastFlushMs = SystemClock.uptimeMillis()
        return n
    }

    private fun closeOut() {
        val c = out ?: return
        out = null
        outFor = null
        try {
            c.flush()
        } catch (_: Throwable) {
        }
        try {
            c.close()
        } catch (_: Throwable) {
        }
    }

    /** 把缓冲里的内容真正写下去（进程退出 / 停止预览 / 写日志前切换目录时调用） */
    @JvmStatic
    fun flushNow() {
        synchronized(ringLock) {
            try {
                out?.flush()
            } catch (_: Throwable) {
            }
            pendingLines = 0
            lastFlushMs = SystemClock.uptimeMillis()
        }
    }

    /**
     * 原样追加一段文本（用于 Hook 回传的日志，正文自带上游时间戳）。
     */
    @JvmStatic
    fun writeRaw(text: String) {
        if (text.isEmpty()) return
        val f = logFile ?: return
        synchronized(ringLock) {
            try {
                val o = ensureOut(f) ?: return
                o.write(text.toByteArray())
                pendingLines += 1
                maybeFlush(o)
            } catch (t: Throwable) {
                closeOut()
                noteWriteFailure(f, t)
            }
        }
    }

    private fun appendLine(line: String) {
        val f = logFile ?: return
        // 和 writeRaw 用同一把锁：传感器回调、巡检 tick、日志回传可能来自不同
        // 线程，不加锁会出现「截断检查 / delete / append」交错，写出半行或
        // 刚删完又被另一线程 append 到旧 fd 的乱序内容。
        synchronized(ringLock) {
            try {
                // 长度检查每条都做是一次 stat 系统调用，改成每 32 条查一次，
                // 512KB 的上限对 32 条日志的误差完全不敏感。
                lastSizeCheckLines += 1
                if (lastSizeCheckLines >= 32) {
                    lastSizeCheckLines = 0
                    if (f.length() > MAX_BYTES) {
                        closeOut()          // 先关流：Windows/部分 FUSE 上删已打开文件会失败
                        f.delete()
                    }
                }
                val o = ensureOut(f) ?: return
                o.write((line + "\n").toByteArray())
                pendingLines += 1
                maybeFlush(o)
            } catch (t: Throwable) {
                closeOut()
                noteWriteFailure(f, t)
            }
        }
    }

    /** 累计 8 条或超过 800ms 落盘一次 */
    private fun maybeFlush(o: BufferedOutputStream) {
        val now = SystemClock.uptimeMillis()
        if (pendingLines >= 8 || now - lastFlushMs >= 800L) {
            try {
                o.flush()
            } catch (_: Throwable) {
            }
            pendingLines = 0
            lastFlushMs = now
        }
    }

    /** 写入失败只报一次：每 2 秒一条的 tick 日志刷屏比没日志更难查 */
    private fun noteWriteFailure(f: File, t: Throwable) {
        if (lastError == null) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "日志写入失败，已停止落盘: ${f.absolutePath} (${lastError})")
            logFile = null
        }
    }

    /** 当前实际落盘目录；未落盘时返回原因说明 */
    @JvmStatic
    fun status(): String {
        val dir = logFile?.parentFile?.absolutePath
        return if (dir != null) dir else "未落盘（${lastError ?: "日志尚未初始化"}）"
    }

    @JvmStatic
    fun logFilePath(): String = logFile?.absolutePath ?: ""

    @JvmStatic
    fun lastError(): String? = lastError

    /** 供页面显示：每个候选目录探测结果 */
    @JvmStatic
    fun probeReport(): String = lastProbeReport

    /**
     * 读日志文件末尾若干行，直接显示在实验区里。
     *
     * 用户手上没有 adb，出问题时唯一能提供的现场就是这段文字 ——
     * 以前只能说「去抓 logcat」，等于把排查门槛抬到了开发者。
     */
    @JvmStatic
    fun tail(maxLines: Int): String {
        val f = logFile ?: return "日志未落盘（${lastError ?: "尚未初始化"}）"
        return try {
            if (!f.exists()) return "日志文件还不存在：${f.absolutePath}"
            val all = f.readLines()
            val from = (all.size - maxLines).coerceAtLeast(0)
            val body = all.subList(from, all.size).joinToString("\n")
            if (body.isBlank()) "日志文件是空的：${f.absolutePath}" else body
        } catch (t: Throwable) {
            "读取失败：${t.message}（${f.absolutePath}）"
        }
    }

    /**
     * 本进程内存里的最近日志。
     *
     * 顺序：先给一个头部说明，再给环形缓冲正文。给 Hook 回传用，所以要限长，
     * 避免 Binder 事务超过 1MB。
     */
    @JvmStatic
    fun snapshot(): String {
        val sb = StringBuilder()
        sb.append("===== 进程日志回传 ").append(stamp()).append(" =====\n")
        sb.append("pid=").append(android.os.Process.myPid())
            .append(" uid=").append(android.os.Process.myUid())
            .append(" 目录=").append(status()).append('\n')
        synchronized(ringLock) {
            for (l in ring) sb.append(l).append('\n')
        }
        return if (sb.length > 60_000) sb.substring(sb.length - 60_000) else sb.toString()
    }

    /** Android 11+ 的「所有文件访问」是否已授予（低版本恒为 true） */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun hasAllFilesAccess(ctx: Context?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return try {
            Environment.isExternalStorageManager()
        } catch (_: Throwable) {
            false
        }
    }

    /** 用户可见目录是否真的可写（供页面提示用，不改变已选目录） */
    @JvmStatic
    fun userDirWritable(): Boolean = probe(File(USER_DIR))

    private fun stamp(): String {
        val c = java.util.Calendar.getInstance()
        return String.format(
            java.util.Locale.US, "%02d:%02d:%02d",
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND)
        )
    }
}
