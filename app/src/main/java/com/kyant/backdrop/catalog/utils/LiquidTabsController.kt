package com.kyant.backdrop.catalog.utils

/**
 * 【BUG 修复 · 液态玻璃导航栏滑动特效联动】
 * 把 LiquidBottomTabs 内部的 [DampedDragAnimation] 以受控方式暴露给 Java 侧
 * （io.github.wumisc.safewalk.MainNav）。
 *
 * ## 三个 bug 的共同根因
 * 液态玻璃滑块 / 交互高光 / lens 折射的位置全部由 `dampedDragAnimation.value`
 * 驱动，而上一版 MainNav 只做了两件事：
 *   ① `select(index)` 只改 `selectedState.intValue`，靠库内部一条脆弱的
 *      `snapshotFlow { currentIndex }.drop(1)` 间接驱动动画 —— 点击**已选中**的 tab
 *      值没变化、flow 不发射，于是「首页时点击首页没反应」「点击关于特效不跟随」；
 *   ② 全局左右滑只写了 Compose 容器的 `Modifier.offset(x = ...)`，整个导航栏
 *      一起平移，**完全没有碰 dampedDragAnimation**，所以「软件滑动时滑块不跟随」。
 *
 * ## 修复思路
 * 不再绕 snapshotFlow，外部（Activity 手势 / 点击 tab / 代码切页）统一通过本控制器
 * 直接调用同一个 [DampedDragAnimation] 实例的 `animateToValue` / `updateValue`，
 * 三条路径共用一套特效，行为确定、不依赖状态流的发射时机。
 *
 * 本类只包装公开 API（press / release / updateValue / animateToValue），
 * 不修改阻尼动画本身的实现，也不改动玻璃渲染层。
 */
class LiquidTabsController internal constructor(
    private val animation: DampedDragAnimation,
    private val tabsCount: Int
) {
    /** tab 值域上限，2 个 tab 时是 1f；防止 tabsCount<=1 时出现 0..-1 的非法区间 */
    private val maxIndex: Float
        get() = (tabsCount - 1).toFloat().coerceAtLeast(0f)

    /**
     * 选中某个 tab。
     *
     * 刻意**不判断「是否与当前相同」**：即使点击已选中的 tab，也重放一次
     * `animateToValue`（内部带 press/release），于是用户能看到明确的按压反馈，
     * 修掉「首页时点击首页没反应」。同时它把滑块位置强制对齐到目标 tab，
     * 修掉「点击关于滑动特效不跟随」。
     */
    fun selectTab(index: Int) {
        animation.animateToValue(index.toFloat().coerceIn(0f, maxIndex))
    }

    /**
     * 外部全局左右滑跟手。
     *
     * @param baseIndex 手指按下时的选中 tab（避免滑动过程中基准漂移）
     * @param progress  归一化进度 [-1,1]：负 = 左滑（趋向更大 index），
     *                  正 = 右滑（趋向更小 index）。放大 1.6 倍让滑块跟手明显，
     *                  再用 coerceIn 钳在合法 tab 区间内，不会滑出边界。
     */
    fun dragTo(baseIndex: Int, progress: Float) {
        val p = progress.coerceIn(-1f, 1f)
        val target = (baseIndex.toFloat() - p * maxIndex * 1.6f).coerceIn(0f, maxIndex)
        animation.updateValue(target)
    }

    /** 手指按下：触发按压高亮 / 折射渐显，让「滑动」和「点击」视觉语言一致 */
    fun press() = animation.press()

    /**
     * 抬手：释放按压态。最终落点由 Activity 判定切页后经 [selectTab] 吸附，
     * 未达阈值时则由 selectTab 把滑块弹回原 tab。
     */
    fun release() = animation.release()
}
