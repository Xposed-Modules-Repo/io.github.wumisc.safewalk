package io.github.wumisc.safewalk;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 安全出行配置界面。Miuix 风格 + 液态玻璃底部导航。
 */
public final class MainActivity extends Activity {

    private Switch swEnable, swWalk, swAuto, swLookdown;
    private RadioGroup rgForm, rgCorner;
    private SeekBar seekAlpha;
    private TextView tvAlpha;
    private EditText etLookdownSec;

    private View pageSettings, pageAbout;
    private LinearLayout tabSettings, tabAbout;
    private TextView tabSettingsText, tabAboutText;
    private android.widget.ImageView tabSettingsIcon, tabAboutIcon;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        // 申请权限
        try {
            java.util.List<String> need = new java.util.ArrayList<>();
            for (String perm : new String[]{
                    android.Manifest.permission.ACTIVITY_RECOGNITION,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.CAMERA}) {
                if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    need.add(perm);
                }
            }
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 100);
        } catch (Throwable ignored) {}

        // 绑定控件
        swEnable = findViewById(R.id.sw_enable);
        swWalk = findViewById(R.id.sw_walk);
        swAuto = findViewById(R.id.sw_auto);
        swLookdown = findViewById(R.id.sw_lookdown);
        rgForm = findViewById(R.id.rg_form);
        rgCorner = findViewById(R.id.rg_corner);
        seekAlpha = findViewById(R.id.seek_alpha);
        tvAlpha = findViewById(R.id.tv_alpha);
        etLookdownSec = findViewById(R.id.et_lookdown_sec);

        pageSettings = findViewById(R.id.page_settings);
        pageAbout = findViewById(R.id.page_about);
        tabSettings = findViewById(R.id.tab_settings);
        tabAbout = findViewById(R.id.tab_about);
        tabSettingsText = findViewById(R.id.tab_settings_label);
        tabAboutText = findViewById(R.id.tab_about_label);
        tabSettingsIcon = findViewById(R.id.tab_settings_icon);
        tabAboutIcon = findViewById(R.id.tab_about_icon);

        // Tab 切换
        tabSettings.setOnClickListener(v -> switchTab(true));
        tabAbout.setOnClickListener(v -> switchTab(false));

        // 初始化配置
        Config.reload();
        swEnable.setChecked(Config.enable);
        swWalk.setChecked(Config.walkMode);
        swAuto.setChecked(Config.autoWalk);
        swLookdown.setChecked(Config.lookdown);
        rgForm.check(Config.overlayForm == 1 ? R.id.rb_full : R.id.rb_corner);
        rgCorner.check(cornerToId(Config.corner));
        seekAlpha.setProgress(Config.alpha);
        tvAlpha.setText("画面不透明度：" + Config.alpha + "%");
        etLookdownSec.setText(String.valueOf(Config.lookdownSec));

        seekAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean f) {
                tvAlpha.setText("画面不透明度：" + v + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> save());

        // 关于页：模块版本
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tv_about_subtitle)).setText("安全出行 · LSPosed 模块 v" + ver);
        } catch (Throwable ignored) {}
        findViewById(R.id.btn_donate).setOnClickListener(v -> showDonate());
    }

    /** 切换 tab：true=首页, false=关于 */
    private void switchTab(boolean toSettings) {
        pageSettings.setVisibility(toSettings ? View.VISIBLE : View.GONE);
        pageAbout.setVisibility(toSettings ? View.GONE : View.VISIBLE);
        int active = getColor(R.color.primary);
        int inactive = getColor(R.color.text_hint);
        tabSettingsText.setTextColor(toSettings ? active : inactive);
        tabAboutText.setTextColor(toSettings ? inactive : active);
        tabSettingsIcon.setColorFilter(toSettings ? active : inactive);
        tabAboutIcon.setColorFilter(toSettings ? inactive : active);
    }

    private void showDonate() {
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_donate_wechat);
        iv.setAdjustViewBounds(true);
        new AlertDialog.Builder(this)
                .setTitle("支持捐赠")
                .setMessage("如果觉得好用，请作者喝杯奶茶 🧋 微信扫一扫即可。\n\n作者：@酷安锦无名")
                .setView(iv)
                .setPositiveButton("好", null)
                .show();
    }

    private int cornerToId(int c) {
        switch (c) {
            case 1: return R.id.rb_tl;
            case 3: return R.id.rb_bl;
            case 4: return R.id.rb_br;
            default: return R.id.rb_tr;
        }
    }

    private int idToCorner() {
        int id = rgCorner.getCheckedRadioButtonId();
        if (id == R.id.rb_tl) return 1;
        if (id == R.id.rb_bl) return 3;
        if (id == R.id.rb_br) return 4;
        return 2;
    }

    private void save() {
        // 先检测 su 权限
        if (!hasRoot()) {
            new AlertDialog.Builder(this)
                .setTitle("需要超级权限")
                .setMessage("保存配置需要 Root 权限。\n\n请在 KernelSU / Magisk 管理器里给本应用授权后重试。")
                .setPositiveButton("知道了", null)
                .show();
            return;
        }
        Config.reload();
        Config.enable = swEnable.isChecked();
        Config.walkMode = swWalk.isChecked();
        Config.autoWalk = swAuto.isChecked();
        Config.lookdown = swLookdown.isChecked();
        Config.overlayForm = rgForm.getCheckedRadioButtonId() == R.id.rb_full ? 1 : 0;
        Config.corner = idToCorner();
        Config.alpha = seekAlpha.getProgress();
        try {
            Config.lookdownSec = Integer.parseInt(etLookdownSec.getText().toString().trim());
        } catch (Throwable ignored) {}
        SafeWalkCore.writeConfigAsync(ok -> {
            if (ok) {
                Toast.makeText(this, "已保存配置，SystemUI 将自动生效", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "配置写入失败，请检查 Root 权限", Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 检测是否有 su 权限（不弹窗，只静默检测） */
    private boolean hasRoot() {
        try {
            Process p = Runtime.getRuntime().exec("su -c id");
            int code = p.waitFor();
            p.destroy();
            return code == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
