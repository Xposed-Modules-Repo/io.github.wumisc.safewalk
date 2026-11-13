package io.github.wumisc.safewalk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 调试面板格式化规则的回归锁。
 *
 * ## 为什么这些细节值得测
 *
 * 调试工具最大的风险是「它自己骗人」：缩进少了会看错父子关系，截断没标出来
 * 会让人以为页面就这么多节点，空字段打成 `null` 会让人以为真有这个文本。
 * 这些都是纯字符串逻辑，一条断言就够，没有任何理由不测。
 */
public class A11yEventFormatterTest {

    // ---------------------------------------------------------------- 事件名

    @Test
    public void eventTypeNamesMatchAosp() {
        assertEquals("CLICK", A11yEventFormatter.eventTypeName(0x00000001));
        assertEquals("WINDOW_STATE", A11yEventFormatter.eventTypeName(0x00000020));
        assertEquals("CONTENT_CHANGED", A11yEventFormatter.eventTypeName(0x00000800));
        assertEquals("NOTIFICATION", A11yEventFormatter.eventTypeName(0x00000040));
        assertEquals("A11Y_FOCUS", A11yEventFormatter.eventTypeName(0x00008000));
    }

    @Test
    public void unknownEventTypeShowsHex() {
        // 认不出的类型要给出十六进制，能直接拿去 AOSP 源码里查；
        // 打成 UNKNOWN 就丢掉了唯一有用的线索
        String s = A11yEventFormatter.eventTypeName(0x00020000);
        assertTrue("未知类型应输出十六进制：" + s, s.startsWith("0x"));
        assertTrue(s.length() >= 8);
    }

    // ---------------------------------------------------------------- 一行摘要

    @Test
    public void oneLineJoinsFieldsInOrder() {
        String s = A11yEventFormatter.oneLine(
                "12:03:41.120", 0x00000001, "com.tencent.mm", "android.widget.TextView", "微信");
        assertEquals("12:03:41.120 CLICK com.tencent.mm TextView  '微信'", s);
    }

    @Test
    public void oneLineOmitsEmptyText() {
        String s = A11yEventFormatter.oneLine("00:00:00.000", 0x00000020, "pkg", "", "");
        assertEquals("00:00:00.000 WINDOW_STATE pkg -", s);
    }

    // ---------------------------------------------------------------- 节点树

    @Test
    public void treeIndentsByDepth() {
        List<A11yNode> nodes = new ArrayList<>();
        nodes.add(new A11yNode(0, "FrameLayout"));
        nodes.add(new A11yNode(1, "LinearLayout"));
        nodes.add(new A11yNode(2, "TextView", "设置", "", "title", true, true, "[0,0][100,50]", 0));

        String[] lines = A11yEventFormatter.formatTree(nodes).split("\n");
        assertEquals(3, lines.length);
        assertTrue("根节点顶格", lines[0].startsWith("#0 FrameLayout"));
        assertTrue("一级缩进 2 空格", lines[1].startsWith("  #1 LinearLayout"));
        assertTrue("二级缩进 4 空格", lines[2].startsWith("    #2 TextView"));
        assertTrue("可点击要标出来", lines[2].contains("[点击]"));
        assertTrue("id 要带出来", lines[2].contains("id=title"));
    }

    @Test
    public void emptyTreeIsExplicit() {
        assertEquals("(空节点树)", A11yEventFormatter.formatTree(Collections.emptyList()));
    }

    @Test
    public void treeTruncationIsAnnounced() {
        // 静悄悄截断是最坏的情况：让人以为页面就这么点东西
        List<A11yNode> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) nodes.add(new A11yNode(0, "V" + i));
        String s = A11yEventFormatter.formatTree(nodes, 4);
        assertTrue(s.contains("还有 6 个节点未显示"));
    }

    @Test
    public void nullLikeFieldsAreOmittedNotPrintedAsNull() {
        A11yNode n = new A11yNode(0, "ImageView", "", "", "");
        String line = A11yEventFormatter.nodeLine(n);
        assertFalse("空字段不能打成 null/'' 污染输出：" + line, line.contains("null"));
        assertFalse(line.contains("text=''"));
        assertFalse(line.contains("desc=''"));
    }

    // ---------------------------------------------------------------- 文本处理

    @Test
    public void clipFlattensNewlines() {
        // 多行文本压成一行，否则一条事件会撑成三四行，面板完全没法扫
        assertEquals("a b c", A11yEventFormatter.clip("a\nb\r\nc"));
    }

    @Test
    public void clipTruncatesWithEllipsis() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('x');
        String out = A11yEventFormatter.clip(sb.toString());
        assertEquals(A11yEventFormatter.MAX_TEXT, out.length());
        assertTrue(out.endsWith("…"));
    }

    @Test
    public void shortClassStripsPackage() {
        assertEquals("TextView", A11yEventFormatter.shortClass("android.widget.TextView"));
        assertEquals("TextView", A11yEventFormatter.shortClass("TextView"));
        assertEquals("-", A11yEventFormatter.shortClass(""));
    }

    // ---------------------------------------------------------------- 无标签

    @Test
    public void nodeWithoutAnyLabelIsDetected() {
        // 这正是读屏增强要补的目标：可点击、但 TalkBack 只会念「按钮」
        assertTrue(A11yEventFormatter.hasNoLabel(new A11yNode(0, "ImageView", "", "", "")));
        assertFalse(A11yEventFormatter.hasNoLabel(new A11yNode(0, "TextView", "设置")));
        assertFalse(A11yEventFormatter.hasNoLabel(new A11yNode(0, "ImageView", "", "返回")));
        assertFalse("有 viewId 也算有线索", A11yEventFormatter.hasNoLabel(
                new A11yNode(0, "ImageView", "", "", "btn_back")));
    }
}
