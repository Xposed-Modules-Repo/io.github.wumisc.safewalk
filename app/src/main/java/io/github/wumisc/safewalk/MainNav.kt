package io.github.wumisc.safewalk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * 首页底部液态玻璃导航栏的桥接层。
 *
 * MainActivity 仍是 Java + XML，Compose 部分收敛到这里。
 * 使用 Kyant0/AndroidLiquidGlass 的成品 LiquidBottomTabs：
 *  拖拽滑动 / 交互高光 / Shadow+InnerShadow / Lens 折射 全套特效。
 *
 * 布局：不额外画背景板，导航栏本体直接由 XML 底部悬浮容器定位
 * （activity_main.xml 中 compose_nav 处于 bottom|center_horizontal）。
 */
object MainNav {

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
            var selected by remember { mutableIntStateOf(initialSelected) }
            // 折射源 backdrop：交给导航栏内部使用（自身 tabsBackdrop 折射）
            val backdrop = rememberLayerBackdrop()

            LiquidBottomTabs(
                selectedTabIndex = { selected },
                onTabSelected = { index ->
                    selected = index
                    onSelect(index)
                },
                backdrop = backdrop,
                tabsCount = 2,
                modifier = Modifier.width(240.dp),
            ) {
                val items = listOf(IconHome to "首页", IconAbout to "关于")
                items.forEachIndexed { index, (icon, label) ->
                    LiquidBottomTab({ selected = index; onSelect(index) }) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = Color.Black.copy(alpha = 0.85f),
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                        Text(
                            text = label,
                            style = TextStyle(
                                color = Color.Black.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                            )
                        )
                    }
                }
            }
        }
    }
}
