package io.github.wumisc.safewalk;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 捐赠二维码页面：显示微信捐赠二维码。
 */
public class DonateQrActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_donate_qr);

        ((TextView) findViewById(R.id.tv_title)).setText("💝 支持捐赠");
        ((TextView) findViewById(R.id.tv_subtitle)).setText("如果觉得好用，请作者喝杯奶茶");

        ImageView iv = findViewById(R.id.iv_qr);
        iv.setImageResource(R.drawable.ic_donate_alipay);
    }
}
