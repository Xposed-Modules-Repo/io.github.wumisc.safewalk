package io.github.wumisc.safewalk.torch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 手电筒通知按钮点击广播接收器
 */
class TorchActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        intent ?: return
        when (intent.action) {
            ACTION_OPEN_TORCH -> {
                val result = TorchHelper.setTorch(context, true)
                val msg = if (result) "手电筒已开启" else "开启失败，请检查权限/闪光灯"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            ACTION_CLOSE_TORCH -> {
                val result = TorchHelper.setTorch(context, false)
                val msg = if (result) "手电筒已关闭" else "关闭失败"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
