package io.github.wumisc.safewalk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.wumisc.safewalk.torch.TorchResult

/**
 * 手电通知按钮的落点（**应用进程**）。
 *
 * 通知由两个进程发出：
 *  · Hook（SystemUI）里的夜间照明提醒与静置提醒；
 *  · 应用自己的提醒卡片。
 * 以前按钮都投给 SystemUI 的 Hook，由它调 setTorchMode。现在统一投到这里，
 * 因为只有应用进程知道「自己的后视预览是不是正占着摄像头」，也只有它能先
 * 把预览收起来。Hook 那条通道保留作兜底，但不再是主路径。
 *
 * 跨进程（SystemUI → 应用）必须是 exported，靠令牌挡伪造。
 */
class TorchActionReceiver : BroadcastReceiver() {

    companion object {
        // @JvmField：Java 侧（LabActivity 的手电测试）要直接读，否则只能走 Companion.getX()
        @JvmField val ACTION_TORCH_ON = io.github.wumisc.safewalk.torch.ACTION_APP_TORCH_ON
        @JvmField val ACTION_TORCH_OFF = io.github.wumisc.safewalk.torch.ACTION_APP_TORCH_OFF
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_TORCH_ON && action != ACTION_TORCH_OFF) return
        // uid 优先（API 34+）+ 令牌兜底：否则任意应用发一条广播就能点灯/熄灯
        if (!ReceiverCompat.isTrustedCommand(
                this, intent.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN)
            )
        ) return

        val app = context.applicationContext
        DiagLog.init(app)
        val enable = action == ACTION_TORCH_ON
        DiagLog.log("[torch] 通知按钮: ${if (enable) "开灯" else "关灯"}")

        TorchController.set(app, enable) { r: TorchResult ->
            if (!r.ok) {
                // 失败必须说出来。以前这里只有一行 false 日志，
                // 用户看到的就是「按了没反应」
                try {
                    Toast.makeText(app, "手电打不开：${r.message}", Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
