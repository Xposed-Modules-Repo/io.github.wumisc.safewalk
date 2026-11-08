package io.github.wumisc.safewalk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

/**
 * 「重启系统界面」执行器。
 *
 * 有两条通道，按可靠性排序：
 *
 * ① **Hook 自尽（不需要 root）**
 *    模块本身就跑在 SystemUI 进程里，所以让 Hook 收到指令后
 *    `Process.killProcess(myPid())` 即可。SystemUI 是 persistent 进程，
 *    system_server 会立刻把它拉起来，效果等同于「重启系统界面」，
 *    而且不依赖 su、不会弹授权框、不会误杀别的东西。
 *
 * ② **su 兜底（Hook 没激活时）**
 *    用户可能刚改完设置但还没在 LSPosed 里勾作用域，这时 ① 收不到回执，
 *    退化为 root 命令。依次尝试 pkill / killall / kill -9 $(pidof)，
 *    全部失败则说明设备没有可用 root，交给调用方提示手动重启。
 *
 * 为什么不第一条就用 su：LSPosed 用户虽然有 root，但每次弹授权框体验很差；
 * 而且部分 ROM（如 KernelSU 精简版）没有 pkill/killall，兜底成功率并不高。
 */
public final class SystemUiRestarter {

    /** App → Hook：请求重启 */
    public static final String ACTION_RESTART = "io.github.wumisc.safewalk.RESTART_SYSTEMUI";
    /** Hook → App：已收到，马上执行（用于区分「发出去没人接」和「已经在重启」） */
    public static final String ACTION_ACK = "io.github.wumisc.safewalk.RESTART_ACK";
    public static final String EXTRA_TOKEN = "token";
    /** 广播令牌：两个接收器都是 exported，用它挡掉随手伪造的广播 */
    public static final String TOKEN = "safewalk-restart-2.4";

    private static final String SYSTEMUI_PKG = "com.android.systemui";
    /** 等回执的时间。SystemUI 是常驻进程，正常情况下 100ms 内就能收到 */
    private static final long ACK_WAIT_MS = 1500L;
    /** su 单条命令的超时，超时就杀掉进程换下一条 */
    private static final long SU_TIMEOUT_SEC = 4L;

    public interface Callback {
        /**
         * @param ok      是否已下达重启指令
         * @param viaRoot true = 走的 su 兜底；false = Hook 通道
         */
        void onResult(boolean ok, boolean viaRoot);
    }

    private SystemUiRestarter() {}

    public static void restart(Context context, Callback callback) {
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final Handler main = new Handler(Looper.getMainLooper());
        final boolean[] acked = {false};

        BroadcastReceiver ackReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                // 伪造一条 ACK 会让 App 以为「已经重启」，从而跳过 su 兜底、
                // 告诉用户重启成功而实际什么都没发生。所以同样要验发送方。
                if (i != null && ReceiverCompat.isTrustedCommand(this,
                        i.getStringExtra(EXTRA_TOKEN), TOKEN)) {
                    acked[0] = true;
                }
            }
        };

        boolean registered = false;
        try {
            ReceiverCompat.registerExported(app, ackReceiver, new IntentFilter(ACTION_ACK));
            registered = true;
        } catch (Throwable ignored) {
            // 注册失败不影响发指令，只是没法区分通道，最后会走 su 兜底
        }

        try {
            Intent i = new Intent(ACTION_RESTART);
            i.setPackage(SYSTEMUI_PKG);
            i.putExtra(EXTRA_TOKEN, TOKEN);
            app.sendBroadcast(i);
        } catch (Throwable ignored) {
        }

        final boolean reg = registered;
        main.postDelayed(() -> {
            if (reg) {
                try {
                    app.unregisterReceiver(ackReceiver);
                } catch (Throwable ignored) {
                }
            }
            if (acked[0]) {
                // Hook 已接管，600ms 后 SystemUI 会自己重启
                callback.onResult(true, false);
                return;
            }
            // 没回执：Hook 未激活 / 作用域没勾 / 刚改完没重启。走 root 兜底
            new Thread(() -> {
                boolean ok = execSu();
                main.post(() -> callback.onResult(ok, true));
            }, "safewalk-restart-sui").start();
        }, ACK_WAIT_MS);
    }

    /** 依次尝试三种杀进程方式，任一成功即返回 */
    private static boolean execSu() {
        String[][] cmds = {
                {"su", "-c", "pkill -f com.android.systemui"},
                {"su", "-c", "killall com.android.systemui"},
                {"su", "-c", "kill -9 $(pidof com.android.systemui)"}
        };
        for (String[] c : cmds) {
            Process p = null;
            try {
                p = new ProcessBuilder(c).redirectErrorStream(true).start();
                boolean finished = p.waitFor(SU_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    // 超时说明多半是 su 正在等用户在授权框上点「允许」。
                    // 这时候换下一条命令毫无意义（还是同一个 su），
                    // 只会在用户面前连续弹三次授权框。直接收手。
                    p.destroy();
                    return false;
                }
                if (p.exitValue() == 0) return true;
            } catch (Throwable ignored) {
                // su 不存在 / 被拒绝授权，换下一条
            } finally {
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }
}
