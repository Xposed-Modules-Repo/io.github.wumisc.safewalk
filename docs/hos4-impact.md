# 4.0 的 HyperOS 3 适配会不会耽误 HyperOS 4？

> 结论：不会。原因不是"试过没事"，而是 4.0 把**功能策略**和**版本识别**拆开了：
> 三个真正影响功能的策略与 ROM 版本无关，只看运行时结果。版本号只用来选一句文案。
> 4.0.1 在此基础上补掉了"HyperOS 5/6 识别不出来"的短板。

## 1. 先看证据：功能路径上没有任何 `if (版本 == 3)`

`HyperOsCompat` 里三个对外策略函数长这样（`app/src/main/java/.../HyperOsCompat.kt`）：

| 函数 | 4.0 的返回 | 是否读 `os` 参数 |
| --- | --- | --- |
| `foregroundServiceStrategy(os)` | `"camera→specialUse→none"` | **否**，直接返回常量 |
| `torchOpenStrategy(os)` | `"inSession"` | **否** |
| `overlayPermissionStrategy(os)` | `"verifyBeforeShow"` | **否** |

参数留着只是为了兼容 3.x 的调用点，函数体里一次都没读它。真正的决策发生在
`WalkPreviewService.goForeground()`：拿 `FGS_TYPE_CHAIN` 逐档 `startForeground()`，
哪一档不抛异常就用哪一档。这条链的行为由**系统当时的反应**决定，不由 ROM 版本决定。

所以「4.0 是为 HyperOS 3 写的，会不会在 HyperOS 4 上水土不服」这个担心的前提不成立：
它不是为某个版本写的，是为"系统可能拒绝"写的。

版本识别的结论（`Os.HOS3 / HOS4 / OTHER`）的消费方只有四处，全是文案：

- `describe()` —— 首页那行摘要
- `supportNote()` —— "这个版本能不能用"的一句话
- `backgroundKeepAliveHint()` —— 保活四步的中文提示
- `rootCmdStrategy()` —— root 命令的注意事项

**识别错了，最坏结果是首页写错一行字，功能一行代码都不会变。**

## 2. Android 16（HyperOS 4 基座）× SafeWalk 影响矩阵

| SafeWalk 能力 | 依赖的系统能力 | Android 16 的变更 | 对本应用的影响 |
| --- | --- | --- | --- |
| 后台相机预览 | `FOREGROUND_SERVICE_TYPE_CAMERA` | 无新增限制（禁止 `BOOT_COMPLETED` 启动 camera FGS 是 **Android 15** 就有的） | 无。启动路径全部经过可见 Activity（含磁贴的透明中转页），本来就绕开了该限制 |
| 悬浮窗 | `SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY` | 无变更 | 无 |
| 无障碍（识别相机 App 后收起小窗） | `AccessibilityService` + `TYPE_WINDOW_STATE_CHANGED` | 无破坏性变更 | 无 |
| 控制中心磁贴 | `TileService` | 无变更 | 无 |
| 常驻通知 | `NotificationChannel` | 新增 `ProgressStyle` / Live Update，旧 API 全部保留 | 无 |
| 后台定位 | `ACCESS_BACKGROUND_LOCATION` | 无变更 | 无 |
| 布局 / 导航栏 | WindowInsets、`graphicsLayer` 裁剪 | **targetSdk 36 起**强制 edge-to-edge，`windowOptOutEdgeToEdgeEnforcement` 失效 | 当前 targetSdk 35，**不生效**；升 36 时需重测 |
| 屏幕方向 / 分屏 | `screenOrientation` | **targetSdk 36 起**在 `sw >= 600dp` 上被忽略 | 当前不生效；本应用主要在手机竖屏用，风险低 |
| 定时 / 轮询 | 无 `JobScheduler` 依赖（用前台服务 + Handler） | JobScheduler 配额收紧 | 无影响（压根没用） |
| 返回手势 | Activity 返回栈 | **targetSdk 36 起**预测性返回默认开启 | 当前不生效；中转页是 `noHistory`，无返回栈可破坏 |

一句话：**矩阵里唯一标"需注意"的两行，都挂在 `targetSdk 36` 上，而我们停在 35。**

## 3. targetSdk 35 是刻意的，不是忘了升

`targetSdk 35` = Android 15 = HyperOS 3 的基座。这在 HyperOS 4（Android 16）上意味着：

- 系统判定本应用为"旧目标版本"，**启用兼容行为**；
- 上表里所有标 `targetSdk 36+` 的破坏性变更**不会应用**到本应用；
- 本应用不走 Google Play，没有"必须升 targetSdk 才能上架"的时间压力。

`compileSdk` 已经是 37（miuix-blur 0.9.4 的硬性要求），所以**编译期能看到最新 API，
运行期仍按 35 的规则走**——这是最稳的组合：既能用新库，又不吃新限制。

## 4. HyperOS 4 上真正会变的事情（都已处理）

厂商层的收紧不在 AOSP 变更清单里，这才是 HyperOS 3 → 4 的实际差异来源：

| 现象 | 4.0 的应对 | 用户可见表现 |
| --- | --- | --- |
| camera 型前台服务被拒（`ForegroundServiceStartNotAllowedException`） | 降级到 `specialUse`，服务与悬浮窗保住 | 小窗在应用可见时有画面，切后台黑屏；发一条一次性通知说明原因 |
| 悬浮窗权限被系统回收 | 每次 `doShow()` 前查 `Settings.canDrawOverlays()` | 不再抛 `BadTokenException` 后静默失败，而是发通知引导重新授权 |
| 后台进程被更激进地回收 | 前台服务 + `START_STICKY` + 常驻通知 | 被杀后自动重建；配合保活四步 |
| 自启动 / 电池白名单被重置 | 首页常驻"权限与无障碍"入口 + 保活提示 | 升级系统后用户能自己复查，不用等出问题 |

## 5. 4.0.1 补的是什么

4.0 的正则写死 `[34]`，`HyperOS 5.0` 会匹配不到任何版本号，掉进
`SDK_INT >= 36 → HOS4` 的兜底。结果碰巧对，但日志会写「未从 prop 提取到 3/4」，
排查时误导人。

| 项 | 4.0 | 4.0.1 |
| --- | --- | --- |
| 识别范围 | `[34]` | `3 ~ 6`（`MAJOR_MAX`） |
| `major >= 4` | 只处理 `== 4` | 统一并入 HOS4 分支 |
| 文案 | 「HyperOS 4」 | 「HyperOS 4 及更新版本」 |
| 误读防护 | 有 `(?![0-9])` | 追加单测锁死 `V816` / `V35` / `V9` / `V12` 不被误读 |
| 单测 | 6 条（只测策略） | +8 条（`HyperOsVersionParseTest`），累计 112/112 |

不新增枚举值（仍是 `HOS3 / HOS4 / OTHER`）：HyperOS 5/6 与 4 的策略完全同构，
多一个枚举只会多一条没人跑的测试分支。

## 6. 什么时候真的需要改代码

给自己留一张触发条件清单，满足任一条再动手：

1. **升 targetSdk 到 36**：必须重测导航栏 insets（edge-to-edge 强制）、悬浮窗定位、
   预测性返回对中转页的影响。这一步不要顺手做，要单独一轮。
2. **`FOREGROUND_SERVICE_TYPE_CAMERA` 在所有档位都被拒**：说明 Android 层面限制了
   后台相机，届时需要考虑 `MediaProjection` 或纯前台可见的方案。
3. **`SYSTEM_ALERT_WINDOW` 被改成需要每次确认**：悬浮窗方案整体要换（例如画中画）。
4. **HyperOS 把 `ro.mi.os.version.name` 改格式**：只需调整正则，`majorFrom()` 的单测
   会立刻指出是哪一类失效。功能不受影响。

## 7. 未验证的部分（诚实声明）

- 没有 HyperOS 4 真机，本文档的结论来自**代码路径 + 清单 + 单测**，不是实机跑出来的。
- Android 16 变更项依据 AOSP 行为变更文档；小米在 HyperOS 4 上是否额外收紧
  camera FGS，只能等真机日志。真遇到时日志里会写清是哪一档被拒（`[FGS]` 前缀），
  这正是 4.0 把决策放在运行时的原因。
- 因此 Release 说明里不写「完美支持 HyperOS 4」，只写「会自动降级」。
