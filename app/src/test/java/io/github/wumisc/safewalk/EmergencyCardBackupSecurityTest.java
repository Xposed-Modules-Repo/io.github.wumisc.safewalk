package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 【5.5.0 急救卡导出/导入】安全契约测试（QA 独立编写，纯 JVM 读源码）。
 *
 * <p>导出的是<b>明文医疗信息</b>，所以「功能能用」还不够，必须同时锁死四条安全约束：
 * <ol>
 *   <li><b>全程 SAF</b>：只用 ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT，不申请、不使用存储权限；</li>
 *   <li><b>不写公共目录</b>：不得出现 /sdcard、Environment.getExternalStorage* 之类的路径拼接；</li>
 *   <li><b>日志不得打印卡片明文</b>：导出/导入路径只允许打事件名（card_exported / card_imported），
 *       不得把姓名/过敏/住址写进 DiagLog；</li>
 *   <li><b>显式知情</b>：导出前必须有「明文 + 敏感」风险提示，空卡必须提前拦截，
 *       文件大小必须有上限（用户可能选到几十兆的相册文件）。</li>
 * </ol>
 *
 * <p>这些约束都无法在 JVM 里靠运行时行为验证（需要系统 DocumentsUI），只能用源码结构锁死；
 * 运行时行为由 {@link EmergencyCardExportUiTest} 的 Intent 契约补足。
 */
public class EmergencyCardBackupSecurityTest {

    private static final String ACTIVITY_REL =
            "src/main/java/io/github/wumisc/safewalk/EmergencyCardActivity.java";

    private static String activitySrc;
    private static String activityCode;

    @BeforeClass
    public static void loadSources() throws IOException {
        activitySrc = new String(Files.readAllBytes(locate(ACTIVITY_REL)), StandardCharsets.UTF_8);
        activityCode = stripComments(activitySrc);
    }

    // ==================== ① SAF / 无存储权限 ====================

    /**
     * 注意口径：本 App 的清单里<b>确实</b>声明了 READ/WRITE_EXTERNAL_STORAGE 与
     * MANAGE_EXTERNAL_STORAGE —— 那是既有功能（诊断日志/配置落地）在用，不是本次改动引入的。
     * 本特性要保证的是「导出导入这条路不碰存储权限」，所以断言的是<b>代码路径</b>，不是清单。
     */
    @Test
    public void exportImportPath_usesNoStoragePermissionApis() {
        String export = normalize(extractMethod(activityCode, "void exportCard()"));
        String create = normalize(extractMethod(activityCode, "void launchCreateDocument()"));
        String pick = normalize(extractMethod(activityCode, "void pickImportFile()"));
        String write = normalize(extractMethod(activityCode, "void writeExportFile(Uri uri)"));
        String readText = normalize(extractMethod(activityCode, "String readText(Uri uri)"));

        for (String body : new String[]{export, create, pick, write, readText}) {
            assertFalse("导出/导入路径不得检查存储权限（SAF 不需要）",
                    body.contains("checkSelfPermission"));
            assertFalse("导出/导入路径不得申请权限", body.contains("requestPermissions"));
            assertFalse("导出/导入路径不得引用 EXTERNAL_STORAGE",
                    body.contains("EXTERNAL_STORAGE"));
            assertFalse("导出/导入路径不得引用 MANAGE_EXTERNAL_STORAGE",
                    body.contains("MANAGE_EXTERNAL_STORAGE"));
        }
    }

    @Test
    public void exportUsesCreateDocumentImportUsesOpenDocument() {
        assertTrue("导出必须用 ACTION_CREATE_DOCUMENT",
                activityCode.contains("new Intent(Intent.ACTION_CREATE_DOCUMENT)"));
        assertTrue("导入必须用 ACTION_OPEN_DOCUMENT",
                activityCode.contains("new Intent(Intent.ACTION_OPEN_DOCUMENT)"));
        assertTrue("导出/导入都必须带 CATEGORY_OPENABLE（否则部分 ROM 返回不可读 URI）",
                countOccurrences(activityCode, "addCategory(Intent.CATEGORY_OPENABLE)") == 2);
        assertTrue("必须声明 application/json",
                countOccurrences(activityCode, "setType(\"application/json\")") == 2);
    }

    @Test
    public void neverTouchesPublicStoragePaths() {
        assertFalse("不得拼接 /sdcard 路径", activityCode.contains("/sdcard"));
        assertFalse("不得使用 Environment.getExternalStorage*",
                activityCode.contains("getExternalStorage"));
        assertFalse("不得直接用 File 构造导出目标（位置必须由用户经 SAF 决定）",
                activityCode.contains("new java.io.File(")
                        || activityCode.contains("new File("));
    }

    // ==================== ② 日志不得含明文 ====================

    @Test
    public void exportImportPaths_writeNoCardValuesToLog() {
        String write = normalize(extractMethod(activityCode, "void writeExportFile(Uri uri)"));
        String read = normalize(extractMethod(activityCode, "void readImportFile(Uri uri)"));
        String apply = normalize(extractMethod(activityCode, "void applyImport(EmergencyCard card)"));

        for (String body : new String[]{write, read, apply}) {
            assertFalse("导出/导入路径不得调用 DiagLog 写卡片内容", body.contains("DiagLog.log"));
            assertFalse("不得把姓名写进任何日志", body.contains("card.getName()")
                    && body.contains("log"));
            assertFalse("不得把过敏史写进任何日志", body.contains("getAllergy()"));
            assertFalse("不得把住址写进任何日志", body.contains("getAddress()"));
        }
        // 只允许打事件名（无值）
        assertTrue("导出成功应留一条无值事件（便于真机核对）",
                write.contains("card_exported"));
        assertTrue("导入成功应留一条无值事件", apply.contains("card_imported"));
    }

    @Test
    public void exportWritesUtf8InOverwriteMode() {
        String write = normalize(extractMethod(activityCode, "void writeExportFile(Uri uri)"));
        assertTrue("必须用 \"wt\" 覆盖写（截断旧文件，避免残留尾部脏数据）",
                write.contains("openOutputStream(uri, \"wt\")"));
        assertTrue("必须显式 UTF-8，否则中文姓名/过敏史在部分 ROM 上变问号",
                write.contains("StandardCharsets.UTF_8"));
        assertTrue("导出内容必须来自 Vault 里已保存的卡（未保存的编辑不得混入备份）",
                write.contains("EmergencyVault.readCard()"));
        assertTrue("必须调用纯逻辑层生成 JSON（单一 schema）",
                write.contains("EmergencyCardBackup.exportJson("));
    }

    @Test
    public void importAppliesToVaultAndRefreshesUi() {
        String apply = normalize(extractMethod(activityCode, "void applyImport(EmergencyCard card)"));
        assertTrue("必须写入加密 Vault", apply.contains("EmergencyVault.saveCard(card)"));
        assertTrue("必须立刻刷新界面，让用户当场核对", apply.contains("fillFromVault()"));
        assertTrue("落盘失败必须有明确提示（不能假装成功）",
                apply.contains("未落盘") || apply.contains("丢失"));
    }

    // ==================== ③ 知情与防护 ====================

    @Test
    public void exportShowsPlaintextRiskDialogBeforePickingFile() {
        String body = normalize(extractMethod(activityCode, "void exportCard()"));
        assertTrue("必须先弹风险提示", body.contains("setTitle("));
        assertTrue("必须明示包含血型/过敏/住址等敏感信息", body.contains("敏感"));
        assertTrue("必须明示是明文（未加密）", body.contains("明文"));
        assertTrue("必须提醒不要发群聊/网盘/公共目录", body.contains("群聊"));
        assertTrue("必须提示重装后删除该文件", body.contains("删除"));
        assertTrue("确认后才拉起文件选择器", body.contains("launchCreateDocument()"));
        // 顺序：先弹框（里面才有 launchCreateDocument），不得直接拉起
        assertTrue("不得在弹框之前就拉起文件选择器",
                body.indexOf("launchCreateDocument") > body.indexOf("AlertDialog"));
    }

    @Test
    public void emptyCardExportIsBlocked() {
        String body = normalize(extractMethod(activityCode, "void exportCard()"));
        assertTrue("必须判空（无姓名且无联系人）", body.contains("isEmpty()"));
        assertTrue("空卡必须提示先保存", body.contains("先填写") || body.contains("保存"));
    }

    @Test
    public void importHasSizeCapAndBomStrip() throws Exception {
        Field f = EmergencyCardActivity.class.getDeclaredField("MAX_IMPORT_BYTES");
        f.setAccessible(true);
        assertEquals("导入上限必须是 256KB（防止被选中的大文件撑爆内存）",
                256 * 1024, f.getInt(null));

        String readText = normalize(extractMethod(activityCode, "String readText(Uri uri)"));
        assertTrue("必须剥 BOM，否则 Windows 记事本存的备份会被判非法",
                readText.contains("\\uFEFF") || readText.contains("\uFEFF"));
        assertTrue("必须按 UTF-8 解码", readText.contains("StandardCharsets.UTF_8"));
        assertTrue("超限必须抛错而不是静默截断", readText.contains("MAX_IMPORT_BYTES"));
    }

    @Test
    public void importFailureIsExplicitNotSilent() {
        String read = normalize(extractMethod(activityCode, "void readImportFile(Uri uri)"));
        assertTrue("解析失败必须提示「文件无法识别」而不是静默无反应",
                read.contains("无法识别"));
        assertTrue("解析失败不得覆盖现有急救卡",
                read.indexOf("applyImport") > read.indexOf("无法识别"));
        assertTrue("导入前必须弹确认框（覆盖是不可逆的）", read.contains("AlertDialog"));
        assertTrue("确认框必须说明会覆盖当前急救卡", read.contains("覆盖"));
    }

    // ==================== 工具 ====================

    private static Path locate(String rel) throws IOException {
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(Paths.get(rel));
        candidates.add(Paths.get("app", rel));
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isEmpty()) {
            Path cwd = Paths.get(userDir);
            candidates.add(cwd.resolve(rel));
            candidates.add(cwd.resolve("app").resolve(rel));
            Path parent = cwd.getParent();
            if (parent != null) {
                candidates.add(parent.resolve("app").resolve(rel));
                candidates.add(parent.resolve(rel));
            }
        }
        List<String> tried = new ArrayList<String>();
        for (Path p : candidates) {
            tried.add(p.toAbsolutePath().toString());
            if (Files.isRegularFile(p) && Files.isReadable(p)) {
                return p;
            }
        }
        throw new IOException("找不到源码 " + rel + "，已尝试：" + tried);
    }

    private static String extractMethod(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue("源码中找不到签名：" + signature, start >= 0);
        int brace = src.indexOf('{', start);
        assertTrue("签名后找不到左花括号：" + signature, brace >= 0);
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        char[] cs = src.toCharArray();
        for (int i = brace; i < cs.length; i++) {
            char c = cs[i];
            if (inString || inChar) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (inString && c == '"') inString = false;
                if (inChar && c == '\'') inChar = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(brace, i + 1);
            }
        }
        throw new AssertionError("方法未闭合：" + signature);
    }

    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inString = false;
        boolean inChar = false;
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString || inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    out.append(next);
                    i++;
                    continue;
                }
                if (inString && c == '"') inString = false;
                if (inChar && c == '\'') inChar = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '\'') {
                inChar = true;
                out.append(c);
            } else if (c == '/' && next == '/') {
                inLine = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlock = true;
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return count;
            count++;
            from = idx + needle.length();
        }
    }
}
