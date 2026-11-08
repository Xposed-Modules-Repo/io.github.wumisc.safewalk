package io.github.wumisc.safewalk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 日志回传接收器（App 进程）。
 *
 * Hook 跑在 SystemUI 进程，它能把日志写进自己的目录，但那个目录是
 * `/storage/emulated/0/Android/data/com.android.systemui/files/…`，
 * 用户不可能找得到，文件管理器也进不去。
 *
 * 所以 Hook 侧日志先进内存环形缓冲，App 启动时发 [DiagLog.ACTION_REQUEST_LOG]，
 * Hook 收到后把整段正文用 [DiagLog.ACTION_LOG_DUMP] 回传过来，由这里
 * 追加到同一个 `diag.log`。用户打开一次 App，两边的日志就都在一个文件里了。
 *
 * 这个接收器必须 exported（SystemUI 跨进程发过来），靠令牌挡伪造——
 * 否则任何应用都能往你的日志里灌垃圾。
 */
public class LogBridgeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null) return;
        // 同 CrashAlertReceiver：uid 优先（API 34+），令牌兜底（API 33）
        if (!ReceiverCompat.isTrustedCommand(this,
                i.getStringExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN))) {
            Log.w("SafeWalk", "[logbridge] 鉴权失败，丢弃");
            return;
        }
        String text = i.getStringExtra(DiagLog.EXTRA_LOG_TEXT);
        if (text == null || text.isEmpty()) return;

        // 接收器可能把已死的 App 进程拉起来，此时 DiagLog 还没初始化
        DiagLog.init(c.getApplicationContext());
        DiagLog.writeRaw(text);
        DiagLog.log("[logbridge] 已接收系统界面日志 " + text.length() + " 字符");
    }
}
