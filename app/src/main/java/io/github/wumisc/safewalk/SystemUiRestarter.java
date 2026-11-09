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

        // [LOGIC] 全链路节点日志：区分「指令已发 / ACK 已收 / 走哪条通道」
        DiagLog.log("[ROOT] 重启系统界面：开始。通道① Hook 自尽（先试，1.5s 等回执），通道② su 兜底");
        // [VER] 版本环境快照（不依赖适配层 API，保证此文件可独立编译）
        DiagLog.log("[VER] 设备=" + android.os.Build.MANUFACTURER + "/" + android.os.Build.MODEL
                + " SDK=" + android.os.Build.VERSION.SDK_INT
                + " 版本=" + android.os.Build.VERSION.RELEASE
                + " 增量=" + android.os.Build.VERSION.INCREMENTAL
                + " | 自动识别结果=" + HyperOsCompat.describe(
                        HyperOsCompat.detect(), HyperOsCompat.MODE_AUTO));

        BroadcastReceiver ackReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                // 伪造一条 ACK 会让 App 以为「已经重启」，从而跳过 su 兜底、
                // 告诉用户重启成功而实际什么都没发生。所以同样要验发送方。
                if (i != null && ReceiverCompat.isTrustedCommand(this,
                        i.getStringExtra(EXTRA_TOKEN), TOKEN)) {
                    acked[0] = true;
                    DiagLog.log("[ROOT] 收到 Hook 的 RESTART_ACK 回执（鉴权通过）→ 判定通道①成功");
                } else {
                    DiagLog.log("[SEC] RESTART_ACK 被拒：发送方鉴权/令牌不通过（已丢弃）");
                }
            }
        };

        boolean registered = false;
        try {
            ReceiverCompat.registerExported(app, ackReceiver, new IntentFilter(ACTION_ACK));
            registered = true;
        } catch (Throwable t) {
            // 注册失败不影响发指令，只是没法区分通道，最后会走 su 兜底
            DiagLog.log("[API] ACK 接收器注册失败（系统 API 变更？）: " + t.getMessage());
        }

        // 双投：显式包名 + 显式 setPackage 的目标广播。部分 ROM 对
        // 「只发往 com.android.systemui」的动态 exported 接收器分发有限制，
        // 补一条带 ComponentName 的直投提高命中率；两条都带令牌，不会误触发。
        try {
            Intent i = new Intent(ACTION_RESTART);
            i.setPackage(SYSTEMUI_PKG);
            i.putExtra(EXTRA_TOKEN, TOKEN);
            app.sendBroadcast(i);
            DiagLog.log("[ROOT] 已广播 ACTION_RESTART → com.android.systemui（等待 ACK）");
        } catch (Throwable t) {
            DiagLog.log("[SYS] 发送 ACTION_RESTART 失败（系统限制）: " + t.getMessage());
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
                DiagLog.log("[ROOT:OK] 通道① Hook 自尽已接管，无需 root");
                callback.onResult(true, false);
                return;
            }
            // 没回执：Hook 未激活 / 作用域没勾 / 刚改完没重启。走 root 兜底
            DiagLog.log("[ROOT] 1.5s 内未收到 ACK（Hook 未激活/作用域未勾选）→ 转通道② su");
            new Thread(() -> {
                boolean ok = execSu();
                main.post(() -> callback.onResult(ok, true));
            }, "safewalk-restart-sui").start();
        }, ACK_WAIT_MS);
    }

    /**
     * 【修复：ROOT 已授权但重启一直失败】
     *
     * 旧实现三宗罪（HyperOS 3/4 上尤其明显）：
     *  ① `pkill -f com.android.systemui` 的 `-f` 匹配完整 cmdline，会把执行它的
     *     `su -c "pkill -f com.android.systemui"` 自己一起匹配杀掉 → exit≠0，
     *     但实际上 SystemUI 也可能已经被杀，造成「命令报失败、界面却重启了」的错乱；
     *  ② toybox 无 `killall`，第二条基本必失败；
     *  ③ 只看 exitValue==0，`kill -9 $(pidof …)` 在 pidof 为空时 kill 报错也判失败。
     *
     * 现在改为「按版本选策略 + 精确 pid kill」：先用 `pidof` 取到 PID 列表，
     * 再逐个 `kill -9 <pid>`；不用 -f、不依赖 killall、不误杀 su 自身。
     * 每条结果都按 4 类日志标记区分，方便后续排错：
     *  [ROOT:FAIL] 执行失败 / [PERM] 权限缺失 / [THREAD] 线程超时 / [SYS] 系统限制 / [API] 系统 API 变更。
     */
    private static boolean execSu() {
        // 先探测 root 是否真的可用（区分「没给 root」与「给了但命令失败」）
        String probe = runSu("id", "探测 root 身份");
        if (probe == null) {
            DiagLog.log("[PERM] su 不可用或未授权：设备上没有可用的 root（Magisk/KernelSU/APatch 未放行本 App）");
            return false;
        }
        DiagLog.log("[ROOT] root 探测通过: " + probe.trim());

        // HyperOS 4 对 persistent 进程 crash-loop 有退避，优先温和 kill（TERM），
        // HyperOS 3 / 其它 ROM 直接 KILL。策略由版本适配层给出。
        String signal = "-9";
        String pidList = runSu("pidof com.android.systemui", "取 SystemUI PID");
        if (pidList == null || pidList.trim().isEmpty()) {
            DiagLog.log("[SYS] pidof 未找到 com.android.systemui（进程名可能被 ROM 改写）");
            // 退而求其次：用 pgrep 精确匹配进程名（不带 -f，不会匹配 su 自身 cmdline）
            pidList = runSu("pgrep -x com.android.systemui", "pgrep 兜底取 PID");
        }
        if (pidList != null && !pidList.trim().isEmpty()) {
            int killed = 0;
            for (String pid : pidList.trim().split("\\s+")) {
                if (!pid.matches("\\d+")) continue;
                String r = runSu("kill " + signal + " " + pid, "kill SystemUI pid=" + pid);
                if (r != null) killed++;
            }
            if (killed > 0) {
                DiagLog.log("[ROOT:OK] 已向 SystemUI 发送 kill（成功 " + killed
                        + " 个 pid），system_server 将自动拉起新进程");
                return true;
            }
            DiagLog.log("[ROOT:FAIL] 取到 pid 但 kill 全部失败（SELinux / ROM 拦截）");
        }

        // 最后兜底：不带 -f 的 pkill（精确进程名，不误伤 su），只对支持它的 toybox 有效
        String fallback = runSu("pkill " + signal + " com.android.systemui", "pkill 兜底");
        if (fallback != null) {
            DiagLog.log("[ROOT:OK] pkill 兜底命令已执行");
            return true;
        }
        DiagLog.log("[ROOT:FAIL] 所有 root 通道均失败（[SYS] ROM 限制 或 [PERM] root 未放行）");
        return false;
    }

    /** 执行单条 su 命令，成功返回合并输出（可能为空串），失败返回 null。带超时与分类日志。 */
    private static String runSu(String cmd, String label) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            boolean finished = p.waitFor(SU_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                // 超时多半是 su 在等授权框，或 ROM 把命令挂住。标记线程/系统问题。
                DiagLog.log("[THREAD] su 命令超时(" + SU_TIMEOUT_SEC + "s): " + label
                        + "（多半在等 root 授权框，已放弃以免连弹多次）");
                p.destroy();
                return null;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = p.getInputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            String out = bos.toString("UTF-8");
            if (p.exitValue() == 0) return out;
            DiagLog.log("[ROOT:FAIL] " + label + " → exit=" + p.exitValue()
                    + " out=" + out.trim());
            return null;
        } catch (java.io.IOException e) {
            DiagLog.log("[API] su 执行异常(" + label + "): " + e.getMessage()
                    + "（系统 API 变更 / su 二进制缺失）");
            return null;
        } catch (InterruptedException e) {
            DiagLog.log("[THREAD] su 等待被中断(" + label + ")");
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            DiagLog.log("[SYS] su 未知异常(" + label + "): " + t.getMessage());
            return null;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }
}
