package io.github.wumisc.safewalk;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 捐赠页：支付宝 + 微信两个收款码，长按任意一张可存到相册。
 */
public class DonateQrActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_donate_qr);

        ((TextView) findViewById(R.id.tv_title)).setText("支持捐赠");
        ((TextView) findViewById(R.id.tv_subtitle)).setText("如果觉得好用，请作者喝杯奶茶");
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        bind(R.id.iv_qr_alipay, R.drawable.ic_donate_alipay, "SafeWalk_donate_alipay");
        bind(R.id.iv_qr_wechat, R.drawable.ic_donate_wechat, "SafeWalk_donate_wechat");
    }

    /** 绑图 + 长按保存。两个码走同一套逻辑，少一份重复代码 */
    private void bind(int viewId, int resId, String fileName) {
        ImageView iv = findViewById(viewId);
        iv.setImageResource(resId);
        iv.setOnLongClickListener(v -> {
            saveToGallery(resId, fileName);
            return true;
        });
    }

    /**
     * 存到相册。和「保存壁纸」同一套 MediaStore 流程：
     * 先插 pending 记录 → 写完再置 0，避免相册扫到半截图。
     */
    private void saveToGallery(int resId, String fileName) {
        Uri uri = null;
        try {
            Bitmap bmp = BitmapFactory.decodeResource(getResources(), resId);
            if (bmp == null) throw new IllegalStateException("图片解码失败");

            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, fileName + ".png");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/SafeWalk");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);

            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new java.io.IOException("相册拒绝了写入请求");

            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                // openOutputStream 在部分 ROM 上会返回 null
                if (os == null) throw new java.io.IOException("无法打开输出流");
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                    throw new java.io.IOException("图片编码失败");
                }
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);

            Toast.makeText(this, "已保存到 DCIM/SafeWalk/", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            // 失败要把占位记录删掉，否则相册里留一条打不开的空条目
            if (uri != null) {
                try {
                    getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            Toast.makeText(this, "保存失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
