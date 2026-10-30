package io.github.wumisc.safewalk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 手电筒静置提醒设置页（Compose + Material3）
 * 调整：手电开着+静置多久后弹省电提醒（5~60分钟）
 */
class TorchStaticSettingsActivity : ComponentActivity() {

    private lateinit var sp: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = getSharedPreferences("safewalk_xsp", MODE_PRIVATE)

        setContent {
            MaterialTheme {
                TorchStaticSettingsScreen(
                    initialMinutes = sp.getFloat(SafeWalkPrefKey.torchStaticMinutes, 20f),
                    onMinutesChange = { minutes ->
                        sp.edit().putFloat(SafeWalkPrefKey.torchStaticMinutes, minutes).apply()
                        SafeWalkConfig.exportConfig(this@TorchStaticSettingsActivity)
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TorchStaticSettingsScreen(
        initialMinutes: Float,
        onMinutesChange: (Float) -> Unit,
        onBack: () -> Unit
    ) {
        var minutes by remember { mutableFloatStateOf(initialMinutes) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("手电静置提醒") },
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
                    .padding(16.dp)
            ) {
                Text(
                    text = "静置提醒时间",
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    text = "手电筒开启 + 静置 ${minutes.toInt()} 分钟后，弹出省电提醒",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Slider(
                    value = minutes,
                    onValueChange = {
                        minutes = it
                        onMinutesChange(it)
                    },
                    valueRange = 5f..60f,
                    steps = 54,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                )

                Text(
                    text = "范围：5 ~ 60 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
