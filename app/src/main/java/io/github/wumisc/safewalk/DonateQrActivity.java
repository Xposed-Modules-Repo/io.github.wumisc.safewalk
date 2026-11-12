package io.github.wumisc.safewalk;

import android.app.Activity;
import android.content.ContentValues;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 捐赠页：支付宝 + 微信两个收款码并列展示，长按任意一张可存到相册；
 * 下方「捐赠名单」以圆形头像 + 名字呈现，当前无捐赠者时展示空状态。
 */
public class DonateQrActivity extends Activity {

    /** 头像底色轮换：人多了靠色相区分；深色模式下各色自动取 values-night 值 */
    private static final int[] AVATAR_COLORS = {
            R.color.primary, R.color.accent_green, R.color.accent_warn, R.color.seal_red
    };

    /**
     * 捐赠名单数据。现在还没有捐赠者，保持空数组即可。
     * 新增一位：{@code new Donor("昵称", 0)} —— avatarRes 传 0 走首字母徽标；
     * 传 drawable 资源 id 则显示真实头像（圆形裁切）。
     */
    private static final Donor[] DONORS = new Donor[]{};

    /** 单个捐赠者：名字 + 可选头像资源（0 表示无图，用首字母） */
    public static final class Donor {
        final String name;
        final int avatarRes;

        public Donor(String name, int avatarRes) {
            this.name = name;
            this.avatarRes = avatarRes;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_donate_qr);

        ((TextView) findViewById(R.id.tv_title)).setText("支持捐赠");
        ((TextView) findViewById(R.id.tv_subtitle)).setText("如果觉得好用，请作者喝杯奶茶");
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        bind(R.id.iv_qr_alipay, R.drawable.ic_donate_alipay, "SafeWalk_donate_alipay");
        bind(R.id.iv_qr_wechat, R.drawable.ic_donate_wechat, "SafeWalk_donate_wechat");

        renderDonors();
    }

    /** 渲染捐赠名单：有数据逐行 inflate；无数据只留空状态，避免空容器占位 */
    private void renderDonors() {
        LinearLayout list = findViewById(R.id.donor_list);
        TextView empty = findViewById(R.id.tv_donor_empty);

        if (DONORS.length == 0) {
            list.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            return;
        }

        empty.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        list.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < DONORS.length; i++) {
            Donor d = DONORS[i];
            View row = inflater.inflate(R.layout.item_donor, list, false);

            TextView letter = row.findViewById(R.id.tv_avatar_letter);
            ImageView avatar = row.findViewById(R.id.iv_avatar);
            TextView name = row.findViewById(R.id.tv_donor_name);

            name.setText(d.name);

            if (d.avatarRes != 0) {
                // 有真实头像：按背景 oval 轮廓裁成圆形
                avatar.setImageResource(d.avatarRes);
                avatar.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                avatar.setClipToOutline(true);
                avatar.setVisibility(View.VISIBLE);
                letter.setVisibility(View.GONE);
            } else {
                letter.setText(initialOf(d.name));
                letter.setBackgroundTintList(ColorStateList.valueOf(
                        getColor(AVATAR_COLORS[i % AVATAR_COLORS.length])));
            }
            list.addView(row);
        }
    }

    /** 取名字首字符兜底头像；用 codePoint 避免把 emoji / 代理对截断 */
    private static String initialOf(String name) {
        if (name == null || name.isEmpty()) return "?";
        int cp = name.codePointAt(0);
        return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
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
     * 存到相册。和「保存图片到相册」同一套 MediaStore 流程：
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
