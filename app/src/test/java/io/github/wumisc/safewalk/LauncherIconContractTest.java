package io.github.wumisc.safewalk;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 桌面图标接线契约（5.6.10 起：前景/背景改为用户定稿插画的 PNG 图层）。
 *
 * <p>历史：5.4.16~5.6.9 前景/背景是矢量 XML，本类曾断言伞/笑脸/剪刀手等图元。
 * 5.6.10 换插画后那些图元断言全部作废，改为锁「接线」：
 * ① manifest 的 icon/roundIcon 指向 mipmap；② 两份 adaptive XML 良构且三层齐全、
 * 前景/背景指向 mipmap PNG；③ monochrome 仍是良构矢量且只用纯白（主题图标由系统着色）。
 * 图层 PNG 的密度齐全性由 {@link IconResourceContractTest} 负责。
 */
public class LauncherIconContractTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final Pattern COLOR_ATTR =
            Pattern.compile("android:(?:fillColor|strokeColor)=\"(#[0-9A-Fa-f]{6,8})\"");

    private static Path main() throws IOException {
        String rel = "src/main";
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(Paths.get(rel));
        candidates.add(Paths.get("app", rel));
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isEmpty()) {
            Path cwd = Paths.get(userDir);
            candidates.add(cwd.resolve(rel));
            candidates.add(cwd.resolve("app").resolve(rel));
            Path parent = cwd.getParent();
            if (parent != null) candidates.add(parent.resolve("app").resolve(rel));
        }
        for (Path p : candidates) {
            if (Files.isDirectory(p.resolve("res/mipmap-anydpi"))) return p;
        }
        throw new IOException("找不到 src/main/res");
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static Element parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))
                .getDocumentElement();
    }

    @Test
    public void manifestPointsToMipmapLauncher() throws IOException {
        String mf = read(main().resolve("AndroidManifest.xml"));
        assertTrue("application/activity 图标必须指向 @mipmap/ic_launcher",
                mf.contains("android:icon=\"@mipmap/ic_launcher\""));
        assertTrue("roundIcon 必须指向 @mipmap/ic_launcher_round",
                mf.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""));
    }

    @Test
    public void adaptiveXmlWellFormed_threeLayers_mipmapRaster() throws Exception {
        Path m = main();
        for (String name : new String[]{"ic_launcher.xml", "ic_launcher_round.xml"}) {
            Element root = parse(read(m.resolve("res/mipmap-anydpi").resolve(name)));
            assertTrue(name + " 根必须是 adaptive-icon", "adaptive-icon".equals(root.getLocalName()));
            // adaptive-icon 的子标签不带命名空间（只有 android:drawable 属性带），
            // 必须用无命名空间的 getElementsByTagName 找层。
            NodeList bg = root.getElementsByTagName("background");
            NodeList fg = root.getElementsByTagName("foreground");
            NodeList mono = root.getElementsByTagName("monochrome");
            assertTrue(name + " 三层齐全", bg.getLength() == 1 && fg.getLength() == 1 && mono.getLength() == 1);
            assertTrue(name + " 背景指向 mipmap PNG",
                    ((Element) bg.item(0)).getAttributeNS(ANDROID_NS, "drawable")
                            .equals("@mipmap/ic_launcher_background"));
            assertTrue(name + " 前景指向 mipmap PNG",
                    ((Element) fg.item(0)).getAttributeNS(ANDROID_NS, "drawable")
                            .equals("@mipmap/ic_launcher_foreground"));
            assertTrue(name + " 单色层保留矢量",
                    ((Element) mono.item(0)).getAttributeNS(ANDROID_NS, "drawable")
                            .equals("@drawable/ic_launcher_monochrome"));
        }
    }

    @Test
    public void monochromeWellFormed_andPureWhite() throws Exception {
        String xml = read(main().resolve("res/drawable/ic_launcher_monochrome.xml"));
        Element root = parse(xml);
        assertTrue("单色层必须是 vector", "vector".equals(root.getLocalName()));
        String live = xml.replaceAll("(?s)<!--.*?-->", "");
        Matcher m = COLOR_ATTR.matcher(live);
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue("单色层只允许纯白（系统按主题着色），发现 " + m.group(1),
                    m.group(1).equalsIgnoreCase("#FFFFFF"));
        }
        assertTrue("单色层必须有着色图元", n > 0);
    }
}
