package io.github.wumisc.safewalk;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 感谢名单（二级页）。
 *
 * 与「致敬名单」分开：致敬名单记的是开源项目与库，这一页记的是**参与测试、
 * 反馈问题的用户**，所以用「头像 + 名字 + 一句说明」的人名卡形式，而不是项目卡片。
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
            new Helper("我是寂寞的花", "酷安", "配合调试，一起排查真机上的问题", 0),
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
        render();
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
