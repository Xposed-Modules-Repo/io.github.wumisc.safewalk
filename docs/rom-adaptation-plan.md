# SafeWalk 全国产 UI 适配计划

> 基线：4.0.1（41）｜目标版本：5.0「让开源再次伟大」｜拟稿：2026-11-10｜4.3.0 起扩为「五厂商 + 原生」｜4.8.0 起扩为「六厂商 + 原生」

## 0. 一页摘要

把现在的「HyperOS 专用适配」升级成**表驱动的多 ROM 适配层**，覆盖小米（HyperOS）、
荣耀（MagicOS）、OPPO/一加/realme（ColorOS）、vivo/iQOO（OriginOS）、华为（EMUI/HarmonyOS 4
及更早）、魅族 Flyme + 原生/类原生，共六厂商 + 原生；同时把图标从「一张 PNG 前景 + 纯色底」升级成
**三层自适应图标 + 单色主题图标**。

两条硬约束，先写在最前面：

1. **不把"通用能力"降级成"厂商判断"。** 4.0 已经证明：前台服务、悬浮窗、手电这三件事
   靠运行时降级链比靠版本号判断可靠得多。本次只在「系统没有公开 API、只能给用户指路」
   的场景引入 ROM 表（自启动页、电池页、后台弹窗页）。
2. **表写错了也不能崩。** 每家 ROM 的私有页 component 都随版本漂移，因此所有表项必须
   先 `resolveActivity` 校验，失败静默回退到 `ACTION_APPLICATION_DETAILS_SETTINGS`，
   并留一行日志说明哪一条没命中。

---

## 1. 现状：哪些地方其实已经 ROM 无关

先把家底说清楚，避免重复劳动：

| 能力 | 现状 | ROM 相关性 |
| --- | --- | --- |
| Hook 注入 | 4 条锚点（`SystemUIApplication.onCreate` / `Instrumentation.callApplicationOnCreate` / `SystemUIService.onCreate` / `ActivityThread` 兜底） | **无**。任一锚点命中即可，OEM 改类名有兜底 |
| 前台服务 | `camera → specialUse → 无 type` 逐档尝试 | **无**。看 `startForeground` 是否抛异常 |
| 悬浮窗 | 每次显示前查 `canDrawOverlays()` | **无** |
| 手电 | 预览会话内 `FLASH_MODE_TORCH` | **无** |
| 磁贴 | `TileService` + 透明中转页绕过后台启动限制 | **无** |
| 无障碍收起小窗 | 前台包名匹配相机类应用 | 仅包名表需要补全 |
| 通知 | 独立渠道 + 冷却 | **无**（超级岛/灵动胶囊属厂商增强，缺失不影响功能） |

**结论：功能主干已经跨 ROM 通用，剩下的都是"指路"和"外观"。**

## 2. 硬编码清单（本次要改的全部位置）

| # | 位置 | 现状 | 问题 |
| --- | --- | --- | --- |
| 1 | `PermissionOnboardingActivity.specialSteps` | 第 4 项写死「自启动 / 后台活动（HyperOS）」 | MagicOS/ColorOS/OriginOS 用户看到别人的系统名 |
| 2 | `PermissionOnboardingActivity` 自启动跳转 | 只认小米两条 component | 其他 ROM 直接失败回退详情页 |
| 3 | `HyperOsCompat.autostartSettingsIntent` | 只有小米 | 同上，且类名本身绑死 HyperOS |
| 4 | `CameraAppPolicy.CAMERA_LIKE` | 11 个包名 | 缺 iQOO、realme、POCO 独立相机、荣耀新包名等子品牌 |
| 5 | `EmergencyCardActivity.openSosSettings` | 5 条路径，无预校验 | 顺序 `startActivity` 失败才下一条，会闪一下才跳走 |
| 6 | `HyperOsCompat` 命名与全部文案 | HyperOS 专有 | 「非 HyperOS」用户读到的是"未识别"，不是"已支持" |
| 7 | `mipmap-anydpi-v26/ic_launcher.xml` | 缺 `<monochrome>` 层 | Android 13+ 主题图标失效，桌面统一风格时被降级成彩色缩略 |
| 8 | `ic_launcher_foreground_new.png` | 位图前景 | 各密度拉伸，边缘发虚；已有 `ic_launcher_foreground.xml` 却未启用 |
| 9 | `ic_qs_walk.xml` / 通知小图标 | 未与主图标共用网格 | 视觉不统一 |

## 3. 目标架构：一张表 + 三条原则

```
RomId 探测（Build.* + 厂商 prop，失败 → AOSP）
        │
        ▼
RomProfile（纯数据，可单测）
  ├─ autostartPages: List<ComponentName>   ← 只有这里用得到厂商差异
  ├─ batteryPages:   List<Intent>
  ├─ backgroundPopupPages: List<ComponentName>
  ├─ cameraPkgs:     Set<String>           ← 与通用表合并，不是替换
  └─ displayName:    String                ← 文案用「你的系统」而不是硬编码名字
        │
        ▼
引导 UI（resolveActivity 逐个校验 → 命中即跳 → 全失败回退应用详情页 + 日志）
```

**原则一：能运行时试的，绝不查表。**
前台服务、悬浮窗、手电、Hook 锚点全部保持现状，不接 RomProfile。

**原则二：表只回答"用户该去哪个页面"，不回答"功能能不能用"。**
即使表全错，功能仍在，只是用户要多点两下。

**原则三：UI 文案用第二人称。**
「请在系统设置里允许自启动」优于「请开启 HyperOS 自启动」。前者永远不会写错系统名。

## 4. 六厂商差异矩阵（+ 原生）

> ⚠️ component 名称随 ROM 版本漂移，下表为**候选值**，落地时每条都要用
> `adb shell cmd package resolve-activity --brief <component>` 或真机 `resolveActivity` 校验。
> 表的价值不在"名字对"，而在"错了会安全回退"。

| 维度 | HyperOS 3/4 | MagicOS 9 | ColorOS 15 | OriginOS 5 | EMUI 13 / HarmonyOS 4 | 原生 / 类原生 |
| --- | --- | --- | --- | --- | --- | --- |
| SystemUI 包名 | `com.android.systemui` | 同 | 同 | 同 | 同 | 同 |
| 自启动页 | `com.miui.securitycenter` · permcenter/autostart 两套路径 | `com.hihonor.systemmanager` · startupmgr | `com.oplus.safecenter` · startupapp | `com.vivo.permissionmanager` · BgStartUp | `com.huawei.systemmanager` · startupmgr（旧版 ProtectActivity） | 无此概念 |
| 电池白名单 | 省电策略 → 无限制 | 应用启动管理 → 手动管理 | 耗电管理 → 允许后台活动 | 后台高耗电 → 允许 | 应用启动管理 → 手动管理 | `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` |
| 后台弹窗 | 有独立开关（后台弹出界面） | 无独立开关 | 有（后台弹出界面） | 有（后台弹出） | 无独立开关（权限页找悬浮窗） | 无 |
| 悬浮窗 | 权限 → 悬浮窗 | 权限 → 悬浮窗 | 权限 → 悬浮窗 | 权限 → 悬浮窗 | 权限 → 悬浮窗 | `ACTION_MANAGE_OVERLAY_PERMISSION` |
| 主题图标 | 支持（图标风格） | 支持（主题） | 支持（个性化） | 支持（原子风格） | 支持（图标风格） | Android 13+ monochrome |
| 图标遮罩 | 圆角矩形（可换形状） | 圆角矩形 | 圆形 / 水滴 / 圆角 | 圆形 | 圆角矩形 | 圆形 / 方圆 |
| 系统级提醒增强 | 超级岛 | 灵动胶囊 | 流体云 | 原子通知 | 实况窗 | 无 |
| 已知收紧点 | camera FGS 后台启动、悬浮窗回收 | 后台进程回收激进 | 后台启动管控、自启动默认关 | 后台高耗电杀进程 | 应用启动管理默认全自动 | 仅 Doze |

**关于"系统级提醒增强"**：各家都有自己的岛/胶囊 API，但都需要厂商私有接口或合作白名单。
本计划**不接入**，只用标准通知；在 5.0 文案里明确写「不依赖超级岛/灵动胶囊，通知被折叠也不影响功能」——
把"没适配"说成"不需要适配"，比假装支持更诚实。

> **4.3.0 的界面调整**：首页不再让用户选「HyperOS 3 / 4」，而是「自动 / 小米 / 荣耀 / OPPO /
> vivo / 华为 / 原生」七个芯片。理由见 `RomSettings.kt` 顶部注释：版本号从来没参与决策，
> 真正影响行为的是「哪一家 UI」。华为是 4.3.0 新补的一家（EMUI 5–11 / HarmonyOS 2–4），
> 两条自启动路径仅做过静态核对，尚未真机验证；HarmonyOS NEXT 不兼容 Android 应用，不在范围内。

## 5. 图标体系：从一张 PNG 到三层矢量

这是「图标更精致」的落点。现在的 `ic_launcher.xml` 只有 background + foreground，
缺 monochrome，前景还是位图。

### 5.1 三层结构（108×108dp 画布，72dp 安全区）

| 层 | 内容 | 要求 |
| --- | --- | --- |
| background | 深蓝渐变（`#0A2542 → #123A63`，矢量） | 必须铺满 108dp，因为各家遮罩形状不同，边缘随时会被切 |
| foreground | 行走/路况符号（矢量） | 关键元素收在中心 **66dp** 内，任何遮罩都不切 |
| monochrome | 同一符号的**重新设计**剪影 | 不是把前景去色：细线要加粗到 24dp 下仍可见，负空间要能撑住 |

### 5.2 单色层为什么必须重画

系统把 monochrome 渲染成单色并可能反相（深色桌面）。直接复用彩色前景会出现：
描边 1.5dp 在 24dp 下糊成一片、渐变变纯色后层次全丢。做法是**只保留轮廓 + 一个实心块**，
把"路 + 人"简化成两三个形状。

### 5.3 各桌面形状验收

用同一份前景，在四种遮罩下预览：圆角矩形（半径 22% / 26%）、圆形、水滴、方圆。
判定标准：**符号最大外接圆不超过 66dp**，则四种遮罩下都不会被切到。

### 5.4 其他图标统一

- 通知小图标：纯白剪影，24dp 网格，不能有透明渐变
- 磁贴图标（`ic_qs_walk.xml`）：与主图标同一套几何，缩到 24dp 仍可辨识
- 空状态/引导页插图：复用同一套描边粗细（1.5dp / 2dp 两档）

## 6. 分阶段路线

| 阶段 | 周期 | 交付 | 验收 |
| --- | --- | --- | --- |
| **P0 抽取** | 3 天 | `RomId`、`RomProfile`、`RomRegistry` 三个纯 Kotlin 文件；把小米专有逻辑搬进 `HyperOsProfile`；**功能零变化** | 单测锁定：Registry 覆盖 5 个 RomId，每个 profile 的 `cameraPkgs` 非空；4.0 的 112 条测试全绿 |
| **P1 指路** | 1 周 | 六家自启动/电池/后台弹窗候选表 + `resolveActivity` 校验链 + 全失败回退；`specialSteps` 文案改第二人称 | 每家至少一条路径能打开（真机）；全失败时回退详情页且日志有归因 |
| **P2 相机包名** | 3 天 | `CameraAppPolicy` 改为「通用表 ∪ profile 表」；补 iQOO/realme/POCO/荣耀子品牌 | 单测：子品牌包名与带 `:子进程` 后缀均命中；非相机包不误判 |
| **P3 图标** | 5 天 | 三层矢量图标 + monochrome + 四形状预览 + 磁贴/通知图标统一 | 各家桌面截图对比；Android 13+ 开主题图标不变形 |
| **P4 验证与发布** | 1 周 | 六家真机 checklist、`docs/rom-support-matrix.md`（标注"已验证/未验证"）、5.0 发布 | 每家 8 项 checklist 至少 6 项通过才标"支持" |

**总计约 4 周**。P0/P2/P3 可并行，P1 依赖 P0。

## 7. 测试矩阵（每家 8 项）

| # | 检查项 | 通过标准 |
| --- | --- | --- |
| 1 | Hook 注入 | 日志出现「成功挂载至 SystemUI」，4 条锚点至少 1 条命中 |
| 2 | 前台服务 | `[FGS]` 日志显示 type=camera（降级也算通过，需记录档位） |
| 3 | 悬浮窗 | 走路时小窗出现；关权限后发通知而非崩溃 |
| 4 | 自启动页 | 引导页第 4 项能打开本 ROM 的自启动设置 |
| 5 | 电池白名单 | 能打开本 ROM 的省电/电池优化页 |
| 6 | 磁贴 | 控制中心可添加，点击能开/关自动跟随 |
| 7 | 无障碍 | 相机 App 前台时小窗收起，退出后恢复 |
| 8 | 图标 | 桌面显示正常；Android 13+ 开启主题图标不变形 |

未通过的项在发布说明里**逐条列出**，不写"完美支持"。

## 8. 风险与边界

| 风险 | 应对 |
| --- | --- |
| 私有页 component 每年漂移 | 表 + `resolveActivity` 校验 + 回退详情页；错的代价是多点两下 |
| 子品牌/版本组合爆炸 | 只覆盖主流六家 + 各自子品牌相机包名；其余按 AOSP 走 |
| 表驱动腐化（没人维护） | P0 单测锁住"每家至少一条路径"，表空了测试就红 |
| 图标遮罩切到主体 | 主体限制在 66dp 内 + 五形状预览 |
| 商标风险 | 计划与文档中**不使用厂商真实 logo**，一律自绘抽象几何 |

## 9. 不做什么（明确划界）

- 不接入超级岛 / 灵动胶囊 / 流体云 / 原子通知的私有 API；
- 不为每家 ROM 复制一套业务代码（那是 3.x 踩过的坑）；
- 不做「任何应用都收起小窗」式的粗暴兼容；
- 不在没有真机的情况下宣称"已支持"。
