package io.github.wumisc.safewalk

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.kyant.backdrop.catalog.utils.LiquidTabsController

/**
 * 首页底部液态玻璃导航栏的桥接层。
 *
 * MainActivity 仍是 Java + XML，Compose 部分收敛到这里。
 * 使用 Kyant0/AndroidLiquidGlass 的成品 LiquidBottomTabs：
 *  拖拽滑动 / 交互高光 / Shadow+InnerShadow / Lens 折射 全套特效。
 *
 * 布局：不额外画背景板，导航栏本体直接由 XML 底部悬浮容器定位
 * （activity_main.xml 中 compose_nav 处于 bottom|center_horizontal）。
 *
 * 【BUG 修复 · 三个导航栏 bug】
 * 上一版只把高亮写进 `selectedState`，全局左右滑也只平移了整个容器，
 * 而液态玻璃滑块 / 高亮 / 折射全部由组件内部的 dampedDragAnimation.value 驱动 ——
 * 于是「点关于特效不跟随」「首页点首页没反应」「页面滑动滑块不跟随」。
 * 现在所有外部交互统一经 [LiquidTabsController] 直接驱动同一个动画实例。
 */
object MainNav {

    /**
     * 导航栏当前选中项的外部可写状态。
     *
     * 为什么需要它：`selected` 原本只是 Compose 内部的 `remember` 状态，
     * 只有「点 tab」这一条路径会更新它。于是左右滑动切页（走
     * MainActivity.switchTab）、或者代码里主动切页时，页面已经换了，
     * 底部高亮还停在上一个 tab 上 —— 用户看到的就是「滑了但导航栏没反应」。
     */
    @Volatile
    private var selectedState: androidx.compose.runtime.MutableIntState? = null

    /**
     * 【BUG 修复】液态玻璃滑块动画控制器。
     *
     * 由 LiquidBottomTabs 在组合完成后通过 onControllerReady 回调注入。
     * 拿到它才能从 Java 侧（Activity 手势、点击 tab、代码切页）真正驱动
     * 阻尼动画，而不是只改一个高亮数字。
     */
    @Volatile
    private var controller: LiquidTabsController? = null

    /** ACTION_DOWN 时的选中 tab，滑动期间固定，避免基准漂移导致滑块乱跳 */
    @Volatile
    private var dragBaseIndex = 0
    /** 本次手势是否真的进入过「水平跟手拖拽」；纯点击 / 竖向滚动不该扰动导航栏 */
    @Volatile
    private var dragPressStarted = false

    /**
     * 由 Activity 侧调用，把高亮同步到指定 tab（0=首页，1=关于）。
     *
     * 关键：除了写状态，还必须 [LiquidTabsController.selectTab] 驱动一次动画。
     * 它不判断「是否与当前相同」，所以点击已选中的 tab 也会重放按压反馈，
     * 一次修掉「首页时点击首页没反应」和「点击关于滑动特效不跟随」。
     */
    @JvmStatic
    fun select(index: Int) {
        selectedState?.intValue = index
        controller?.selectTab(index)
    }

    /** 【手势导航联动】ACTION_DOWN：只记录基准 tab，不触发按压（避免点按钮也让导航闪一下） */
    @JvmStatic
    fun beginDrag() {
        dragBaseIndex = selectedState?.intValue ?: 0
        dragPressStarted = false
    }

    /** 【手势导航联动】ACTION_MOVE：首次进入水平跟手时触发按压高亮，之后实时更新滑块位置 */
    @JvmStatic
    fun updateDragProgress(progress: Float) {
        if (!dragPressStarted) {
            dragPressStarted = true
            controller?.press()
        }
        controller?.dragTo(dragBaseIndex, progress)
    }

    /**
     * 【手势导航联动】未切页时的收尾（ACTION_CANCEL / 未达阈值的 ACTION_UP）：
     * 释放按压态并把滑块弹回基准 tab。
     * 真正切页时 Activity 不调这里，由 switchTab → [select] 直接吸附到落点，
     * 避免「先弹回原位、再滑到新页」的抖动。
     */
    @JvmStatic
    fun endDrag() {
        if (!dragPressStarted) return   // 纯点击 / 竖向滚动：从未扰动过动画
        dragPressStarted = false
        controller?.release()
        controller?.selectTab(dragBaseIndex)
    }

    private fun navIcon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color(0xFF000000)),
        ).build()

    private val IconHome = navIcon("Home", "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z")
    private val IconAbout = navIcon("Info", "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z")

    @JvmStatic
    fun attach(composeView: ComposeView, initialSelected: Int, onSelect: (Int) -> Unit) {
        composeView.setContent {
            val state = remember { mutableIntStateOf(initialSelected) }
            // 把状态句柄交给 Activity 侧；用 SideEffect 保证只在组合成功后赋值
            androidx.compose.runtime.SideEffect {
                selectedState = state
            }
            val selected = state.intValue
            // 折射源 backdrop：交给导航栏内部使用（自身 tabsBackdrop 折射）
            val backdrop = rememberLayerBackdrop()

            // 图标与文字颜色必须跟着主题走。
            // 原来写死 Color.Black：玻璃底在深色下是半透明的深色，黑字等于隐形。
            val fg = if (isSystemInDarkTheme()) {
                Color.White.copy(alpha = 0.92f)
            } else {
                Color.Black.copy(alpha = 0.85f)
            }

            LiquidBottomTabs(
                selectedTabIndex = { selected },
                onTabSelected = { index ->
                    state.intValue = index
                    onSelect(index)
                },
                backdrop = backdrop,
                tabsCount = 2,
                modifier = Modifier.width(240.dp),
                // 【BUG 修复】拿回动画控制器：点击 / 全局左右滑 / 代码切页
                // 三条路径从此都能直接驱动液态玻璃滑块特效。
                onControllerReady = { ctrl -> controller = ctrl },
            ) {
                val items = listOf(IconHome to "首页", IconAbout to "关于")
                items.forEachIndexed { index, (icon, label) ->
                    LiquidBottomTab({ state.intValue = index; onSelect(index) }) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = fg,
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                        Text(
                            text = label,
                            style = TextStyle(
                                color = fg,
                                fontSize = 12.sp,
                            )
                        )
                    }
                }
            }
        }
    }
}
