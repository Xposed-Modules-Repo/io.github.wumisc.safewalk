package io.github.wumisc.safewalk

/**
 * 传感器时钟换算（5.7.6 新增）。
 *
 * 【为什么必须要有这一层】
 * 真机导出的 gait_trace 里，3523 个样本只有 570 个不同时间戳，83.8% 的相邻样本
 * 时间戳完全相同 —— 因为回调里用的是 `System.currentTimeMillis()`。
 * 这批机器（HyperOS 4 / 部分厂商 ROM）对 SensorEvent 做了批处理上报：
 * 传感器硬件按 ~66ms 一包把事件攒起来再一起抛给应用，应用层逐条回调时墙钟
 * 早就走完了，于是同一包里的 6~8 帧拿到一模一样的时间。
 *
 * 后果是整条判定链的数学全被污染：
 *  · 步间隔 `now - lastPeakTime` 变 0 或 66ms 的整数倍，真实 900ms 的步子
 *    可能被记成 66ms（判「步频过高」丢弃）或 132ms；步频窗口整段失效；
 *  · 包络衰减 `exp(-dt/1500)` 在 dt=0 时几乎不衰减，阈值被前面的瞬态顶住不放；
 *  · 判停时钟、静置计时用的也是同一个钟，同样一帧一帧地"没走时间"。
 *
 * `SensorEvent.timestamp` 是硬件单调时钟（开机后的纳秒），逐帧唯一、不受批处理
 * 影响，才是算间隔的正确数据源。
 *
 * 但它不能直接拿去跟 `System.currentTimeMillis()` 比：上层大量逻辑（lastStepTs、
 * 判停、冷却）都在墙钟域里。所以这里把传感器纳秒**线性映射到墙钟域**：
 * 首帧取一次偏移 `offset = wallNow - ns/1e6`，之后每帧 `ns/1e6 + offset`。
 * 结果既保留硬件时钟的逐帧间隔精度，又和墙钟同域，下游一处都不用改。
 *
 * 只在传感器回调线程使用，不需要加锁。
 */
object SensorClock {

    /** 纳秒 → 毫秒 的换算系数（整数除法会丢 0.5ms，这里显式取整到毫秒） */
    private const val NS_PER_MS = 1_000_000L

    private var offsetMs = 0L
    private var inited = false

    /**
     * 把 `SensorEvent.timestamp`（纳秒）换算成与墙钟同域的毫秒。
     *
     * @param ns       e.timestamp；<= 0 或明显不合理时退化为墙钟，绝不返回倒退值
     * @param wallNow  本帧处理时的墙钟（传入 System.currentTimeMillis()）
     */
    @JvmStatic
    fun msFromNanos(ns: Long, wallNow: Long): Long {
        // 个别 ROM 的合成传感器会给 0 或负数，这类帧没有可信时钟，直接用墙钟
        if (ns <= 0L) return wallNow
        if (!inited) {
            offsetMs = wallNow - ns / NS_PER_MS
            inited = true
        }
        val mapped = ns / NS_PER_MS + offsetMs
        // 开机时钟偶尔出现微小回跳（校时/休眠唤醒），单调化避免下游算出负间隔
        return if (mapped < lastMapped) lastMapped else mapped.also { lastMapped = it }
    }

    private var lastMapped = Long.MIN_VALUE

    /** 重置偏移（重新注册传感器、或换了一路数据源时调用） */
    @JvmStatic
    fun reset() {
        inited = false
        offsetMs = 0L
        lastMapped = Long.MIN_VALUE
    }
}
