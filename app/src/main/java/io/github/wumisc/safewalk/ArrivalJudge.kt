package io.github.wumisc.safewalk

/**
 * 「到达某类场所」判定器（纯逻辑，不碰定位与通知，便于单测）。
 *
 * 【为什么需要它】
 * 证件提醒和社交提示原来挂在 Hook 的 `triggerMovement()` 上——只要判定「正在持续
 * 步行」就立刻推送，跟你人在哪儿毫无关系。于是沙发上起身走两步、屋里踱来踱去，
 * 都会收到一条「身份证、护照一定要贴身放好」和一大段「【求职】优先青旅…」。
 * 这两条内容的语义明明是「到某类场合该注意什么」，却变成了「只要你走路就念一遍」，
 * 是典型的**触发条件与内容语义错配**。
 *
 * 现在改为：定位 → 反编码地址 → 词库判类（求职/约会/聚会/旅游）→ 本判定器确认
 * 「确实到了这类场所并停留」才放行。
 *
 * 【三条闸门】
 * 1. **停留确认**：连续 [staySamples] 次采样都命中同一类别才算「到达」。
 *    单次命中可能只是路过、或反编码把邻近商圈的名字带进来了。
 * 2. **类别冷却**：同一类别 [cooldownMs] 内只提醒一次，避免在同一商圈里
 *    反复进出餐厅/商场时连环弹。
 * 3. **离开复位**：采样到「不在已知场所」时清空候选，下次到访重新计时——
 *    否则「隔壁商场 → 走开 → 再回来」会被当成一次连续的停留。
 */
class ArrivalJudge @JvmOverloads constructor(
    private val staySamples: Int = 2,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {

    companion object {
        /** 同一类场所的默认冷却：6 小时。中午在商圈吃饭、下午又去同一商圈，
         *  不该弹两次；但隔天再去应该能提醒到。 */
        const val DEFAULT_COOLDOWN_MS = 6 * 60 * 60 * 1000L
    }

    private var candidate: String? = null
    private var hits = 0
    private val lastFiredAt = HashMap<String, Long>()

    /** 当前正在确认中的类别（未达停留门槛也算），便于日志排查 */
    val pendingKind: String?
        get() = candidate

    /** 已确认的停留次数 */
    val pendingHits: Int
        get() = hits

    /**
     * 喂入一次采样。
     *
     * @param kind 本次采样判定出的场所类别；null 表示「不在任何已知场所」
     * @param now  采样时间戳（毫秒）
     * @return 本次应当触发提醒的类别；null 表示还不到时候
     */
    fun onSample(kind: String?, now: Long): String? {
        if (kind.isNullOrBlank()) {
            // 离开已知场所：候选清空，下次到访重新计时
            candidate = null
            hits = 0
            return null
        }
        if (kind == candidate) {
            hits++
        } else {
            candidate = kind
            hits = 1
        }
        if (hits < staySamples) return null

        val last = lastFiredAt[kind] ?: 0L
        if (last != 0L && now - last < cooldownMs) return null

        lastFiredAt[kind] = now
        return kind
    }

    /** 某类别上次提醒时间（0 表示从未），用于持久化与诊断 */
    fun lastFiredOf(kind: String): Long = lastFiredAt[kind] ?: 0L

    fun reset() {
        candidate = null
        hits = 0
        lastFiredAt.clear()
    }
}
