package io.github.wumisc.safewalk;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

/**
 * 功能建议（关于页 → 二级页）。
 *
 * 【为什么不做成自建表单】
 * 自建表单需要一个后端去收，而后端没人维护时表单就是黑洞 —— 用户认真写完，
 * 永远等不到回复。这里改成把建议直接变成一条 GitHub issue：公开、可追踪、
 * 有状态（open / closed），作者关掉它时必须给出理由。
 *
 * 【为什么要自动附环境信息】
 * 「小窗不显示」这类问题，九成取决于机型、ROM、系统版本。让用户手打这些
 * 既容易漏也容易错，直接从 Build 里读，提交时一并带上。
 *
 * 【为什么要有「复制」】
 * 不是所有人都有 GitHub 账号，也不是所有网络都能打开它。复制出来发到酷安，
 * 同样能被看到 —— 这条兜底路径比一个打不开的按钮有用。
 */
public final class FeedbackActivity extends Activity {

    private static final String ISSUE_BASE =
            "https://github.com/Xposed-Modules-Repo/io.github.wumisc.safewalk/issues/new";

    private EditText etSuggestion;
    private SwitchCompat swAttach;
    private TextView tvEnvPreview;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_feedback);
        DiagLog.init(getApplicationContext());

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        etSuggestion = findViewById(R.id.et_suggestion);
        swAttach = findViewById(R.id.sw_attach);
        tvEnvPreview = findViewById(R.id.tv_env_preview);

        // 默认带上：不带环境信息的建议，多半要来回问三轮才能定位
        swAttach.setChecked(true);
        refreshEnvPreview();
        swAttach.setOnCheckedChangeListener((v, c) -> refreshEnvPreview());
        findViewById(R.id.card_attach).setOnClickListener(v -> swAttach.toggle());

        findViewById(R.id.btn_submit).setOnClickListener(v -> submit());
        findViewById(R.id.btn_copy).setOnClickListener(v -> copy());
    }

    /** 环境信息一行人话，同时也是复制/提交时用的同一份数据源 */
    private String envLines() {
        String rom;
        try {
            rom = RomDetector.detectFromBuild().getDisplayName();
        } catch (Throwable t) {
            rom = "未知";
        }
        String version = "未知";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Throwable ignored) {
        }
        return "机型：" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "系统：Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "UI：" + rom + "\n"
                + "应用：" + version;
    }

    private void refreshEnvPreview() {
        tvEnvPreview.setText(swAttach.isChecked()
                ? envLines()
                : "不附带设备信息。作者可能无法复现你说的问题。");
    }

    /** 建议正文 + 可选的环境信息，拼成 issue body */
    private String buildBody() {
        StringBuilder sb = new StringBuilder();
        sb.append("### 想要什么\n\n");
        sb.append(etSuggestion.getText().toString().trim());
        if (swAttach.isChecked()) {
            sb.append("\n\n---\n\n<details><summary>设备信息</summary>\n\n");
            sb.append(envLines().replace("\n", "\n\n"));
            sb.append("\n\n</details>\n");
        }
        return sb.toString();
    }

    private String buildTitle() {
        String raw = etSuggestion.getText().toString().trim();
        if (raw.isEmpty()) return "[建议] ";
        // 只取第一行，超过 40 字截断 —— issue 标题过长在列表里会被折成两行
        String first = raw.split("\n")[0].trim();
        if (first.length() > 40) first = first.substring(0, 40) + "…";
        return "[建议] " + first;
    }

    private void submit() {
        if (TextUtils.isEmpty(etSuggestion.getText().toString().trim())) {
            Toast.makeText(this, "先写两句想加什么", Toast.LENGTH_SHORT).show();
            etSuggestion.requestFocus();
            return;
        }
        Uri uri = Uri.parse(ISSUE_BASE).buildUpon()
                .appendQueryParameter("title", buildTitle())
                .appendQueryParameter("body", buildBody())
                .build();
        DiagLog.log("[feedback] 打开 issue 提交页：" + buildTitle());
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Throwable t) {
            // 没有浏览器 / 被 ROM 拦截：退回复制，别让用户白写一段
            DiagLog.log("[feedback] 打开浏览器失败：" + t.getMessage() + "，改为复制");
            copy();
        }
    }

    private void copy() {
        String text = buildTitle() + "\n\n" + buildBody();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("SafeWalk 功能建议", text));
                Toast.makeText(this, "已复制，可粘贴到酷安或 GitHub", Toast.LENGTH_SHORT).show();
                DiagLog.log("[feedback] 建议内容已复制到剪贴板");
                return;
            }
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "复制失败，请长按输入框手动选择", Toast.LENGTH_SHORT).show();
    }
}
