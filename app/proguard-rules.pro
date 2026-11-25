# =============================================================================
# SafeWalk 发布包裁剪规则（5.6.8）
#
# 背景：5.6.7 的 APK 36.2MB，其中 dex 占 33.5MB —— release 一直是
# `minifyEnabled false`，等于把 Compose / Material / miuix / ZXing 的
# 全部调试与未用代码都塞进了包里。这个体积对一个「路况小窗 + 急救卡」模块
# 完全不合理。
#
# 本轮策略：**先不混淆，只做 shrink**（`-dontobfuscate`）。
#   · Xposed 生态里很多问题是「类名被改了就悄悄失效」，排查成本极高；
#   · 只删未引用代码就能砍掉大半体积，风险却低得多；
#   · 后续若要混淆，只需去掉 -dontobfuscate 并补 keep，规则已经在这里。
# =============================================================================

# 【必须】LSPosed 通过 assets/xposed_init 里的**字符串类名**加载入口，
# R8 看不到这层引用，不 keep 会直接删掉整个 Hook 模块（装上去毫无反应）。
-keep class io.github.wumisc.safewalk.SafeWalkLSPHook { *; }
-keepnames class io.github.wumisc.safewalk.SafeWalkLSPHook

# Hook 时用字符串查的是系统类（android.*），本模块类不参与反射；
# 但 xposed API 只在编译期存在，R8 会抱怨找不到实现类，这里显式忽略。
-dontwarn de.robv.android.xposed.**

# Android 组件（Activity/Service/Receiver/Provider）由 AGP 自动 keep，
# 但动态注册的 Receiver 与 TileService 也走系统按类名创建，兜底保留。
-keep class io.github.wumisc.safewalk.*TileService { *; }
-keep class io.github.wumisc.safewalk.*Receiver { *; }

# Compose / Kotlin 协程的 consumer rules 由各自 AAR 提供；
# 这里只兜底 Kotlin 元数据与注解，避免 R8 在部分版本上误删
# （这类误删的表现是运行时 NoSuchMethodError，真机上极难定位）。
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**

# 【本轮不混淆】只 shrink。去掉这一行即可切到「shrink + 混淆」，
# 但必须先补齐所有反射/序列化类名（ConfigBackup 的 JSON 字段名不受影响）。
-dontobfuscate

# Tink（androidx.security 的传递依赖）引用 JSR-305 注解，运行期不需要这个 jar，
# 但 R8 缺类会直接以 ERROR 终止构建 —— 显式忽略，别让它把发布流程卡死。
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

# 【libxposed 102】入口类由框架按 META-INF/xposed/java_init.list 里的**类名字符串**
# 反射加载，R8 看不到任何代码引用，会在 shrink 阶段直接裁掉 —— 表现就是「模块装了、
# 角标显示 API 102，但 SystemUI 里一行日志都没有」。业务内核同理（入口按名 new）。
-keep class io.github.wumisc.safewalk.SafeWalkLibxModule { *; }
-keep class io.github.wumisc.safewalk.SafeWalkLSPHook { *; }

# 【libxposed API 102 官方要求】入口类按 java_init.list 里的类名反射加载。
# 即使本工程暂时 -dontobfuscate，也把官方三条规则放齐：将来打开混淆时
# java_init.list 里的类名会被同步改写，不会出现「类名改了、清单没改」的静默失效。
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}