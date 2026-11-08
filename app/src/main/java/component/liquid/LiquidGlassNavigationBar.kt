// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package component.liquid

// Liquid glass rendering effect adapted from Kyant0 / AndroidLiquidGlass
// Powered by Miuix (Apache-2.0)

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 单个 tab 胶囊宽高 */
private val IndicatorWidth = 68.dp
private val IndicatorHeight = 44.dp

/**
 * iOS26 风格液态玻璃悬浮导航栏 —— 完整复刻 Kyant0/AndroidLiquidGlass
 * 的 LiquidBottomTabs 交互（miuix-blur 0.9.4 原语实现）：
 *
 *  - 真折射透镜：drawBackdrop + vibrancy + blur + lens（折射/色散）
 *  - 光泽高光：drawBackdrop.highlight + BloomStroke 双光源镜面高光
 *  - 设备倾斜光效：rememberDeviceTilt 陀螺仪驱动高光光源随重力移动
 *  - 边缘内阴影：innerShadow 玻璃内侧立体描边
 *  - 拖拽滑动：选中指示胶囊随手指水平拖动，松手弹簧吸附到最近 tab
 *  - 面板跟随：拖拽时整条导航栏轻微反向偏移（视差）
 */
@Composable
fun IosLiquidGlassNavigationBar(
    items: List<NavigationItem>,
    selectedIndex: Int,
    onItemClick: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    isBlurActive: Boolean,
    modifier: Modifier = Modifier,
    badge: (Int) -> (@Composable () -> Unit)? = { null },
) {
    val pillShape = RoundedCornerShape(40.dp)
    val innerPillShape = RoundedCornerShape(26.dp)
    val accent = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val container = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = if (isBlurActive) 0.5f else 1f)
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f

    // 设备倾斜光效：陀螺仪重力方向驱动高光光源位移
    val tilt = rememberDeviceTilt()
    val tiltX = (tilt.value.gravityX * 0.12f).fastCoerceIn(-0.3f, 0.3f)
    val tiltY = (tilt.value.gravityY * 0.10f).fastCoerceIn(-0.25f, 0.25f)

    val animationScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dragValue = remember { Animatable(selectedIndex.toFloat()) }

    // 拖拽的指示胶囊位置（px），供三层共享
    val indicatorX = dragValue.value

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // tab 宽度（px）
        val tabWidthPx = with(density) {
            (constraints.maxWidth.toFloat() - 16.dp.toPx()) / items.size.coerceAtLeast(1)
        }

        // 拖拽手势：驱动 dragValue，松手吸附最近 tab
        val dragModifier = Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = {
                    animationScope.launch { dragValue.stop() }
                },
                onDragCancel = {
                    animationScope.launch { dragValue.animateTo(selectedIndex.toFloat()) }
                },
                onDragEnd = {
                    val nearest = dragValue.value.fastRoundToInt()
                        .fastCoerceIn(0, items.size - 1)
                    animationScope.launch {
                        dragValue.animateTo(nearest.toFloat(), spring(0.4f, 500f))
                    }
                    onItemClick(nearest)
                },
            ) { _, dragAmount ->
                animationScope.launch {
                    dragValue.snapTo(
                        (dragValue.value + dragAmount / tabWidthPx)
                            .fastCoerceIn(0f, (items.size - 1).toFloat())
                    )
                }
            }
        }

        // 1) 外层液态玻璃胶囊栏
        Box(
            modifier = Modifier
                .graphicsLayer {
                    // 面板视差：拖拽时整条导航轻微反向偏移
                    translationX = -(dragValue.value - selectedIndex) * tabWidthPx * 0.12f
                }
                .clip(pillShape)
                .innerShadow(pillShape) {
                    InnerShadow(
                        radius = 16.dp,
                        offset = DpOffset(0.dp, 0.dp),
                        color = Color.Black.copy(alpha = if (isDark) 0.35f else 0.2f),
                        alpha = 0.9f,
                    )
                }
                .then(
                    if (isBlurActive && backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                padding = maxOf(padding, 40.dp.toPx())
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = 0.45f,
                                )
                            },
                            highlight = {
                                Highlight(
                                    width = 3.dp,
                                    alpha = 0.7f,
                                    style = BloomStroke(
                                        color = Color.White.copy(alpha = 0.3f),
                                        blendMode = BlendMode.Screen,
                                        innerBlurRadius = 2.dp,
                                        primaryLight = LightSource(
                                            position = LightPosition(
                                                (0.5f + tiltX).fastCoerceIn(0.15f, 0.85f),
                                                (0.35f + tiltY).fastCoerceIn(0.1f, 0.65f),
                                                -0.1f,
                                            ),
                                            color = Color.White,
                                            intensity = 0.85f,
                                        ),
                                        secondaryLight = LightSource(
                                            position = LightPosition(0.5f, 0.85f, -0.6f),
                                            color = Color.White,
                                            intensity = 0.35f,
                                        ),
                                        dualPeak = true,
                                    ),
                                )
                            },
                            onDrawSurface = { drawRect(container) },
                        )
                    } else {
                        Modifier.background(container)
                    }
                )
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .height(56.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // 2) 选中指示胶囊：随拖拽水平移动 + 折射 + 高光 + 内阴影
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX =
                            indicatorX * tabWidthPx - (tabWidthPx - with(density) { IndicatorWidth.toPx() }) / 2f
                    }
                    .width(IndicatorWidth)
                    .height(IndicatorHeight)
                    .clip(innerPillShape)
                    .then(
                        if (isBlurActive && backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { innerPillShape },
                                effects = {
                                    vibrancy()
                                    blur(26.dp.toPx())
                                    lens(
                                        refractionHeight = 22.dp.toPx(),
                                        refractionAmount = 20.dp.toPx(),
                                        depthEffect = true,
                                        chromaticAberration = 0.6f,
                                    )
                                },
                                highlight = {
                                    Highlight(
                                        width = 2.dp,
                                        alpha = 0.9f,
                                        style = BloomStroke(
                                            color = if (isDark) Color.White.copy(alpha = 0.25f)
                                            else Color.White.copy(alpha = 0.45f),
                                            blendMode = BlendMode.Screen,
                                            innerBlurRadius = 2.dp,
                                            primaryLight = LightSource(
                                                position = LightPosition(
                                                    (0.5f + tiltX).fastCoerceIn(0.2f, 0.8f),
                                                    (0.3f + tiltY).fastCoerceIn(0.1f, 0.6f),
                                                    -0.1f,
                                                ),
                                                color = Color.White,
                                                intensity = 0.95f,
                                            ),
                                            secondaryLight = LightSource(
                                                position = LightPosition(0.5f, 0.9f, -0.5f),
                                                color = Color.White,
                                                intensity = 0.5f,
                                            ),
                                            dualPeak = true,
                                        ),
                                    )
                                },
                                onDrawSurface = { drawRect(Color.White.copy(alpha = if (isDark) 0.06f else 0.12f)) },
                            )
                        } else {
                            Modifier.background(accent.copy(alpha = 0.4f))
                        }
                    )
            )
        }

        // 3) 图标 + 文字层（可点击，可拖拽）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp)
                .then(dragModifier),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val contentColor = if (selected) accent else onSurface.copy(alpha = 0.75f)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(innerPillShape)
                        .clickable { onItemClick(index) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                ) {
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        Icon(
                            modifier = Modifier.size(22.dp),
                            imageVector = item.icon,
                            contentDescription = item.label,
                        )
                        Text(
                            text = item.label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    badge(index)?.invoke()
                }
            }
        }
    }
}
