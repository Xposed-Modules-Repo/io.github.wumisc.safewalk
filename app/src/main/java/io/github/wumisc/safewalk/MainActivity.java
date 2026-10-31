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
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 40);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("V2.0 基准版\n\n" +
                "✅ 已安装为LSPosed模块\n" +
                "✅ Hook目标：系统界面 (com.android.systemui)\n" +
                "✅ 功能：步态检测、相机悬浮层、夜间手电提醒\n\n" +
                "请在LSPosed管理器中勾选「系统界面」作用域，\n" +
                "然后重启手机即可生效。\n\n" +
                "日志文件：Download/safewalk_diag.log");
        info.setTextSize(14);
        root.addView(info);

        setContentView(root);
    }
}
