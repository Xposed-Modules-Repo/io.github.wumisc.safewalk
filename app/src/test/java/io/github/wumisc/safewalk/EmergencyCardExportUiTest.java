package io.github.wumisc.safewalk;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * 【5.5.0 急救卡备份】UI 接线验收测试（Robolectric）。
 *
 * <p>能测到的是「点下去到底发生了什么」：走的是不是 SAF（ACTION_CREATE_DOCUMENT /
 * ACTION_OPEN_DOCUMENT，application/json + CATEGORY_OPENABLE），空卡是否被提前拦截，
 * 页面上是否有明文风险提示。
 *
 * <p><b>测不到的部分（边界声明，避免误读为已覆盖）：</b>
 * <ol>
 *   <li>真实 SAF 落盘：需要系统 DocumentsUI + ContentResolver 授权，Robolectric 无此实现；
 *       「导出 → 导入 → 写回 Vault」的端到端因此<b>不在本文件</b>，
 *       其等价物由 {@link EmergencyCardExportImportTest} 的真文件往返 + 本文件的 Intent 契约合起来逼近。</li>
 *   <li>写回 Vault：{@code EncryptedSharedPreferences} 依赖 AndroidKeyStore，Robolectric 下必然失败，
 *       {@code saveCard} 会返回 false（代码有明确降级提示）。故「导入后 Vault 里真的有这张卡」
 *       只能真机验证。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xhdpi")
public class EmergencyCardExportUiTest {

    @Test
    public void exportAndImportButtonsExist() {
        ActivityController<EmergencyCardActivity> c =
                Robolectric.buildActivity(EmergencyCardActivity.class).setup();
        try {
            Button export = c.get().findViewById(R.id.btn_export_emergency);
            Button importBtn = c.get().findViewById(R.id.btn_import_emergency);
            assertNotNull("导出按钮必须存在于急救卡页面", export);
            assertNotNull("导入按钮必须存在于急救卡页面", importBtn);
            assertTrue("导出按钮必须可点", export.isEnabled());
            assertTrue("导入按钮必须可点", importBtn.isEnabled());
        } finally {
            c.pause().stop().destroy();
        }
    }

    @Test
    public void importUsesSafOpenDocumentWithJsonType() {
        ActivityController<EmergencyCardActivity> c =
                Robolectric.buildActivity(EmergencyCardActivity.class).setup();
        try {
            EmergencyCardActivity a = c.get();
            a.findViewById(R.id.btn_import_emergency).performClick();
            Intent started = shadowOf(a).getNextStartedActivity();
            assertNotNull("点导入必须拉起系统文件选择器", started);
            assertTrue("必须用 ACTION_OPEN_DOCUMENT（SAF，不需要存储权限）",
                    Intent.ACTION_OPEN_DOCUMENT.equals(started.getAction()));
            assertTrue("必须限定 CATEGORY_OPENABLE",
                    started.hasCategory(Intent.CATEGORY_OPENABLE));
            assertTrue("必须声明 application/json", "application/json".equals(started.getType()));
        } finally {
            c.pause().stop().destroy();
        }
    }

    @Test
    public void emptyCard_exportIsBlockedBeforeAnyFilePicker() {
        // 空卡导出没有意义，且会在用户选的文件里留下一份空备份 —— 必须先拦下并提示。
        // Vault 在 Robolectric 下为空，正好覆盖这条前置校验。
        ActivityController<EmergencyCardActivity> c =
                Robolectric.buildActivity(EmergencyCardActivity.class).setup();
        try {
            EmergencyCardActivity a = c.get();
            a.findViewById(R.id.btn_export_emergency).performClick();
            Intent started = shadowOf(a).getNextStartedActivity();
            assertNull("空卡时不得拉起文件选择器", started);
            String toast = ShadowToast.getTextOfLatestToast();
            assertNotNull("空卡时必须给出提示", toast);
            assertTrue("提示应说明先保存急救卡，实际=" + toast,
                    toast.contains("保存") || toast.contains("没有"));
        } finally {
            c.pause().stop().destroy();
        }
    }

    @Test
    public void pageShowsPlaintextWarning() {
        ActivityController<EmergencyCardActivity> c =
                Robolectric.buildActivity(EmergencyCardActivity.class).setup();
        try {
            View root = c.get().findViewById(android.R.id.content);
            String all = collectText(root);
            assertTrue("页面必须出现「备份与恢复」区块", all.contains("备份与恢复"));
            assertTrue("页面必须明示导出文件是明文", all.contains("明文"));
        } finally {
            c.pause().stop().destroy();
        }
    }

    private static String collectText(View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) sb.append(t).append('\n');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sb.append(collectText(g.getChildAt(i)));
        }
        return sb.toString();
    }
}
