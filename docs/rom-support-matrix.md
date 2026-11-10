# ROM 支持矩阵

> 本表是**测试台账**，不是宣传页。状态只反映「跑过什么」，不反映「应该能行」。
> 最后更新：2026-11-10（P0 完成，P1–P4 未开始）

## 图例

| 符号 | 含义 |
| --- | --- |
| ✅ | 自动验证通过（JVM 单测 / lint / 静态检查） |
| 🟡 | 代码就绪，**未真机验证** |
| ⬜ | 尚未实现（对应阶段未开始） |
| — | 该生态无此项 |

> 「✅」≠「这家 ROM 上一定好用」。它只表示「这一条不需要真机就能确认的部分是对的」。
> 真正的可用性判断必须看 🟡 项的真机结果。

## 八项能力 × 五家生态

| # | 能力 | HyperOS | MagicOS | ColorOS | OriginOS | AOSP | 依据 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Hook 注入（4 锚点 + 兜底） | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 代码静态可确认 4 条路径，命中情况需真机日志 |
| 2 | 前台服务降级链 | ✅ | ✅ | ✅ | ✅ | ✅ | `HyperOsCompatStrategyTest` 6 条：camera 优先、三档共用、不看版本 |
| 3 | 悬浮窗显示前复核 | ✅ | ✅ | ✅ | ✅ | ✅ | 策略常量 + `verifyBeforeShow`，真机需验回收场景 |
| 4 | 自启动页 | ⬜ | ⬜ | ⬜ | ⬜ | — | 表已就绪（P0），跳转未接（P1） |
| 5 | 电池白名单 | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 表已就绪，跳转未接（P1） |
| 6 | 磁贴 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | `WalkTileStatePolicyTest` 6 条通过；真机需验添加与切换 |
| 7 | 无障碍收起小窗 | ✅ | ✅ | ✅ | ✅ | ✅ | `CameraAppPolicyTest` 4 条；包名并集已由 `RomRegistryTest` 验证 |
| 8 | 图标（三层 + 主题图标） | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 缺 `monochrome` 层与矢量前景（P3） |

## 相机包名覆盖

`RomRegistry.cameraPkgs(id)` = 通用表 ∪ 厂商表。通用表里已有：华为、三星、Google、
美颜相机、Zoom、腾讯会议。

| 生态 | 专属包名 |
| --- | --- |
| HyperOS | `com.miui.camera`、`com.xiaomi.camera` |
| MagicOS | `com.hihonor.camera` |
| ColorOS | `com.oplus.camera`、`com.coloros.camera` |
| OriginOS | `com.vivo.camera` |
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
- **P4**：五家真机跑完 8 项，把 🟡/⬜ 改成 ✅/❌，❌ 项逐条写明现象。
