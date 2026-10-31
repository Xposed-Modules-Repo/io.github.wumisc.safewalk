package io.github.wumisc.safewalk;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(60, 120, 60, 60);

        TextView title = new TextView(this);
        title.setText("安全出行 SafeWalk");
        title.setTextSize(28);
        title.setPadding(0, 0, 0, 20);
        root.addView(title);

        TextView version = new TextView(this);
        version.setText("V2.0 材质深色图标版");
        version.setTextSize(16);
        version.setPadding(0, 0, 0, 40);
        root.addView(version);

        TextView features = new TextView(this);
        features.setText(
                "✅ 步态检测：走路自动开启后置摄像头悬浮窗\n" +
                "✅ 相机避让：打开相机APP自动隐藏悬浮窗\n" +
                "✅ 夜间手电：夜间出行自动提醒开启手电筒\n" +
                "✅ 久坐提醒：连续行走40分钟提示休息\n" +
                "✅ 玻璃胶囊：底部悬浮导航栏\n\n" +
                "📌 LSPosed设置：\n" +
                "1. 勾选「系统界面 (com.android.systemui)」作用域\n" +
                "2. 重启手机生效\n\n" +
                "📝 日志位置：Download/safewalk_diag.log"
        );
        features.setTextSize(14);
        root.addView(features);

        setContentView(root);
    }
}
