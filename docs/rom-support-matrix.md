# ROM 支持矩阵

> 本表是**测试台账**，不是宣传页。状态只反映「跑过什么」，不反映「应该能行」。
> 最后更新：2026-11-13（4.8.0：新增 Flyme 一列；P4 真机验证仍未开始）

## 图例

| 符号 | 含义 |
| --- | --- |
| ✅ | 自动验证通过（JVM 单测 / lint / 静态检查） |
| 🟡 | 代码就绪，**未真机验证** |
| ⬜ | 尚未实现（对应阶段未开始） |
| — | 该生态无此项 |

> 「✅」≠「这家 ROM 上一定好用」。它只表示「这一条不需要真机就能确认的部分是对的」。
> 真正的可用性判断必须看 🟡 项的真机结果。

## 九项能力 × 六家厂商 + 原生

| # | 能力 | HyperOS | MagicOS | ColorOS | OriginOS | 华为 | Flyme | AOSP | 依据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Hook 注入（4 锚点 + 兜底） | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 代码静态可确认 4 条路径，命中情况需真机日志 |
| 2 | 前台服务降级链 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | `HyperOsCompatStrategyTest` 6 条：camera 优先、三档共用、不看版本 |
| 3 | 悬浮窗显示前复核 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 策略常量 + `verifyBeforeShow`，真机需验回收场景 |
| 4 | 自启动页 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | — | P1 已接通：候选 → `resolveActivity` → 打开 → 回退详情页；组件名需真机验 |
| 5 | 电池白名单 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 六家共用标准 action（`REQUEST_IGNORE` → `IGNORE_BATTERY_OPTIMIZATION_SETTINGS` → 详情页），无厂商私有页 |
| 6 | 磁贴 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | `WalkTileStatePolicyTest` 6 条通过；真机需验添加与切换 |
| 7 | 无障碍收起小窗 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | `CameraAppPolicyTest` 4 条；包名并集已由 `RomRegistryTest` 验证 |
| 8 | 图标（三层 + 主题图标） | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | P3 已实现：矢量前景 + `monochrome` 层 + 渐变背景；各家的主题图标效果需真机看 |
| 9 | 进程回收后自动恢复 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | `KeepAlivePolicy` 15 条单测 + 30s 心跳 / 90s 超时 / 静态兜底；杀进程→复活未真机跑通 |

> 第 9 行全列 🟡：代码与单测就绪，但「ROM 杀掉 App 进程 → 看门狗 90 秒内拉回来」这条链路
> 一次都没在真机上跑过。心跳显式投递 `com.android.systemui` 与 `com.miui.systemui`，
> 第三种包名会让看门狗进入 3 分钟一次的盲区重试。详见 [docs/keepalive.md](keepalive.md)。

> Flyme 一列是 4.8.0 新加的：自启动首选落在 `com.meizu.safe` 的 `SHOW_APPSEC`，备选 `SmartBGActivity`，
> 两条都只做静态核对，**没在魅族真机上点过**。魅蓝（`mblu`）与魅族共用这一列。

> 华为一列是 4.3.0 新加的：档案里的两条自启动路径分别对应 EMUI 5–11 与 HarmonyOS 2–4，
> 都只做了静态核对，**一条都没在真机上点过**。HarmonyOS NEXT（5.0 起）不兼容 Android
> 应用，本模块装不上，不在矩阵范围内。

> 行 4 从 ⬜ 变 🟡 **不是**「已验证能用」：它只表示代码路径已存在且有单测守着「顺序、兜底、
> 文案不串台」，但 component 名字在真机上是否命中仍未验证。行 5 的 ✅ 同理：验证的是
> 「标准 action 有六家通用性 + 三级回退」，不是「在 ColorOS 上实测加入了白名单」。

## 相机包名覆盖

`RomRegistry.cameraPkgs(id)` = 通用表 ∪ 厂商表。通用表里已有：华为、三星、Google、
美颜相机、Zoom、腾讯会议。

| 生态 | 专属包名 |
| --- | --- |
| HyperOS | `com.miui.camera`、`com.xiaomi.camera` |
| MagicOS | `com.hihonor.camera` |
| ColorOS | `com.oplus.camera`、`com.coloros.camera` |
| OriginOS | `com.vivo.camera` |
| 华为 | `com.huawei.camera`（与通用表同一条，这里显式列出便于核对） |
| AOSP | `com.android.camera2` |

> 并集而非替换：`com.android.camera` 与 `com.android.camera2` 是两个真实包名，
> 替换会导致漏判（见 `TEST-REPORT.md` 3.1）。

## 私有页候选（落地前必须 `resolveActivity` 校验）

| 生态 | 自启动 | 电池 | 后台弹窗 | 紧急呼叫 |
| --- | --- | --- | --- | --- |
| HyperOS | 2 条 component + 1 条 action 兜底 | 标准 action | 应用详情（无独立页） | 1 条 |
| MagicOS | 1 条 component + 1 条 action 兜底 | 标准 action | — | — |
| ColorOS | 2 条 component + 1 条 action 兜底 | 标准 action | 应用详情 | 1 条 |
| OriginOS | 1 条 component + 1 条 action 兜底 | 标准 action | 应用详情 | 1 条 |
| 华为 | 2 条 component（应用启动管理 / 受保护应用）+ 1 条 action 兜底 | 标准 action | 应用详情（无独立页） | — |
| AOSP | — | 标准 action | — | — |

校验命令：

```bash
adb shell cmd package resolve-activity --brief <pkg/cls>
```

## 待办（按阶段）

- **P1**：把表接入 `PermissionOnboardingActivity`；`specialSteps` 的「（HyperOS）」改成第二人称；
  首次访问 `RomRegistry` 包 try/catch，失败降级 AOSP 档案（`ExceptionInInitializerError` 风险见测试报告 3.2）。
- **P2**：`CameraAppPolicy.isCameraLike` 改为查 `RomRegistry.cameraPkgs(detected)`。
- **P3**：`ic_launcher.xml` 加 `<monochrome>`；前景换矢量；通知与磁贴图标统一网格。
- **P4**：六家真机跑完 8 项，把 🟡/⬜ 改成 ✅/❌，❌ 项逐条写明现象。
