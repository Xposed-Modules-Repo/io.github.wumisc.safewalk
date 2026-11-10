package com.kyant.backdrop.catalog.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.utils.DampedDragAnimation
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.catalog.utils.LiquidTabsController
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** 导航栏圆角半径：56dp 高，28dp 正好是外接半圆，视觉上仍是胶囊 */
private val TabsCorner = 28.dp

/**
 * 显式圆角 + **不做** 50% 百分比。
 * `Capsule()` 内部是 `RoundedCornerShape(50%)`，在非整数像素高度（56dp × 密度）上
 * 会算出 27.5px 之类的半径，shader 画圆角时没有覆盖采样，边缘就出现一圈半透明毛刺。
 * 固定 28dp 后半径落在整像素上，锯齿明显减少。
 */
private val TabsShape: Shape = RoundedCornerShape(TabsCorner)

/**
 * 边缘「收口」：在玻璃图层之上再描一圈极细的同色边。
 *
 * 为什么需要它：液态玻璃的 blur / lens 是 AGSL shader 直接栅格化的，形状边缘没有
 * 多重采样抗锯齿；再加上 8dp 模糊会把边缘外的透明像素混进来，圆角内侧会留一条
 * 半透明的灰边（用户说的「毛边」）。用 1dp 描边压住这一圈，圆角就干净了。
 * 描边颜色跟主题底色一致、且做垂直渐变，看起来是玻璃边的自然高光，不像加了个框。
 */
private fun Modifier.cleanEdge(corner: Dp, brush: Brush, width: Dp = 1.dp): Modifier =
    drawWithContent {
        drawContent()
        val stroke = width.toPx()
        // 内缩半个描边宽：描边以路径为中心线，不内缩的话外侧会被自身边界裁掉一半，
        // 圆角处就会出现「半边粗半边细」的台阶，反而更像毛边。
        val inset = stroke / 2f
        val r = (corner.toPx() - inset).coerceAtLeast(0f)
        drawRoundRect(
            brush = brush,
            topLeft = Offset(inset, inset),
            size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = stroke)
        )
    }

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    /** 【BUG 修复】把内部阻尼动画的控制权交给外部，让点击 / 全局左右滑都能驱动滑块特效 */
    onControllerReady: ((LiquidTabsController) -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f)
        else Color(0xFF121212).copy(0.4f)
    // 边缘收口用色。
    //
    // 【为什么亮色要单独一套】3.5 在两种主题下都用了「白 0.85→0.25」描边：
    // 暗色底上白边是自然高光，毛边被压住，用户反馈「深色好多了」；
    // 亮色底（#F5F5F7 一类）上白边和背景几乎同色，等于没画，那 1~2dp
    // 半透明灰带照样露出来 —— 这就是「浅色页面还有毛边」的直接原因。
    //
    // 现在亮色改用**低透明度**的纯白（只做一丝边光，不形成白框），
    // 真正的去毛边交给外层 `graphicsLayer(clip = true)` 的硬件抗锯齿裁切。
    val edgeBrush =
        if (isLightTheme) {
            Brush.verticalGradient(
                listOf(Color(0xFFFFFFFF).copy(0.55f), Color(0xFFFFFFFF).copy(0.10f))
            )
        } else {
            Brush.verticalGradient(
                listOf(Color(0xFFFFFFFF).copy(0.22f), Color(0xFFFFFFFF).copy(0.05f))
            )
        }

    // 亮色下 blur 半径收敛：边缘内侧的半透明过渡宽度与模糊半径正相关，
    // 8dp 在浅底上是一条肉眼可见的灰箍，6dp 只剩极细的一圈。
    val blurRadius = if (isLightTheme) 6.dp else 8.dp

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    // 【BUG 修复】内部拖拽结束直接回执切页。
                    // 原先靠 snapshotFlow { currentIndex }.drop(1) 间接转发，
                    // 既容易漏发射，又会和外部手势驱动打架；现在由外部 MainNav.select
                    // 统一 animateToValue 吸附，滑块 / 高亮 / 折射一起到位。
                    onTabSelected(targetIndex)
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        // 【BUG 修复】动画驱动收敛为单一来源：
        //   · 外部（点击 tab / 全局左右滑 / 代码切页）→ LiquidTabsController；
        //   · 组件内部直接拖滑块 → 上面的 onDrag / onDragStopped。
        // 删掉原先两条 snapshotFlow 链：`.drop(1)` 会吞掉首次变化，
        // 「再点一次已选中的 tab」值不变则根本不发射 —— 正是用户反馈的
        // 「点击关于特效不跟随 / 首页点首页没反应 / 页面滑动滑块不跟随」三个 bug。
        LaunchedEffect(dampedDragAnimation) {
            onControllerReady?.invoke(LiquidTabsController(dampedDragAnimation, tabsCount))
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                    // 【浅色毛边根因修复】AGSL 是直接栅格化的，圆角处没有覆盖率采样，
                    // 形状边界会输出一条 1~2dp 的半透明带；叠在浅色底上就是一圈灰箍
                    // （深色底上几乎不可见，所以「深色好多了」）。
                    // 让 RenderNode 按同一形状做硬件裁剪：outline 裁剪自带抗锯齿，
                    // 把那条半透明带和外溢的模糊采样一起裁掉，边界只剩 1px 过渡。
                    //
                    // 代价：layerBlock 里按住时的轻微放大（+5%）会被裁掉，视觉上只剩
                    // 高亮和透镜，不再「膨胀」。比起圆角毛边，这个损失可以接受。
                    shape = TabsShape
                    clip = true
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { TabsShape },
                    effects = {
                        vibrancy()
                        blur(blurRadius.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { /* 不画实色底，仅保留 blur/lens 折射，消除毛边 */ }
                )
                // 圆角收口：盖住 shader 栅格化留下的半透明毛边（见 cleanEdge 注释）
                .cleanEdge(TabsCorner, edgeBrush)
                .then(interactiveHighlight.modifier)
                .height(56f.dp)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { TabsShape },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(
                                24f.dp.toPx() * progress,
                                24f.dp.toPx() * progress
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { TabsShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = progress)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f)
                            else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                // 滑块同样收口：它是画面上最亮的元素，圆角毛边最显眼
                .cleanEdge(TabsCorner, edgeBrush, 0.9.dp)
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}
