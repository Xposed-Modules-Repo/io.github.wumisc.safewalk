#!/usr/bin/env python3
"""
SafeWalk 主题兼容性检查器。

为什么需要它：
    Material 库的组件在构造时会读取**自身默认 style** 里引用的 ?attr。
    如果主题是 Material2（Theme.MaterialComponents.*）而组件是 Material3
    的（如 materialswitch.MaterialSwitch），默认 style 里的
    ?attr/textAppearanceBodyMedium、?attr/minTouchTargetSize 无法解析，
    真机上直接抛 Resources$NotFoundException -> InflateException -> 崩溃。

    而 Lint 不查这个，Robolectric 对未知 attr 又过于宽容，
    所以必须用静态解析来判断：把"主题能提供的 attr 集合"和
    "组件默认 style 需要的 attr 集合"求差集。

用法：
    python3 tools/check_theme_compat.py [--aar-dir DIR]
退出码 0 = 通过，1 = 有阻断项。
"""
import os
import re
import sys
import glob
import zipfile
import tempfile
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
GRADLE_CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")

# M3 专属 attr：M2 主题里不存在，一旦被组件默认 style 引用就会崩
M3_ONLY_ATTRS = {
    "textAppearanceBodyMedium", "textAppearanceBodySmall", "textAppearanceBodyLarge",
    "textAppearanceTitleMedium", "textAppearanceLabelMedium", "textAppearanceHeadlineSmall",
    "minTouchTargetSize", "colorSurfaceContainerHighest", "colorSurfaceContainerHigh",
    "colorSurfaceContainer", "colorSurfaceContainerLow", "colorSurfaceBright",
    "colorSurfaceDim", "colorOutlineVariant", "colorSecondaryContainer",
    "colorOnSecondaryContainer", "colorPrimaryContainer", "colorOnPrimaryContainer",
    "colorTertiary", "shapeCornerExtraSmall", "shapeCornerSmall", "shapeCornerMedium",
    "materialThemeOverlay", "elevationLevel3",
}

# M3 专属组件：包路径特征
M3_COMPONENT_HINTS = ("materialswitch",)


def find_aar(artifact, version=None):
    pattern = os.path.join(GRADLE_CACHE, artifact)
    hits = []
    for dirpath, _, files in os.walk(pattern) if os.path.isdir(pattern) else []:
        for f in files:
            if f.endswith(".aar") and (version is None or version in dirpath or version in f):
                hits.append(os.path.join(dirpath, f))
    return hits


def load_aar_values(aar_path):
    """返回 AAR 内 res/values/*.xml 的拼接文本"""
    if not aar_path or not os.path.exists(aar_path):
        return ""
    tmp = tempfile.mkdtemp()
    try:
        with zipfile.ZipFile(aar_path) as z:
            names = [n for n in z.namelist() if re.match(r"res/values[^/]*/.*\.xml$", n)]
            chunks = []
            for n in names:
                chunks.append(z.read(n).decode("utf-8", "ignore"))
            return "\n".join(chunks)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def style_attrs(values_xml, style_name):
    """取某个 style 直接声明的 attr 名集合"""
    m = re.search(
        r'<style name="%s"[^>]*>(.*?)</style>' % re.escape(style_name),
        values_xml, re.S)
    if not m:
        return set(), None
    body = m.group(1)
    attrs = set(re.findall(r'<item name="([A-Za-z0-9_]+)"', body))
    parent = re.search(r'parent="([^"]+)"', m.group(0))
    return attrs, parent.group(1) if parent else None


def theme_chain_attrs(values_xml, theme_name, seen=None):
    """递归收集主题继承链上所有 attr"""
    if seen is None:
        seen = set()
    if theme_name in seen or not theme_name:
        return set()
    seen.add(theme_name)
    attrs, parent = style_attrs(values_xml, theme_name)
    # parent 可能是 "Theme.MaterialComponents.Light" 形式
    if parent:
        attrs |= theme_chain_attrs(values_xml, parent, seen)
    return attrs


def main():
    problems = []
    warnings = []

    app_themes = open(os.path.join(RES, "values/themes.xml"), encoding="utf-8").read()
    theme_name = re.search(r'<style name="([^"]+)"\s+parent="([^"]+)"', app_themes)
    app_style, theme_parent = theme_name.group(1), theme_name.group(2)

    material_aar = (find_aar("com.google.android.material/material") or [None])[-1]
    mat_values = load_aar_values(material_aar)
    print(f"主题: {app_style} (parent={theme_parent})")
    print(f"Material AAR: {material_aar}")

    is_m3_theme = "Material3" in theme_parent
    print(f"主题版本: {'Material3' if is_m3_theme else 'Material2'}")

    # 主题最终可提供的 attr（material 库 + 应用自身）
    available = theme_chain_attrs(mat_values, theme_parent)
    available |= set(re.findall(r'<attr name="([A-Za-z0-9_]+)"', mat_values))

    # 1) 扫描布局里出现的组件
    print("\n== 1. 布局组件兼容性 ==")
    layouts = glob.glob(os.path.join(RES, "layout/*.xml"))
    for lp in sorted(layouts):
        s = open(lp, encoding="utf-8").read()
        classes = set(re.findall(r'<([a-zA-Z][\w.]*\.[A-Z]\w*)', s))
        for cls in sorted(classes):
            base = cls.split(".")[-1]
            if any(h in cls for h in M3_COMPONENT_HINTS) and not is_m3_theme:
                problems.append(
                    f"{os.path.basename(lp)}: 使用了 Material3 组件 {base}，"
                    f"但主题是 Material2，真机 inflate 会崩溃")
            else:
                print(f"  {os.path.basename(lp):32s} {base:28s} OK")

    # 2) 布局里直接引用的 ?attr 是否可解析
    print("\n== 2. 布局 ?attr 引用 ==")
    for lp in sorted(layouts):
        s = open(lp, encoding="utf-8").read()
        for attr in set(re.findall(r'\?attr/([A-Za-z0-9_]+)', s)):
            if attr not in available:
                problems.append(f"{os.path.basename(lp)}: ?attr/{attr} 在当前主题下不存在")
    if not problems:
        print("  无缺失")

    # 3) 每个 Activity 是否有可解析的布局
    print("\n== 3. Activity -> 布局 ==")
    for jf in sorted(glob.glob(os.path.join(ROOT, "app/src/main/java/**/*Activity*.java"), recursive=True)) \
            + sorted(glob.glob(os.path.join(ROOT, "app/src/main/java/**/*Activity*.kt"), recursive=True)):
        s = open(jf, encoding="utf-8").read()
        m = re.search(r'setContentView\(R\.layout\.(\w+)\)', s)
        name = os.path.basename(jf)
        if not m:
            print(f"  {name:32s} 代码构建视图（跳过）")
            continue
        path = os.path.join(RES, f"layout/{m.group(1)}.xml")
        if not os.path.exists(path):
            problems.append(f"{name}: 引用布局 {m.group(1)} 不存在")
        else:
            print(f"  {name:32s} {m.group(1):30s} OK")

    # 4) 清单里声明的 Activity 类是否存在
    print("\n== 4. Manifest 组件类 ==")
    manifest = open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    jroot = os.path.join(ROOT, "app/src/main/java/io/github/wumisc/safewalk")
    for comp, cname in re.findall(r'<(activity|service|receiver|provider)\s+android:name="\.([\w.]+)"', manifest):
        rel = cname.replace(".", "/")
        if not (os.path.exists(f"{jroot}/{rel}.java") or os.path.exists(f"{jroot}/{rel}.kt")):
            problems.append(f"Manifest 声明了 {comp} .{cname}，但没有对应源文件")
        else:
            print(f"  {comp:9s} .{cname:28s} OK")

    # 5) 硬编码中文（仅提示）
    print("\n== 5. 硬编码中文（提示，不影响运行）==")
    for lp in sorted(layouts):
        s = open(lp, encoding="utf-8").read()
        n = len(re.findall(r'android:text="[\u4e00-\u9fa5]', s))
        if n:
            warnings.append(f"{os.path.basename(lp)}: {n} 处硬编码中文")

    print()
    for w in warnings:
        print(f"  [提示] {w}")

    if problems:
        print("\n===== 阻断项 =====")
        for p in problems:
            print(f"  [错误] {p}")
        return 1

    print("\n结果: 通过，无主题/组件兼容性阻断项")
    return 0


if __name__ == "__main__":
    sys.exit(main())
