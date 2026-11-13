package io.github.wumisc.safewalk;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 无障碍调试面板（开发者工具）。
 *
 * ## 用途
 *
 * 排查「为什么读屏读不对」「这个按钮为什么 TalkBack 只念『按钮』」「焦点为什么
 * 跳到别处」这类问题。三块信息：
 *
 * 1. **事件流**——点击、焦点、窗口切换、通知、滚动，带时间与来源包名；
 * 2. **节点树**——当前窗口的控件层级、文本、contentDescription、viewId、可点击性；
 * 3. **无标签统计**——可点击但既无 text 也无 desc 的节点数，这正是读屏增强要补的。
 *
 * ## 生命周期即采集开关
 *
 * `onResume` 打开采集、`onPause` 关闭。不在后台偷偷遍历节点树：那样既费电，
 * 也会让用户（以及审核）怀疑这个「调试工具」在偷看屏幕。面板不在前台，一条都不收。
 *
 * ## 为什么 Activity 是 Java
 *
 * 项目里所有 Activity 都是 Java，混编时保持同一套 View 操作习惯，读代码的人不用
 * 在两种语法间切换。纯逻辑部分（格式化、节点模型）已经抽到 Kotlin 里了，
 * 这里只剩绑定与渲染。
 */
public class A11yInspectorActivity extends Activity {

    /** 面板最多渲染多少条事件。缓冲有 300 条，全画出来会明显卡顿。 */
    private static final int MAX_RENDER = 60;

    private TextView tvServiceState, tvEventCount, tvTree;
    private LinearLayout containerEvents;

    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** 最近一次抓取的节点树文本，供「复制节点树」使用。 */
    private String lastTreeText = "";

    /** 事件总线回调。字段持有引用，onDestroy 才能精确反注册。 */
    private final Runnable onEventsChanged = this::renderEvents;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_a11y_inspector);

        tvServiceState = findViewById(R.id.tv_service_state);
        tvEventCount = findViewById(R.id.tv_event_count);
        tvTree = findViewById(R.id.tv_tree);
        containerEvents = findViewById(R.id.container_events);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_open_service).setOnClickListener(v -> openServiceSettings());
        findViewById(R.id.btn_dump).setOnClickListener(v -> dumpTree());
        findViewById(R.id.btn_copy_tree).setOnClickListener(v ->
                copyToClipboard("节点树", lastTreeText));
        findViewById(R.id.btn_copy_events).setOnClickListener(v ->
                copyToClipboard("事件列表", buildEventsText()));
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            A11yEventBus.INSTANCE.clear();
            lastTreeText = "";
            tvTree.setText("（已清空）");
            renderEvents();
        });

        A11yEventBus.INSTANCE.addListener(onEventsChanged);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 只有服务真的被系统连上，采集才有意义；没连上时置 true 也不会来事件
        A11yInspectorService.Companion.setCollecting(A11yInspectorService.isEnabled(this));
        refreshState();
        renderEvents();
    }

    @Override
    protected void onPause() {
        // 面板不在前台就停采集：这是「不偷看」的硬保证，不是性能优化
        A11yInspectorService.Companion.setCollecting(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        A11yEventBus.INSTANCE.removeListener(onEventsChanged);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 状态

    private void refreshState() {
        boolean enabled = A11yInspectorService.isEnabled(this);
        boolean connected = A11yInspectorService.isConnected();
        if (!enabled) {
            tvServiceState.setText("服务状态：未开启 · 点下面「开启无障碍服务」");
        } else if (!connected) {
            tvServiceState.setText("服务状态：已授权，等待系统连接…（可退出重进本页）");
        } else {
            tvServiceState.setText("服务状态：采集中");
        }
    }

    // ---------------------------------------------------------------- 事件流

    private void renderEvents() {
        refreshState();
        if (containerEvents == null) return;

        List<A11yEventRecord> all = A11yEventBus.INSTANCE.snapshot();
        tvEventCount.setText("已采集 " + all.size() + " 条事件（缓冲上限 "
                + A11yEventBus.CAP + " 条）");

        containerEvents.removeAllViews();
        if (all.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("还没有事件。先去别的界面点点按钮，再回来看。");
            t.setTextColor(getColor(R.color.text_hint));
            t.setTextSize(13);
            containerEvents.addView(t);
            return;
        }

        // 最新在上：倒序遍历，最多 MAX_RENDER 条
        int shown = 0;
        for (int i = all.size() - 1; i >= 0 && shown < MAX_RENDER; i--, shown++) {
            A11yEventRecord r = all.get(i);
            TextView t = new TextView(this);
            t.setText(timeFmt.format(new Date(r.getTs())) + "  "
                    + A11yEventFormatter.eventTypeName(r.getType()) + "  "
                    + (r.getPkg().isEmpty() ? "-" : r.getPkg()) + "  " + r.getCls()
                    + (r.getText().isEmpty() ? "" : "\n    " + A11yEventFormatter.clip(r.getText())));
            t.setTextSize(11.5f);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextColor(getColor(R.color.text_body));
            t.setPadding(0, dp(5), 0, dp(5));
            containerEvents.addView(t);
        }
        if (all.size() > shown) {
            TextView more = new TextView(this);
            more.setText("… 还有 " + (all.size() - shown) + " 条更早的未显示（可「复制事件列表」看全部）");
            more.setTextSize(11);
            more.setTextColor(getColor(R.color.text_hint));
            more.setPadding(0, dp(6), 0, 0);
            containerEvents.addView(more);
        }
    }

    private String buildEventsText() {
        List<A11yEventRecord> all = A11yEventBus.INSTANCE.snapshot();
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(all.size() * 80);
        sb.append("# SafeWalk 无障碍事件（共 ").append(all.size()).append(" 条）\n");
        for (A11yEventRecord r : all) {
            sb.append(timeFmt.format(new Date(r.getTs()))).append(' ')
                    .append(A11yEventFormatter.eventTypeName(r.getType())).append(' ')
                    .append(r.getPkg().isEmpty() ? "-" : r.getPkg()).append(' ')
                    .append(r.getCls());
            if (!r.getText().isEmpty()) {
                sb.append("  '").append(A11yEventFormatter.clip(r.getText())).append('\'');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 节点树

    private void dumpTree() {
        List<A11yNode> nodes = A11yInspectorService.dumpActiveTree(A11yInspectorService.MAX_NODES);
        if (nodes.isEmpty()) {
            lastTreeText = "";
            tvTree.setText("抓不到节点。可能原因：\n"
                    + "· 无障碍服务没开（点上面「开启无障碍服务」）\n"
                    + "· 当前没有活动窗口（开机动画 / 正在切换）\n"
                    + "· 该窗口设置了 FLAG_SECURE，无障碍读不到（银行、支付类 App 常见，这是正常现象）");
            return;
        }

        // 可点击但没有任何标签的节点 —— 读屏增强要补的就是这些
        int noLabel = 0;
        for (A11yNode n : nodes) {
            if (n.getClickable() && A11yEventFormatter.hasNoLabel(n)) noLabel++;
        }

        lastTreeText = A11yEventFormatter.formatTree(nodes, A11yEventFormatter.MAX_TREE_LINES);
        tvTree.setText("共 " + nodes.size() + " 个节点｜可点击但无标签 " + noLabel + " 个\n\n"
                + lastTreeText);
        Toast.makeText(this, "已抓取 " + nodes.size() + " 个节点", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 杂项

    private void openServiceSettings() {
        try {
            startActivity(A11yInspectorService.detailsIntent(this));
        } catch (Throwable t) {
            // 少数 ROM 没有详情页 action，退到无障碍总列表
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t2) {
                Toast.makeText(this, "打不开无障碍设置：" + t2.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void copyToClipboard(String label, String text) {
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "没有可复制的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) {
            Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("SafeWalk " + label, text));
        Toast.makeText(this, label + "已复制（" + text.length() + " 字）",
                Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
