package io.github.wumisc.safewalk;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * 桌面图标资源契约（5.6.10 换插画图标）。
 *
 * <p>锁三条：① 自适应 XML 的前景/背景必须指向 mipmap PNG（换图时最容易漏改引用，
 * 漏了会直接资源链接失败或显示旧矢量）；② 五个密度的前景/背景 PNG 一个都不能少
 * （缺密度会在对应设备上回退采样发糊）；③ monochrome 层必须保留（主题图标依赖它）。
 */
public class IconResourceContractTest {

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

    @Test
    public void adaptiveXmlPointsToMipmapLayers() throws IOException {
        Path m = main();
        for (String name : new String[]{"ic_launcher.xml", "ic_launcher_round.xml"}) {
            String xml = read(m.resolve("res/mipmap-anydpi").resolve(name));
            assertTrue(name + " 背景必须指向 mipmap",
                    xml.contains("@mipmap/ic_launcher_background"));
            assertTrue(name + " 前景必须指向 mipmap",
                    xml.contains("@mipmap/ic_launcher_foreground"));
            assertTrue(name + " 必须保留 monochrome 层",
                    xml.contains("@drawable/ic_launcher_monochrome"));
        }
    }

    @Test
    public void allDensityPngsPresent() throws IOException {
        Path m = main();
        for (String d : new String[]{"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"}) {
            for (String layer : new String[]{"ic_launcher_background.png", "ic_launcher_foreground.png"}) {
                Path p = m.resolve("res/mipmap-" + d).resolve(layer);
                assertTrue("缺少 " + d + "/" + layer, Files.isRegularFile(p));
                assertTrue(d + "/" + layer + " 不能是空文件", Files.size(p) > 100);
            }
        }
    }

    @Test
    public void monochromeDrawableKept() throws IOException {
        assertTrue("主题图标单色层不能丢",
                Files.isRegularFile(main().resolve("res/drawable/ic_launcher_monochrome.xml")));
    }
}
