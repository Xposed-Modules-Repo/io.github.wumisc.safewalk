package io.github.wumisc.safewalk

/**
 * 【诊断上传冷却】纯逻辑，不依赖 android.\*，可跑 JVM 单测。
 *
 * ## 为什么要有它
 *
 * 5.7.6 之前「关于 → 上传诊断日志」没有任何节流：`doUploadLog()` 每点一次就起一个线程
 * 发一次 POST，按钮在进行中也不禁用。用户手欠连点几下，后端就多收几条 60KB 的记录；
 * 更糟的是这属于「自己人刷自己的额度」——Cloudflare 免费计划的请求与 D1 写入额度
 * 是共享的（100k/天），被刷掉之后真实反馈当天全部 500。
 *
 * ## 口径
 *
 * · 同机 **10 分钟** 内只允许成功发起一次；失败（网络不通 / 服务 500）不占冷却，
 *   允许马上重试 —— 用户网络抖一下不该被罚站十分钟。
 * · 时钟回拨（`now < last`）视为可用，并把状态当脏值重置。
 */
object UploadCooldown {

    /** 冷却窗口：10 分钟。 */
    const val COOLDOWN_MS: Long = 10 * 60 * 1000L

    /** 现在是否允许发起上传。 */
    @JvmStatic
    fun shouldAllow(lastOkTs: Long, now: Long): Boolean {
        if (lastOkTs <= 0L) return true
        if (now < lastOkTs) return true
        return now - lastOkTs >= COOLDOWN_MS
    }

    /** 还要等多久（毫秒）；0 表示现在就能传。 */
    @JvmStatic
    fun remainingMs(lastOkTs: Long, now: Long): Long {
        if (shouldAllow(lastOkTs, now)) return 0L
        return COOLDOWN_MS - (now - lastOkTs)
    }

    /** 给用户看的剩余时间：向上取整到分钟，最少 1 分钟。 */
    @JvmStatic
    fun remainingMinutes(lastOkTs: Long, now: Long): Int {
        val remain = remainingMs(lastOkTs, now)
        if (remain <= 0L) return 0
        return ((remain + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    }
}
