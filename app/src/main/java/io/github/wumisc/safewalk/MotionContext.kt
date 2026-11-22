package io.github.wumisc.safewalk

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 「我到底在走路还是没动」的**环境证据**判定（纯逻辑，不依赖 android.*，可 JVM 单测）。
 *
 * ## 为什么必须补这一条
 *
 * 到 5.5.1 为止，整套步态判定只有一种证据：**加速度波形像不像走路**。
 * 它由 11 道闸门（包络阈值、步频窗口、主轴一致率、连续步数、最大间隔、平均间隔、
 * 跨度、节律一致性、短促抖动、连续步流、硬件计步）把关，全部作用于同一路信号。
 *
 * 这在真机上有两个对称的翻车：
 *
 * 1. **该弹不弹**：手机在兜里 / 平着拿时通常是 LAY_FLAT，而 LAY_FLAT 在
 *    [GaitDetector] 与 [WalkConfirmPolicy] 里各被硬拦一次，唯一的补救分支
 *    「平放 + 硬件步数 ≥8」在缺少 `ACTIVITY_RECOGNITION` 的机型上又恒不成立
 *    （计步通道 STALE → 窗口内步数恒 0）。用户走了十几分钟一次没弹。
 * 2. **不该弹乱弹**：床上刷视频、沙发伸懒腰、副驾颠簸，在加速度上与走路**可以做得
 *    几乎一样**（0.5~2.5Hz 的规律起伏）。只调阈值只有两种结局：要么继续误触，
 *    要么把正常慢走一起杀掉。
 *
 * 缺的是一条**与波形正交**的证据：人真的在移动吗？移动速度是步行还是车载？
 * 位置是不是十分钟没变过？这三个问句与加速度无关，任何一条成立都能直接定性。
 *
 * ## 三类结论
 *
 * | 结论 | 判据 | 对步态的作用 |
 * |---|---|---|
 * | [Verdict.VEHICLE] | 瞬时速度 ≥8m/s，或 1 分钟内行程 ≥800m | **否决**：驾驶/乘车一律不弹 |
 * | [Verdict.STILL] | 6 分钟以上所有定位点都在 30m 半径内 | **平放时否决**：床上/沙发刷视频 |
 * | [Verdict.WALKING] | 2 分钟内实际移动 ≥20m，且平均速度 ≤3m/s | **平放时放行**：补上缺陷 A 的出路 |
 * | [Verdict.UNKNOWN] | 样本不足 / 精度差 / 无定位权限 | **不影响**：退回 5.5.1 的纯波形判据 |
 *
 * ## 为什么阈值选这些
 *
 * - **8m/s = 28.8km/h**：市区堵车都到不了，只有真在车上才会持续到这个速度。
 *   步行 1.2~1.5m/s、跑步 3~5m/s、骑车 5~7m/s，都低于它。
 * - **30m 半径**：室外 GPS 静态漂移典型 5~15m，30m 留了两倍余量；而走出一个
 *   房间/一段人行道必然超过它，所以「真的没在移动」这件事判得干净。
 * - **6 分钟**：床上刷视频的典型停留远长于它；而正常走路不会 6 分钟原地打转。
 * - **20m / 2 分钟**：这已经是「慢悠悠散步」的下限（0.17m/s 的净位移）。
 *   再低就会把「站着等红灯时手机轻微漂移」算成走路。
 *
 * ## 零额外耗电
 *
 * 本类只做计算，不注册任何定位。数据由 [PlaceArrivalMonitor] 已有的
 * `requestLocationUpdates(60s / 150m)` 顺路喂进来（那时它本来就在收位置），
 * 因此这一步不新增一次唤醒、不新增一个 provider。
 */
object MotionContext {

    /** 环境结论 */
    enum class Verdict {
        /** 证据不足（无权限 / 只有一次定位 / 精度太差）——不影响任何既有判据 */
        UNKNOWN,
        /** 确实在移动，且速度符合步行 */
        WALKING,
        /** 长时间在一个小范围内不动 */
        STILL,
        /** 在车上（自驾或乘坐） */
        VEHICLE
    }

    /** 保留多长时间内的定位点（12 分钟够判「6 分钟没动」） */
    const val RETAIN_MS = 12 * 60_000L

    /** 车速下限（m/s）。28.8km/h，步行/跑步/骑车都达不到 */
    const val VEHICLE_SPEED_MPS = 8.0

    /** 用车速判车的观察窗口 */
    const val VEHICLE_SPAN_MS = 60_000L

    /** 窗口内行程超过这个米数也判车（应对 `getSpeed()` 恒为 0 的网络定位） */
    const val VEHICLE_TRIP_M = 800.0

    /** 步行判据：2 分钟内实际移动至少这么多米 */
    const val WALK_TRIP_M = 20.0

    /** 步行观察窗口 */
    const val WALK_SPAN_MS = 120_000L

    /** 步行速度上限（m/s）。超过就是骑车/坐车，不算走路 */
    const val WALK_MAX_SPEED_MPS = 3.0

    /** 静止判据：所有点都在这个半径内 */
    const val STILL_RADIUS_M = 30.0

    /** 静止判据的最短观察跨度 */
    const val STILL_MIN_SPAN_MS = 6 * 60_000L

    /** 精度差于这个值的定位点直接丢弃（室内网络定位常见 100~500m） */
    const val MAX_ACCURACY_M = 60.0f

    /** 一次定位样本（时间戳用 `Location.getTime()`，不是收到回调的时刻） */
    class Fix(
        @JvmField val ts: Long,
        @JvmField val lat: Double,
        @JvmField val lon: Double,
        @JvmField val speedMps: Float,
        @JvmField val accuracyM: Float
    )

    /** 两点间大圆距离（米）。Haversine，误差 <0.5%，判 20m/30m 足够 */
    @JvmStatic
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) +
                Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(minOf(1.0, sqrt(a)))
    }

    /**
     * 用一组定位点判环境。
     *
     * @param fixes 按时间升序的样本（可以乱序，本函数内部排序）
     * @param now   当前时刻（毫秒）
     */
    @JvmStatic
    fun verdict(fixes: List<Fix>, now: Long): Verdict {
        val usable = fixes
            .filter { it.ts > 0L && now - it.ts <= RETAIN_MS }
            .filter { it.accuracyM <= 0f || it.accuracyM <= MAX_ACCURACY_M }
            .sortedBy { it.ts }
        if (usable.size < 2) return Verdict.UNKNOWN

        val newest = usable[usable.size - 1]
        val oldest = usable[0]
        val span = newest.ts - oldest.ts
        if (span < 20_000L) return Verdict.UNKNOWN

        // ① 车载：先看「现在这一刻的速度」，再兜底看一分钟内的行程。
        // 顺序很重要 —— 车刚停下的几秒里瞬时速度为 0，但一分钟行程仍然巨大。
        if (newest.speedMps > 0f && newest.speedMps >= VEHICLE_SPEED_MPS) return Verdict.VEHICLE
        val vehicleWindow = usable.filter { newest.ts - it.ts <= VEHICLE_SPAN_MS }
        if (vehicleWindow.size >= 2) {
            val winSpan = newest.ts - vehicleWindow[0].ts
            if (winSpan >= VEHICLE_SPAN_MS / 2) {
                val trip = netDisplacement(vehicleWindow)
                if (trip >= VEHICLE_TRIP_M) return Verdict.VEHICLE
            }
        }

        // ② 静止：先看长窗口，避免「床上刷了 10 分钟」被 2 分钟窗口判成走路。
        if (span >= STILL_MIN_SPAN_MS && maxRadius(usable) <= STILL_RADIUS_M) {
            return Verdict.STILL
        }

        // ③ 步行：最近 2 分钟**净位移**够多，且速度没到骑车/开车的档。
        //
        // 刻意用净位移（首末直线距离）而不是路径长度：GPS 静态漂移是
        // 「在原地前后晃 5 米」，逐点累加能轻易凑出几十米，这正是
        // 「躺在床上被判成走路」的经典来源。净位移把这类抖动直接压平
        // ——代价是绕圈散步会被低估，但那属于「宁可少弹一次」，不是误触。
        val walkWindow = usable.filter { newest.ts - it.ts <= WALK_SPAN_MS }
        if (walkWindow.size >= 2) {
            val winSpan = newest.ts - walkWindow[0].ts
            if (winSpan >= 30_000L) {
                val net = netDisplacement(walkWindow)
                val speed = net / (winSpan / 1000.0)
                if (net >= WALK_TRIP_M && speed <= WALK_MAX_SPEED_MPS) return Verdict.WALKING
            }
        }
        return Verdict.UNKNOWN
    }

    /** 这条结论能否支撑「平放也是在走路」 */
    @JvmStatic
    fun allowsFlatWalking(v: Verdict): Boolean = v == Verdict.WALKING

    /** 这条结论是否必须硬否决这次激活 */
    @JvmStatic
    fun vetoesActivation(v: Verdict): Boolean = v == Verdict.VEHICLE

    /** 日志用的一句话 */
    @JvmStatic
    fun describe(v: Verdict): String = when (v) {
        Verdict.WALKING -> "在移动（步行速度区间）"
        Verdict.STILL -> "长时间原地未移动"
        Verdict.VEHICLE -> "车速/大行程 → 判定在车上"
        Verdict.UNKNOWN -> "证据不足（无定位或样本太少）→ 不影响波形判据"
    }

    /** 相邻点距离之和（含中途绕行，仅诊断用，不参与判定） */
    private fun sumOfDistances(list: List<Fix>): Double {
        var sum = 0.0
        for (i in 1 until list.size) {
            sum += distanceMeters(list[i - 1].lat, list[i - 1].lon, list[i].lat, list[i].lon)
        }
        return sum
    }

    /** 首点到末点的直线距离（净位移）。判车速与步行都用它，避免漂移累积成假行程 */
    private fun netDisplacement(list: List<Fix>): Double {
        return distanceMeters(list[0].lat, list[0].lon, list[list.size - 1].lat, list[list.size - 1].lon)
    }

    /** 所有点相对首点的最大距离 */
    private fun maxRadius(list: List<Fix>): Double {
        var r = 0.0
        val a = list[0]
        for (f in list) {
            val d = distanceMeters(a.lat, a.lon, f.lat, f.lon)
            if (d > r) r = d
        }
        return r
    }

    /**
     * 进程内滚动缓冲：接收定位、按需给出结论。
     *
     * 刻意做成**被动接收**：谁在收定位就顺手喂它，本类永不主动请求位置更新。
     * 这是「补证据但不加耗电」的实现前提。
     */
    class Tracker {
        private val fixes = ArrayList<Fix>(32)
        private val lock = Any()

        /** 喂一个定位点；重复时间戳 / 精度过差直接丢弃 */
        fun feed(fix: Fix) {
            if (fix.ts <= 0L) return
            if (fix.accuracyM > 0f && fix.accuracyM > MAX_ACCURACY_M) return
            synchronized(lock) {
                if (fixes.isNotEmpty() && fixes[fixes.size - 1].ts >= fix.ts) {
                    // 时间戳没前进（同一 Location 被重复回调）：替换掉，避免累积垃圾
                    fixes[fixes.size - 1] = fix
                    trim(fix.ts)
                    return
                }
                fixes.add(fix)
                trim(fix.ts)
            }
        }

        fun clear() {
            synchronized(lock) { fixes.clear() }
        }

        /** 当前结论 */
        fun verdict(now: Long): Verdict {
            val snapshot = synchronized(lock) { ArrayList(fixes) }
            return verdict(snapshot, now)
        }

        /** 样本数（诊断日志用） */
        fun size(): Int = synchronized(lock) { fixes.size }

        private fun trim(now: Long) {
            while (fixes.isNotEmpty() && now - fixes[0].ts > RETAIN_MS) fixes.removeAt(0)
            while (fixes.size > 64) fixes.removeAt(0)
        }
    }

    /** 全局共享实例：App 进程里只有一份，定位与步态判定都在同一进程 */
    @JvmField
    val tracker = Tracker()

    /** 供 Java 侧一行调用：当前环境结论 */
    @JvmStatic
    fun verdictNow(): Verdict = tracker.verdict(System.currentTimeMillis())
}
