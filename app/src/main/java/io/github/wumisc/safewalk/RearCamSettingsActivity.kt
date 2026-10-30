package io.github.wumisc.safewalk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 后置看路设置页（Compose + Material3 版本）。
 * 从首页「后置看路」磁贴长按进入。
 *
 * 只做 UI 交互，摄像头逻辑后续接入。
 */
class RearCamSettingsActivity : ComponentActivity() {

    private lateinit var sp: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = getSharedPreferences("safewalk_xsp", MODE_WORLD_READABLE)

        setContent {
            MaterialTheme {
                RearCamSettingsPage(
                    sp = sp,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RearCamSettingsPage(
    sp: android.content.SharedPreferences,
    onBack: () -> Unit
) {
    // 状态
    var rearCamMode by remember { mutableStateOf(sp.getString(SafeWalkPrefKey.rearCamMode, "fullscreen")) }
    var rearCamAuto by remember { mutableStateOf(sp.getBoolean(SafeWalkPrefKey.rearCamAuto, true)) }
    var rearCamOpacity by remember { mutableFloatStateOf(sp.getInt(SafeWalkPrefKey.rearCamOpacity, 50).toFloat()) }
    var rearCamCorner by remember { mutableStateOf(sp.getString(SafeWalkPrefKey.rearCamCorner, "bottom_right")) }

    val isSmallWindow = rearCamMode == "smallwindow"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("后置看路设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {

            // ===== 自动模式开关 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动检测步行", style = MaterialTheme.typography.titleMedium)
                    Text("走起来自动开、停下自动收", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = rearCamAuto,
                    onCheckedChange = {
                        rearCamAuto = it
                        sp.edit().putBoolean(SafeWalkPrefKey.rearCamAuto, it).commit()
                    }
                )
            }

            // ===== 显示形态 =====
            Text(
                text = "显示形态",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )

            RadioOption(
                title = "半透明全屏模式",
                selected = rearCamMode == "fullscreen",
                onClick = {
                    rearCamMode = "fullscreen"
                    sp.edit().putString(SafeWalkPrefKey.rearCamMode, "fullscreen").commit()
                }
            )

            RadioOption(
                title = "小窗模式",
                selected = rearCamMode == "smallwindow",
                onClick = {
                    rearCamMode = "smallwindow"
                    sp.edit().putString(SafeWalkPrefKey.rearCamMode, "smallwindow").commit()
                }
            )

            // ===== 小窗方向设置 =====
            Text(
                text = "小窗方向",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                color = if (isSmallWindow) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )

            RadioOption(
                title = "右下角",
                selected = rearCamCorner == "bottom_right",
                enabled = isSmallWindow,
                onClick = {
                    rearCamCorner = "bottom_right"
                    sp.edit().putString(SafeWalkPrefKey.rearCamCorner, "bottom_right").commit()
                }
            )

            RadioOption(
                title = "左下角",
                selected = rearCamCorner == "bottom_left",
                enabled = isSmallWindow,
                onClick = {
                    rearCamCorner = "bottom_left"
                    sp.edit().putString(SafeWalkPrefKey.rearCamCorner, "bottom_left").commit()
                }
            )

            RadioOption(
                title = "右上角",
                selected = rearCamCorner == "top_right",
                enabled = isSmallWindow,
                onClick = {
                    rearCamCorner = "top_right"
                    sp.edit().putString(SafeWalkPrefKey.rearCamCorner, "top_right").commit()
                }
            )

            RadioOption(
                title = "左上角",
                selected = rearCamCorner == "top_left",
                enabled = isSmallWindow,
                onClick = {
                    rearCamCorner = "top_left"
                    sp.edit().putString(SafeWalkPrefKey.rearCamCorner, "top_left").commit()
                }
            )

            // ===== 透明度调节 =====
            Text(
                text = "透明度：${rearCamOpacity.toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )

            Slider(
                value = rearCamOpacity,
                onValueChange = { rearCamOpacity = it },
                onValueChangeFinished = {
                    sp.edit().putInt(SafeWalkPrefKey.rearCamOpacity, rearCamOpacity.toInt()).commit()
                },
                valueRange = 0f..100f
            )

        }
    }
}

@Composable
private fun RadioOption(
    title: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled
        )
        Text(
            text = title,
            modifier = Modifier.padding(start = 16.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}
