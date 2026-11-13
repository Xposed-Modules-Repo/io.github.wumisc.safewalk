package io.github.wumisc.safewalk

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一条无障碍事件记录（调试面板的一行）。
 *
 * @param ts       毫秒时间戳，面板自己格式化成 `HH:mm:ss.SSS`
 * @param type     `AccessibilityEvent.eventType` 原值
 * @param pkg      来源包名
 * @param cls      来源控件类名（短名）
 * @param text     事件文本（`getText()` 拼接，通常只有 TEXT_CHANGED / NOTIFICATION 有）
 */
data class A11yEventRecord(
    val ts: Long,
    val type: Int,
    val pkg: String,
    val cls: String,
    val text: String
)

/**
 * 调试面板的事件总线。
 *
 * ## 为什么用内存环形缓冲而不是写文件
 *
 * 面板的用途是「当下这一屏为什么读不对」，看的是刚刚发生的几十条事件。
 * 写文件会引入 IO、轮转、权限一堆问题，还会跟诊断日志抢同一个文件。
 * 内存里留最近 [CAP] 条，关掉面板就没了——这正好是调试工具该有的生命周期。
 *
 * ## 为什么容量是 300
 *
 * 一个列表页滚动时每秒能产生几十条 CONTENT_CHANGED。300 条约等于最近 10 秒的
 * 完整现场，够看清「滚动时焦点跳到哪去了」；再大就只是拖慢面板渲染。
 *
 * ## 线程
 *
 * `onAccessibilityEvent` 跑在服务主线程，面板的 UI 刷新也在主线程，理论上不存在
 * 并发。但 `dumpToLog()` 可能从别的线程调用（实验区长按），所以所有入口都加锁，
 * 并额外用 [CopyOnWriteArrayList] 保护监听器集合的遍历。
 */
object A11yEventBus {

    /** 最多保留多少条事件。 */
    const val CAP = 300

    private val buffer = ArrayDeque<A11yEventRecord>(CAP)
    private val listeners = CopyOnWriteArrayList<Runnable>()

    /** 追加一条；超出容量时丢最旧的。 */
    @Synchronized
    fun add(r: A11yEventRecord) {
        if (buffer.size >= CAP) buffer.removeFirst()
        buffer.addLast(r)
    }

    /** 当前快照（拷贝，调用方随便遍历）。 */
    @Synchronized
    fun snapshot(): List<A11yEventRecord> = buffer.toList()

    @Synchronized
    fun clear() {
        buffer.clear()
    }

    @Synchronized
    fun size(): Int = buffer.size

    /**
     * 注册刷新回调。
     *
     * 参数类型是 [Runnable] 而不是 `() -> Unit`：面板 Activity 是 Java 写的，
     * Kotlin 的函数类型在 Java 侧要写 `new Function0<Unit>() { … }`，
     * 可读性极差；`Runnable` 在两边都是 lambda，直接传即可。
     */
    fun addListener(l: Runnable) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Runnable) {
        listeners.remove(l)
    }

    /** 通知面板刷新。服务侧已做节流，这里不再重复限频。 */
    fun notifyChanged() {
        for (l in listeners) {
            try {
                l.run()
            } catch (_: Throwable) {
                // 面板已经销毁时回调会抛，忽略即可，不能让它影响采集
            }
        }
    }
}
