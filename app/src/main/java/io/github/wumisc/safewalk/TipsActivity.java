package io.github.wumisc.safewalk;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * 出门踩坑指南页面：2x2 场景磁贴，点进去显示对应内容。
 */
public class TipsActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_tips);

        // 四个场景磁贴点击事件
        findViewById(R.id.tile_job).setOnClickListener(v -> showContent("求职避坑", JOB_TIPS));
        findViewById(R.id.tile_date).setOnClickListener(v -> showContent("约会避坑", DATE_TIPS));
        findViewById(R.id.tile_party).setOnClickListener(v -> showContent("聚会避坑", PARTY_TIPS));
        findViewById(R.id.tile_travel).setOnClickListener(v -> showContent("旅游避坑", TRAVEL_TIPS));
    }

    private void showContent(String title, String content) {
        TextView tvTitle = findViewById(R.id.tv_content_title);
        TextView tvContent = findViewById(R.id.tv_content);
        tvTitle.setText(title);
        tvContent.setText(content);
        tvTitle.setVisibility(android.view.View.VISIBLE);
        tvContent.setVisibility(android.view.View.VISIBLE);
    }

    private static final String JOB_TIPS =
        "1. 住宿踩坑：优先青旅，只订1晚，现场看房\n" +
        "2. 行李繁重：精简物品，寄存行李，面试带小包\n" +
        "3. 预算超支：每日定额，公交优先，留应急钱\n" +
        "4. 迷路跑错：离线地图，提前查路线，预留缓冲\n" +
        "5. 面试跑空：出发前电话确认时间地点\n" +
        "6. 手机断电：随身充电宝，缓存资料路线\n" +
        "7. 身体不适：常备药品，保证休息\n" +
        "8. 证件遗失：证件拍照备份，原件贴身放";

    private static final String DATE_TIPS =
        "1. 地点踩雷：提前沟通偏好，选交通方便的地方\n" +
        "2. 迟到失印象：提前出门，预留堵车缓冲\n" +
        "3. 话题尴尬：少打探收入、感情过往等隐私\n" +
        "4. 消费分歧：提前想好AA或轮流买单方案\n" +
        "5. 身体不适：随身备纸巾、基础药品\n" +
        "6. 手机分心：少长时间刷手机，专注交流\n" +
        "7. 行程混乱：简单规划，不安排太满\n" +
        "8. 边界越界：尊重对方距离，不强行肢体接触";

    private static final String PARTY_TIPS =
        "1. 迟到失礼：尽量准时，迟到提前告知\n" +
        "2. 言语踩坑：不随意评判、不强行劝酒\n" +
        "3. 消费纠纷：提前说好AA，结束统一结算\n" +
        "4. 冷场尴尬：轻松闲聊，不单向说教\n" +
        "5. 财物丢失：随身看好手机钱包\n" +
        "6. 饮酒风险：量力而行，不逞强喝酒\n" +
        "7. 离场安排：走前打招呼，安全返程\n" +
        "8. 分寸把握：不随意打探别人私事";

    private static final String TRAVEL_TIPS =
        "1. 住宿踩坑：多看实拍评价，优先可退订单\n" +
        "2. 行李繁重：精简行李，车站寄存大件\n" +
        "3. 预算超标：做好预算，预留应急资金\n" +
        "4. 迷路误车：离线地图，提前查交通班次\n" +
        "5. 突发变故：保留备选方案，保险备好\n" +
        "6. 手机断电：充电宝，提前缓存路线\n" +
        "7. 身体不适：备肠胃、外伤药物，劳逸结合\n" +
        "8. 财物证件：证件拍照存档，贵重物品贴身";
}
