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
 * 感谢名单（二级页）。
 *
 * 与「致敬名单」分开：致敬名单记的是开源项目与库，这一页记的是**参与测试、
 * 反馈问题的用户**，所以用「头像 + 名字 + 一句说明」的人名卡形式，而不是项目卡片。
 * 页面底部合并了「支持作者」收款码区块（支付宝 / 微信，长按可存相册），不再单设捐赠页。
 *
 * 头像默认用名字首字母圆形徽标（不依赖任何图片资源）；若以后有真实头像，
 * 把图片放进 drawable 并把资源 id 填到 {@link Helper#avatarRes} 即可圆形裁切显示。
 */
public class ThanksActivity extends Activity {

    /** 头像底色轮换，深色模式自动取 values-night 值 */
    private static final int[] AVATAR_COLORS = {
            R.color.primary, R.color.accent_green, R.color.accent_warn, R.color.seal_red
    };

    /** 名单数据。加人只改这里。avatarRes 传 0 用首字母，传 drawable id 用真实头像。 */
    private static final Helper[] HELPERS = new Helper[]{
            new Helper("酷安", "酷安社区", "感谢支持与帮助", 0),
            new Helper("不羁", "酷安", "· 资金支持（赞助）\n· 感谢对本项目的支持", 0),
            new Helper("我是寂寞的花", "酷安", "· 真机测试\n· 超广角思路与看法", 0),
            new Helper("小鳥游星野酱", "酷安", "提出 HyperOS 3 适配问题", 0),
    };

    private static final class Helper {
        final String name;
        final String platform;
        final String desc;
        final int avatarRes;

        Helper(String name, String platform, String desc, int avatarRes) {
            this.name = name;
            this.platform = platform;
            this.desc = desc;
            this.avatarRes = avatarRes;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_thanks);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        bindQr(R.id.iv_qr_alipay, R.drawable.ic_donate_alipay, "SafeWalk_donate_alipay");
        bindQr(R.id.iv_qr_wechat, R.drawable.ic_donate_wechat, "SafeWalk_donate_wechat");

        render();
    }

    /** 绑收款码图 + 长按保存到相册。两个码走同一套逻辑，少一份重复代码 */
    private void bindQr(int viewId, int resId, String fileName) {
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

    private void render() {
        LinearLayout container = findViewById(R.id.container_thanks);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < HELPERS.length; i++) {
            if (i > 0) container.addView(divider());

            Helper h = HELPERS[i];
            View row = inflater.inflate(R.layout.item_thanks, container, false);

            TextView letter = row.findViewById(R.id.tv_avatar_letter);
            ImageView avatar = row.findViewById(R.id.iv_avatar);
            TextView name = row.findViewById(R.id.tv_name);
            TextView platform = row.findViewById(R.id.tv_platform);
            TextView desc = row.findViewById(R.id.tv_desc);

            name.setText(h.name);
            platform.setText(h.platform);
            desc.setText(h.desc);

            if (h.avatarRes != 0) {
                avatar.setImageResource(h.avatarRes);
                avatar.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                avatar.setClipToOutline(true);
                avatar.setVisibility(View.VISIBLE);
                letter.setVisibility(View.GONE);
            } else {
                letter.setText(initialOf(h.name));
                letter.setBackgroundTintList(ColorStateList.valueOf(
                        getColor(AVATAR_COLORS[i % AVATAR_COLORS.length])));
            }
            container.addView(row);
        }
    }

    /** 首字符兜底头像；codePoint 避免截断 emoji / 代理对 */
    private static String initialOf(String name) {
        if (name == null || name.isEmpty()) return "?";
        return new String(Character.toChars(name.codePointAt(0))).toUpperCase(Locale.ROOT);
    }

    /** 行间分隔线：1px，左缩进 16dp，与全站行式卡片一致 */
    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
