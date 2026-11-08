package io.github.wumisc.safewalk;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 致敬名单。
 *
 * ## 这一页为什么重写
 *
 * 原来整页只有一个 TextView，把十几条项目名和说明拼成一个字符串：链接点不了，
 * 也完全看不出「这个项目到底有没有真的被用上」。现在改成数据驱动 ——
 * 每条带三样东西：**项目名、可点击的仓库地址、采纳状态**。
 *
 * ## 采纳状态的判定依据
 *
 * · {@link #ADOPTED_INTEGRATED} 依赖里真的挂着，不是「参考」
 * · {@link #ADOPTED_FULL}       核心机制能在源码里指出对应实现
 * · {@link #ADOPTED_PARTIAL}    只借了一部分，另一部分明确没做
 * · {@link #ADOPTED_REFERENCE}  只是思路来源，代码里没有对应实现
 * · {@link #ADOPTED_GONE}       仓库已不存在，不再挂链接
 *
 * 2026-11-08 逐条访问 GitHub API 核对时，原名单里有 4 个仓库返回 404，
 * 因此降级为「仅参考 · 仓库已失效」，避免用户在页面上点到一个打不开的地址。
 */
public class AcknowledgementsActivity extends Activity {

    // 颜色一律取自资源：values-night 覆盖后整套界面自动转深色。
    // 原来这里是 0xFF1D1D1D 之类的常量，深色下白底白字，整页看不见。
    private int C_TITLE, C_SUB, C_LINK, C_OK, C_PARTIAL, C_GONE, C_CARD, C_DIVIDER;

    private static final String ADOPTED_INTEGRATED = "直接集成";
    private static final String ADOPTED_FULL = "已落地";
    private static final String ADOPTED_PARTIAL = "部分落地";
    private static final String ADOPTED_REFERENCE = "仅参考";
    private static final String ADOPTED_GONE = "仅参考 · 仓库已失效";

    private float density;

    /** 一条致谢条目 */
    private static final class Item {
        final String name;
        final String desc;
        final String status;
        /** null 表示没有可访问的公开地址 */
        final String url;

        Item(String name, String desc, String status, String url) {
            this.name = name;
            this.desc = desc;
            this.status = status;
            this.url = url;
        }
    }

    /** 一个分区：小标题 + 若干条目 */
    private static final class Section {
        final String title;
        final Item[] items;

        Section(String title, Item[] items) {
            this.title = title;
            this.items = items;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_acknowledgements);
        density = getResources().getDisplayMetrics().density;

        C_TITLE = getColor(R.color.text_primary);
        C_SUB = getColor(R.color.text_hint);
        C_LINK = getColor(R.color.primary);
        C_OK = getColor(R.color.accent_green);
        C_PARTIAL = getColor(R.color.accent_warn);
        C_GONE = getColor(R.color.text_muted);
        C_CARD = getColor(R.color.card_bg);
        C_DIVIDER = getColor(R.color.divider);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        LinearLayout container = findViewById(R.id.container_ack);
        for (Section s : buildSections()) {
            container.addView(buildSectionHeader(s.title));
            container.addView(buildSectionCard(s.items));
        }
    }

    /** 全部致谢数据。要改条目只动这里，不用碰布局。 */
    private List<Section> buildSections() {
        List<Section> out = new ArrayList<>();

        out.add(new Section("直接依赖（不是「参考」，是装进项目里的库）", new Item[]{
                new Item("Kyant0/AndroidLiquidGlass",
                        "底部导航栏本体。液态玻璃材质、拖拽阻尼、透镜折射、交互高光整套特效，"
                                + "本项目的 LiquidBottomTabs 直接取自它。",
                        ADOPTED_INTEGRATED, "https://github.com/Kyant0/AndroidLiquidGlass"),
                new Item("io.github.kyant0:backdrop-android",
                        "Maven 依赖 2.0.1。drawBackdrop / layerBackdrop / Shadow / InnerShadow 等渲染原语，"
                                + "导航栏的模糊与折射都建立在这套原语上。",
                        ADOPTED_INTEGRATED, null),
                new Item("top.yukonga.miuix.kmp:miuix-*",
                        "Maven 依赖 0.9.4（blur / ui / squircle）。液态玻璃模糊、方角与超椭圆容器；"
                                + "compileSdk 需要 37、Java 需要 21，也是被它拉上来的。",
                        ADOPTED_INTEGRATED, null),
        }));

        out.add(new Section("步态检测", new Item[]{
                new Item("bagilevi/android-pedometer",
                        "纯加速度计计步的经典实现。峰值检测 + 步频区间过滤 + 自适应阈值的整体思路，"
                                + "对应 GaitDetector.feedAcc() 里的包络阈值与 0.5~2.5Hz 步频窗口。",
                        ADOPTED_FULL, "https://github.com/bagilevi/android-pedometer"),
                new Item("huuphuoc1396/android-step-detector",
                        "TYPE_STEP_DETECTOR 与 TYPE_ACCELEROMETER 两路取数的用法。本项目现在是"
                                + "「硬件计步器 + 加速度计兜底」双路并行，在 recordStep() 里按 300ms 去重。",
                        ADOPTED_PARTIAL, "https://github.com/huuphuoc1396/android-step-detector"),
                new Item("mdeguzis/StepCounter",
                        "滑动窗口统计 + 按运动状态动态切换采样率的省电策略。只落地了窗口统计："
                                + "采样率至今恒为 SENSOR_DELAY_UI，动态切换没做。",
                        ADOPTED_GONE, null),
                new Item("ashishb/android-step-detector",
                        "用 FFT 频谱分析识别周期性步态。本项目没有采用 FFT，走的是时域包络检测 ——"
                                + "后者在低采样率下更稳，代价是抗噪差一些。",
                        ADOPTED_GONE, null),
                new Item("AccStepCounter / SensorFusion",
                        "低功耗计步滤波、传感器姿态融合与自适应阈值。最终只保留了一阶低通 + 重力估计，"
                                + "姿态融合与卡尔曼都没有落地。",
                        ADOPTED_GONE, null),
        }));

        out.add(new Section("状态机", new Item[]{
                new Item("tonyo567/hysteresis-state-machine",
                        "分离式迟滞状态机：进入与退出各用独立计时器，避免在阈值边缘反复抖动。对应"
                                + "GaitDetector 的 ENTER_DEBOUNCE_MS(2500ms) 与 EXIT_DEBOUNCE_MS(4500ms)。",
                        ADOPTED_GONE, null),
        }));

        out.add(new Section("系统服务与定位", new Item[]{
                new Item("p-v/Android-SensorService",
                        "后台传感器监听、系统采样节流、丢采样容错。本项目只借鉴了「注册成功也可能"
                                + "永远没回调」这条经验，最终写成 45 秒步数探针 + 加速度计自动恢复。",
                        ADOPTED_PARTIAL, null),
                new Item("UrbanBigDataCentre/meowmotion",
                        "GPS 轨迹分割、停留/移动分类、连续位移校验。未落地：出行模式识别走的是"
                                + "Geocoder 反编码 + 关键词投票，没有做轨迹分割。",
                        ADOPTED_REFERENCE, "https://github.com/UrbanBigDataCentre/meowmotion"),
                new Item("KalebKE/FSensor",
                        "Madgwick / Mahony 姿态滤波与卡尔曼 GPS 平滑。未落地：低头判定只用"
                                + "重力 z 轴阈值，没上姿态融合。",
                        ADOPTED_REFERENCE, "https://github.com/KalebKE/FSensor"),
        }));

        out.add(new Section("HyperOS 通知", new Item[]{
                new Item("HyperBridge / WidgetEnhancer / HyperLight",
                        "超级岛自定义通知卡片、通知快捷按钮、悬浮通知相关研究。最终没有走超级岛，"
                                + "用的是标准通知 + 全屏 Intent 拉起急救卡。"
                                + "（原名单只留下缩写，未找到可访问仓库，按「仅参考」处理。）",
                        ADOPTED_GONE, null),
        }));

        return out;
    }

    // ========== 视图构造 ==========

    private TextView buildSectionHeader(String title) {
        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setText(title);
        tv.setTextColor(C_SUB);
        tv.setTextSize(13);
        return tv;
    }

    /** 一个分区 = 一张白卡片，条目之间 1px 分隔线，和医疗急救卡一致 */
    private LinearLayout buildSectionCard(Item[] items) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(C_CARD);
        card.setPadding(dp(16), dp(4), dp(16), dp(4));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < items.length; i++) {
            if (i > 0) card.addView(buildDivider());
            card.addView(buildItem(items[i]));
        }
        return card;
    }

    private View buildDivider() {
        View v = new View(this);
        v.setBackgroundColor(C_DIVIDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    private LinearLayout buildItem(Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        // 第一行：项目名（有链接则蓝字可点）+ 采纳状态
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(item.url != null ? item.name + " ↗" : item.name);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (item.url != null) {
            name.setTextColor(C_LINK);
            name.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openUrl(item.url);
                }
            });
        } else {
            name.setTextColor(C_TITLE);
        }
        head.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = new TextView(this);
        status.setText(item.status);
        status.setTextSize(11);
        status.setTextColor(statusColor(item.status));
        status.setPadding(dp(8), 0, 0, 0);
        head.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(head);

        TextView desc = new TextView(this);
        desc.setText(item.desc);
        desc.setTextSize(12);
        desc.setTextColor(C_SUB);
        desc.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        row.addView(desc, dlp);

        return row;
    }

    private int statusColor(String status) {
        if (ADOPTED_INTEGRATED.equals(status)) return C_LINK;
        if (ADOPTED_FULL.equals(status)) return C_OK;
        if (ADOPTED_PARTIAL.equals(status)) return C_PARTIAL;
        return C_GONE;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "打开链接失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }
}
