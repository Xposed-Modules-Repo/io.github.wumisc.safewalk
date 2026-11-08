# 更新日志

## [未发布] - 2026-11-08 界面统一 + 一批交互/安全问题

### 🎨 界面：所有二级页面统一到「医疗急救卡」版式

原来每个页面各写一套视觉：首页是米黄底 + 圆角磁贴 + emoji 图标框，智能提醒是
每项一张圆角卡，致敬名单是一整块纯文本，实验区是代码里硬编码颜色。改一个颜色
要改四五处。现在统一为「灰底页 + 平角白卡片 + 1px 分隔线 + 24sp 粗标题」，
公共属性抽进 `res/values/styles.xml`（`RowStyle` / `RowTitle` / `RowHint` /
`RowChevron` / `RowDivider` / `CardStyle` / `SectionLabel`）。

- 首页：四个磁贴改成一张卡片里的四行（总开关 / 智能提醒 / 行走模式 / 急救卡），带右侧箭头
- 关于：五个入口 + 诊断日志开关 + 实验区入口，全部行式化
- 智能提醒：六个独立圆角卡合并为一张卡片，XML 从 774 行降到约 420 行
- 行走模式：同样行式化，显示参数/步态参数分组
- 致敬名单：整块文本改成「分区小标题 + 白卡片 + 行」，并见下条

### 🔗 致敬名单：补链接、标注采纳状态

原页面是一个 TextView 拼出来的长文本，链接点不了，也看不出某个项目到底有没有被用上。
现在每一条都带**可点击的仓库地址**与**采纳状态**（直接集成 / 已落地 / 部分落地 /
仅参考 / 仓库已失效），说明里写明对应到哪个机制。

核对结果：原名单里 `mdeguzis/StepCounter`、`ashishb/android-step-detector`、
`tonyo567/hysteresis-state-machine`、`p-v/Android-SensorService` 四个仓库在 GitHub
上**已不存在（404）**，因此不再挂链接并降级为「仅参考」；`ashishb` 的替代地址为
`huuphuoc1396/android-step-detector`。同时补上了实际在用的三个 Maven 依赖
（`Kyant0/AndroidLiquidGlass`、`backdrop-android`、`miuix-*`）。

### 🧪 实验区：日志区放大 + 注入链路自检

- 调试日志区原来写死 300px（约 100dp），1080p 屏上只够看四五行；改成屏幕高度的
  42%（最少 280dp）、字号 11→12sp、可长按选择文本，并新增「清空」按钮
- 新增「注入链路自检」：逐条核对当前版本的初始化链（日志回传 / 锚点挂载 /
  初始化入口 / 核心初始化 / 透明自启页 / 前台检测降级 / 步数探针），
  缺失项直接给出排查方向
- 新增「预览服务状态」：一键看 `isAlive()` / `isPreviewShowing()`
- 按钮列表改成表驱动（分组 + 文案 + 动作），加一项只加一行

### 🐞 底部导航：滑动切页后高亮不同步

`MainNav` 的 `selected` 只是 Compose 内部 `remember` 状态，只有点 tab 才会更新；
左右滑动走 `MainActivity.switchTab()`，于是页面换了、高亮还停在原处。
现在 `MainNav.select(index)` 暴露给 Activity，`switchTab()` 里同步；
顺带修掉初始索引写反（`onPage ? 0 : 1` → 一进来高亮落在「关于」）。

### 🛡 预览「黑过一次就再也不回来」

`RearCameraOverlay` 看门狗与 `recoverCamera` 都是**先 `reportFailure()` 再 `hide()`**，
而回执读的是 `overlay.getShowing()`，于是发出去的是 `showing=true`，紧接着窗口才收起、
却不再有第二次回执。Hook 侧 `lastSentShow` 永久停在 `true`，之后每条「该显示」都被
去重吞掉 —— 默认又是手动常显模式，用户看到的就是「预览突然再也不出来了」。
现在两处都改成**先 hide 再报错**；另外给 `sendOverlayCmd` 加了 60 秒保鲜期，
即使 App 被系统直接杀掉（不走 onDestroy）也能自动重发、让状态自愈。

### 🛡 实验区把「行走模式」永久打开

实验区为了绕过开关检查，会把 `enableRearCamera` 强制写成 `true`，但只在点
「关闭预览」时还原，`onDestroy` 什么都不做，原始值还只是实例字段 ——
用户点过一次预览直接返回，行走模式就常开了，回到桌面还可能自己糊上全屏摄像头画面。
现在备份写进 SharedPreferences，`onStop`（仅 `isFinishing()`）自动还原，
并在 `MainActivity.onCreate` 里兜底恢复上次残留。

### 🛡 手电透明页可被任意应用拉起

`TorchActionActivity` 是 `exported` 的（通知 PendingIntent 必须能跨进程拉起），
但页内**完全没有调用方校验**，且 `enable = action != ACTION_APP_TORCH_OFF` ——
action 为 null 也算「开灯」。任意应用 startActivity 一个空 Intent 就能点亮闪光灯。
现在：action 必须在白名单内（否则直接退出），并校验调用方 uid（API 34+ 的
`launchedFromUid`）或广播令牌。

### 🛡 App 侧跨进程入口只靠硬编码令牌

`CrashAlertReceiver` / `LogBridgeReceiver` / `TorchActionReceiver` /
`WalkPreviewService` 的动态接收器 / `SystemUiRestarter` 的 ACK 接收器，
原来只比对反编译就能拿到的字符串令牌。伪造一条 `CRASH_DETECTED` 足以让急救卡
走完倒计时、真的发短信与拨号。现在统一走 `ReceiverCompat.isTrustedCommand()`：
API 34+ 先用 `getSentFromUid()` 校验发送方必须是系统进程/系统 uid 段/本应用，
令牌退化为第二道校验（API 33 上 `getSentFromUid` 不存在，仍以令牌为主）。

---

## [未发布] - 2026-11-08 构建验证 + Gradle Wrapper

### 🔧 新增：Gradle Wrapper（锁定 9.6.0）

仓库原来没有 wrapper，README 又写「请自备 Gradle 9.8」——但 AGP 9.4.1 的实际
下限是 **9.6.0**，版本写错、且没有 wrapper 就意味着换台机器/换个人克隆下来
大概率卡在 `Minimum supported Gradle version is 9.6.0` 上。

现在补上 `gradlew` / `gradlew.bat` / `gradle/wrapper/`，直接 `./gradlew :app:assembleDebug`
即可，不用先装 Gradle。

### ✅ 验证：全量编译 + 单元测试通过

- `:app:assembleDebug` 成功，产物 45.1 MB，Kotlin 编译**零告警**
- `:app:testDebugUnitTest` 成功，**9 个测试类 / 60 个用例全通过**
- APK 内 `permission.CONTROL` 确认 `protectionLevel=signature`，
  `AutoStartActivity` 的 `exported / taskAffinity / excludeFromRecents` 均如期

### 📄 文档：修正 README 编译章节

原文写「Platform 35 + Gradle 9.8」，与 `compileSdk 37` / AGP 9.4.1 不符。
已改成实测通过的版本表（JDK 21 / Gradle 9.6.0+ / android-37.2 / Build-Tools 36.0.0），
并补了「首次拉依赖约 1.7 GB、十几分钟」与 `services.gradle.org` 不可达时的绕法。

---

## [未发布] - 2026-11-08 稳定性加固（三条 P0 + 两条 P1）

### 🛡 修复：初始化失败一次就永久失效

`inited = true` 原来写在 `try` **之前**，后面任何一步抛异常（`DiagLog.init` /
`getSystemService` / `registerReceiver` 在部分 ROM 上都会），这个 SystemUI 进程
就永久停在"已初始化"的假状态：不重试、不再注册任何监听，模块静默失效直到下次
重启系统界面。

现在拆成 `initRunning` / `initSucceeded` 两个标志，核心步骤失败按 **2s→4s→8s…
封顶 60s、最多 20 次** 退避重试；其余每一步用 `step("名字"){}` 单独兜底，
一处失败只损失那一步，日志能直接看出是哪步挂了。接收器按名字登记，
重试不会重复注册（重复注册 = 一条提醒弹两次）。

### 🛡 修复：主线程跨进程读配置可能拖出 ANR

`contentResolver.query` 是同步 Binder 调用，对面是 App 的 `ConfigProvider`；
App 已被回收时这一次 query 还会把它冷启动起来，耗时几百毫秒到数秒。原来它跑在
主线程的 tick 里，等于每 60 秒让 SystemUI 主线程赌一次"会不会卡住"。

现在读取全部挪到 `SafeWalk-config` 守护线程，`configJson` 改成 `@Volatile` 快照
（后台整体替换、主线程只读），读完回主线程再重算车祸监听与悬浮窗状态。
读失败保留旧快照，10 秒后重试。

### 🛡 修复：硬编码广播令牌 → 第三方可反复杀 SystemUI

反编译 APK 拿到 `safewalk-bc-2.5` 就能发 `RESTART_SYSTEMUI` 反复杀系统界面
（黑屏 DoS）、静默点灯、刷配置。

新增签名级权限 `io.github.wumisc.safewalk.permission.CONTROL`
（`protectionLevel="signature"`），SystemUI 侧 6 个导出接收器统一改为
`registerExportedWithPermission()` 注册，**系统在分发阶段就校验发送方签名**。
令牌保留为第二道校验。反方向（SystemUI → App）仍只能靠令牌，见
`docs/LOGIC_DESIGN.md` §13.1 的边界说明。

### 🪵 修复：日志开关关掉后 SystemUI 侧仍在写

`enableDiagLog` 不在 `ConfigProvider.WHITELIST` 里，Hook 侧
`pref(enableDiagLog, true)` 永远拿默认值 `true`。已加入白名单。

### 🪝 修复：Hook 只有单一入口，ROM 一改类名就整模块静默失效

原来只挂 `SystemUIApplication.onCreate`。OEM 换掉这个类、或改了 SystemUI 的启动
顺序，模块就完全不动，日志里只留一行「找不到 SystemUIApplication 类」——用户看到
的现象是「装了但什么都不动」，且没有任何其他线索。

现在改成**四层入口并联**，任一层命中都能起来：
1. `SystemUIApplication.onCreate`（AOSP 与多数 ROM 的主入口）
2. `Instrumentation.callApplicationOnCreate`（不认类名，任何 Application 都必经）
3. `SystemUIService.onCreate`（SystemUI 自己的 Service，启动极早）
4. 守护线程反射 `ActivityThread.currentApplication()` 轮询兜底（每条锚点都没挂上时
   1 秒后开始，挂上了则先等 8 秒；每 500ms 试一次，最多 20 秒）

四条路都汇到自带幂等保护的 `triggerInit()`，不会重复初始化。第一个命中的来源会写进
日志（`[hook] 初始化触发源: …`），排查时一眼能看出这台 ROM 走的是哪条路。

### 📷 修复：相机前台检测只有一级反射，失败就在相机界面挡住取景

`cameraInForeground` 原来只调 `ActivityTaskManager.getFocusedRootTaskInfo()`，
抛异常被 `catch (_: Throwable) { false }` 吞掉：表现是「在相机界面悬浮窗不收起、
挡住取景」，而日志里干干净净什么都看不到。

现在四级降级，任意一级拿到就用：`getFocusedRootTaskInfo` →
`ActivityTaskManager.getTasks(1)` → `ActivityManager.getRunningTasks(1)` →
`UsageStatsManager.queryEvents`（按最近一次 RESUMED 事件推断）。会记住上次成功的
层级、只在换级时写一行日志；四级全失败时**选择不避让**（宁可显示，也不要因为检测
坏了就永远不显示）并只告警一次。相机包名清单从 9 个扩到 22 个（覆盖华米 OV 荣耀
三星魅族索尼摩托华硕中兴 + 高通/联发科参考相机）。

顺带删掉 `RearCameraOverlay.isCameraAppInForeground()` 那份**零调用方**的重复实现
（相机搬到 `WalkPreviewService` 后它就没人用了），避免两份反射逻辑各自漂移。

改动文件：
- `SafeWalkLSPHook.kt`：`runInit()` / `step()` / `scheduleInitRetry()` / `registerControlReceivers()` / `registerOnce()` / `startTick()`；`refreshConfig()` 改异步；新增 `hookAnchor()` / `triggerInit()` / `startContextFallback()` / `currentApplication()` / `foregroundPackage()` 及 4 个 `pkgFrom*`
- `RearCameraOverlay.kt`：删除零调用方的 `isCameraAppInForeground()` 与 `CAMERA_PACKAGES`
- `ReceiverCompat.kt`：新增 `registerExportedWithPermission()`
- `AndroidManifest.xml`：声明 `permission.CONTROL`
- `ConfigProvider.kt`：白名单加入 `enableDiagLog`
- `docs/LOGIC_DESIGN.md`：§2.1 / §6.2 / §10 / §11.4 / §13 同步更新

## [v2.10.3] - 未发布（步态检测修复）

### 🚶 修复：自动跟随步行「只有手动好用」

**现象**：行走模式里打开「自动跟随步行」后，走起来也不会自动显示路况，
关掉自动（手动常显）却正常。

**根因**：v2.10.1 加回「自动跟随步行」时，复用的是 **SystemUI 进程**里的步数
判定（`SafeWalkLSPHook`）。而 SystemUI 进程里的计步在多数 ROM 上会
「注册成功但永不回调」（缺 `ACTIVITY_RECOGNITION`、或被后台策略掐断传感器
回调），导致 `walking` 永远为 false，自动模式永远不显示。手动模式不依赖
`walking`，所以好用——这与 v2.9.6 亲自认定不可靠、才把步态检测搬进应用进程
的那条路径是同一回事。

**修复**：把自动步态检测迁回**应用进程**的 `WalkPreviewService`（camera 类型
前台服务，传感器回调不会被后台掐断，判定可靠）。该服务监听加速度计 →
`GaitDetector` 判走/停 → 直接控制预览显隐，不再依赖 SystemUI 的步数。
`SafeWalkLSPHook` 只保留「让路」职责（总开关关闭 / 相机应用前台 / 手电占
摄像头时发收起指令），具体显不显示由 App 依据自身 walking 决定。

改动文件：
- `WalkPreviewService.java`：新增应用进程内加速度计监听 + 走/停判定 + 显隐控制
- `SafeWalkLSPHook.kt`：`updateOverlay` 不再依赖本进程 walking，只发「可恢复显示」信号
- `WalkModeActivity.java`：自动开关切换时实时同步给运行中的预览服务

## [v2.10.2] - 2026-11-02

### 🎨 应用图标换新：暗底圆形徽章 · 小女孩打伞

图标换成用户提供的水彩插画「小女孩打伞」，采用暗色背景 + 圆形徽章设计
（圆内为插画、圆外深蓝黑底），并同步替换各密度传统图标与自适应图标前景。

## [v2.10.1] - 2026-11-02

### 🚶 行走模式补回「自动跟随步行」开关，三态可选

行走模式不再只有「手动常显」一种。新增自动跟随开关，三态：

- **关闭**：不显示
- **开启 + 自动关**：路况常显（2.10.0 的行为）
- **开启 + 自动开**：走起来自动显示、停下自动收起

自动模式复用 Hook 提醒用的同一路步态判定（滑动窗口步数），不再另起炉灶；
判不出来时用户随时可切回手动常显兜底。主页磁贴副标题同步显示三态。

### 🎨 应用图标与关于页背景换新

图标（含各密度与自适应前景）与关于页壁纸统一换成「暗色雨夜·小女孩打伞」
插画，图标背景色同步改为深蓝黑 `#1A2036`。

## [v2.10.0] - 2026-11-02

### 🔧 行走模式改为纯手动：开关开 = 路况常显，关 = 收起

彻底放弃「走起来自动开、停下自动收」的步态自动联动。那套逻辑在真机上
反复失效——要么 SystemUI 拿不到计步数据（预览永远不出现），要么波形判不准
（走起来闪一下就没了），用户无法从现象区分「没修好」和「算法不认」。

现在的语义极简，行为可预期：

- **打开行走模式开关** → 后置画面立即显示并**持续显示**，直到手动关闭
- **关闭行走模式开关** → 收起并停掉预览服务
- 手电占用、相机应用在前台时仍会自动让路，之后自动恢复

同时移除的 UI：自动检测步行开关、步态实时状态行、步行判定（步数/静置）滑块、
实时预览三按钮。显示形态（全屏/小窗）、小窗位置、透明度保留。

> 说明：步态检测仍在 SystemUI 进程里运行，但它现在只服务于**提醒功能**
> （证件、社交、夜间手电、久坐），与预览显示彻底解耦。

## [v2.9.6] - 2026-11-02

### 🔧 重构：步态判定搬进应用进程，行走模式不再依赖 SystemUI

这是「走了半天没反应」的真正根因，前几版一直在调阈值，方向就错了。

原来的链路是：

```
加速度计（SystemUI 进程） → 步数 → walking 判定 → 广播 → App 显示预览
```

其中任何一环失效，表现都完全一样：**预览永远不出现，日志里一个字都没有**。
而 SystemUI 里的计步在部分 ROM 上会「注册成功但永不回调」
（没有 `ACTIVITY_RECOGNITION` 时就是这样），等于整条链路的第一环就断了。

现在：

```
加速度计（App 前台服务） → 步数 → walking 判定 → 直接控制自己的悬浮窗
```

应用进程有两个 SystemUI 给不了的条件：

- 它是 **camera 类型的前台服务**，传感器回调不会被后台策略掐断
- 日志直接写进用户看得到的 `diag.log`，每一步都可追溯

加速度计**无条件接入**，不再等系统计步器的 45 秒探测期——
等它确认「没数据」，用户已经走丢半条街了。系统计步器有就并行用，300ms 去重。

Hook 不再根据 `walking` 发显示/收起指令。它只在三种情况下让位：
总开关关闭、相机应用在前台、手电正占着摄像头。

### 📊 行走模式页新增步态实时状态

页面上一行字，每秒刷新：

```
步态：行走中 ✅ 预览应自动显示 · 上次步伐 1s 前 · 传感器帧 1543
步态：静止 · 上次步伐 12s 前 · 传感器帧 9821
步态检测：加速度计还没有数据
步态检测：服务未运行（先打开行走模式开关）
```

这四种状态分别对应四件完全不同的事，以前只能靠猜。

---

## [v2.9.5] - 2026-11-02

### 🐛 修复：手电通知按钮「点了完全没反应」

2.9.4 把按钮投给 SystemUI 里的 Hook，但它仍然是一条**广播**：
夜间应用进程常被 ROM 回收，跨进程广播能不能把进程拉起来全看后台策略，
HyperOS 上大概率直接丢弃。日志里连一行 `[torch]` 都没有，就是最好的证据。

这次把落点从广播换成**透明 Activity**：

- 点击通知是用户操作，`PendingIntent.getActivity` 由系统强制拉起进程，
  不受后台限制影响 —— 这是唯一有保证的「点通知执行动作」的方式
- Activity 在前台时，收起预览（`startService`）与点灯（`setTorchMode`）
  都是合法调用，不会再被后台限制拦掉
- 执行结果用 Toast 直接告诉用户（成功/打不开的原因），不再是静默失败

### 🔬 实验室改为「应用内直连」

实验区的「手电筒开关」不再发广播，直接调 `TorchController`。
这样即使 Hook 没更新、SystemUI 没重启，也能立刻验证这颗灯到底能不能亮：
能亮 → 相机与权限没问题，只是投递方式；不能亮 → Toast 会写明原因。

---

## [v2.9.4] - 2026-11-02

### 🐛 修复：夜间手电通知「点了打不开灯」

手电和实时预览用的是**同一颗后置摄像头**，同一时刻只能有一个使用者。
预览开在应用进程里，通知按钮却投给系统界面里的 Hook——Hook 调
`setTorchMode` 必然拿到 `CAMERA_IN_USE`，打一行「结果: false」就结束，
用户看到的就是「按了没反应」。

2.9.3 曾把按钮改投应用进程，但夜间应用常被系统回收，跨进程广播到不了，
问题依旧。这次把「让路」和「点灯」解耦：

- 按钮投给**一直在跑**的 SystemUI（Hook 宿主），最短路径点灯
- Hook 收到后先广播 `ACTION_HOOK_HIDE` 让应用收起预览，App 侧
  `overlay.hide()` 会 close CameraDevice
- HAL 释放摄像头要几百毫秒，点灯按 300/600/1200/2000ms **重试四次**
- 四次都失败（SystemUI 权限或厂商策略受限）转交应用进程兜底
- 全程写 `[torch]` 日志，成功/失败都有明确一行

### 🐛 修复：手电与预览互相抢占

点亮手电后自动模式立刻把预览拉起来，预览 openCamera 撞上被手电占用的摄像头，
既看不到画面又可能把灯顶掉。现在开灯后 60 秒内、以及 `TorchCallback`
报告手电亮着期间，`updateOverlay` 不再拉起预览，关灯后自动恢复。

---

## [v2.9.3] - 2026-11-02

### 🐛 修复：全屏↔小窗切换后「相机没有返回画面」

日志里能清楚看到时间线：
`20:51:03` 全屏一次点亮（窗口挂载 → 相机打开 → 收到画面，1 秒内完成），
`20:51:08` 切回小窗，窗口挂上了、相机也「打开了」两次，然后就再没有一帧，
`20:51:13` 看门狗报「相机没有返回画面」。

根因不是权限、不是窗口参数，是**相机的生命周期没有串行化**：

- **close 是异步的**：`CameraDevice.close()` 只是发起断开，HAL 释放要几百毫秒。
  原实现 `releaseView() → apply()` 紧接着就重建视图，`onSurfaceTextureAvailable`
  立刻调 `openCamera`，新旧请求直接撞车，`createCaptureSession` 静默卡死
  （`onConfigured` 和 `onConfigureFailed` 一个都不回调）。
- **重复打开导致设备泄漏**：`openCamera` 只用 `camera != null` 挡重复，挡不住
  「回调还没回来」那段异步窗口。日志里两条「正在打开相机/相机已打开」就是证据：
  两个句柄都算打开成功，后一个覆盖字段，前一个再没人 close —— 摄像头从此常亮占用。
- **`hide()` 在 `isShowing == false` 时直接 return**：失败路径里窗口先收起、
  设备还没释放，这条「补救」指令反而什么都不做。

改法：

- 相机代号 `cameraGen`：每次 close 递增，回调带回旧代号一律 `close()` 丢弃
- `opening` 标志挡住并发 open；close 到 open 之间强制 400ms 冷却
- 会话配置 3 秒超时，超时/失败/onError 走统一的 `recoverCamera()`，最多重试 2 次
- 收到画面即清零重试计数；`hide()` 只要还有相机或视图就执行释放
- 看门狗从 5 秒放宽到 6 秒，日志带上 `opening/gen/retry` 现场

### 🔌 修复：手电筒点不亮（与预览同一个根因）

`setTorchMode` 在摄像头被占用时抛 `CAMERA_IN_USE`，而它原来只返回一个 `false`，
Hook 打一行「开启手电结果: false」就结束了 —— 用户看到的就是「按了没反应」。

两个问题叠在一起：

1. 泄漏的 CameraDevice 一直占着摄像头，谁都点不亮（上面的修复已解决）
2. **通道选错了**：通知按钮投给 SystemUI 里的 Hook，但后视预览就开在应用进程里。
   摄像头被自己占着，系统进程那条路必然失败，而且它没有任何办法把预览收起来。

现在按钮投给应用自己的 `TorchActionReceiver` → `TorchController`：

- 开灯前先判断预览是否在跑，在跑就先收起、等 HAL 放开再点灯
- 失败按原因分类（占用/系统禁用/断开/无闪光灯），占用时最多重试 3 次
- 失败会 Toast 说明原因，不再静默
- Hook 那条通道保留作兜底，`TorchHelper` 也改成能返回具体原因

### ✅ 测试

49 项用例全绿。新增/改写 2 条：
- 手电按钮必须投给 `TorchActionReceiver`（锁死「不能再投给 SystemUI」）
- App/Hook 两条手电通道的 action 不能混用

## [v2.9.2] - 2026-11-02

### 🐛 修复：步态检测的根因（2.9.1 没修对）

2.9.1 放宽了步频窗口、延长了采样、加了兜底，方向对但没打到要害。
这次把算法摊开重算，找到了两个**数学上必然失效**的错误：

- **重力滤波系数写反**：`gravity = 0.2*gravity + 0.8*acc` 是「让重力估计跟着信号跑」，
  减完的残差只剩幅值 1%。仿真 1.5Hz 正常步行，线性加速度平均只有 **0.07 m/s²**，
  而阈值下限是 0.6 —— 一个峰都不会出现，怎么调窗口都没用。
  改为 α=0.96 后残差恢复到 1.7 m/s² 量级。
- **阈值公式自相矛盾**：`均值 + 1.8×标准差`，而 |正弦| 的均值本身就是 0.64 倍幅值、
  标准差 0.31 倍幅值，算出 1.2 倍幅值 > 幅值本身，阈值永远高于信号峰值。
  改为「时间衰减包络 × 0.6」，与波形分布无关。

同时：

- **主轴自适应**：手机在口袋/手里/包里朝向随机，固定看某一轴换个姿势就测不到
- **计步双源并行**：加速度计恒定开启，系统计步器能出数就叠加计入，300ms 去重。
  不再等「5 分钟无事件」才兜底——SystemUI 进程拿不到 `ACTIVITY_RECOGNITION` 时
  注册不报错但永远没回调，等 5 分钟用户早走了
- **静置不再清空步数**：原来停 20 秒就把 5 分钟窗口清零，等个红灯、进个电梯前功尽弃
- **验证失败不再死循环**：连续 2 次判不出稳定步态且步数翻倍时，按步数证据触发
- 触发成功/失败都写日志（`✅ 判定为持续步行 → 已发提醒：证件、社交`）

### 🔍 新增两处自查（用户不用 adb）

- 实验区 →「🧭 步态自检（正常走 8 秒）」：用同一套算法在本进程采样，
  一眼区分「算法/传感器有问题」和「Hook 没跑起来」
- 实验区 →「📄 查看 diag.log 末尾」：直接在页面里看最近 80 行日志

### ✅ 测试

新增 3 条正弦波用例（1.5Hz 必须识别 / 3Hz 必须拒绝 / 重力在 Z 轴仍要识别）。
已验证：把系数改回 0.2，新用例立刻失败——旧用例用「单帧阶跃尖峰」写，
在错误系数下也能通过，所以此前一直没发现。48 项用例全绿。

## [v2.9.1] - 2026-11-02

### 🐛 修复：走了半天不提醒

这一版集中处理「步态判定」这条链路上的三处硬伤，它们都不报错、不崩溃，
只表现为**任何提醒都不弹**：

- **步频窗口过窄**：`1.4~2.2Hz` 只覆盖快走，正常散步约 `1.1~1.4Hz`，
  每一步都被判成「不合格」丢掉，`peakIntervals` 永远为空 → 放宽到 **`1.1~2.4Hz`**
- **验证失败就清空步数**：原逻辑「步态验证不通过 → `steps.clear()`」，
  走走停停的人（等红灯、进电梯、拐楼道）要重新攒满 50 步才能再验一次，
  永远凑不齐 → 改为**保留步数 + 8 秒重试冷却**
- **采样窗口太短**：慢走时 3 秒只采到 3 步，扣掉定位用的第一个峰只剩 1 个间隔，
  判不出「稳定步态」→ 延长到 **4 秒**，容差 `±200ms` 放宽到 `±250ms`

### 🔍 计步链路不再静默失败

- 计步器注册结果写日志：没传感器 / 注册被拒 / 抛异常，三种情况分别说明
- **加速度计兜底计步**：系统计步器不可用（或注册成功却 5 分钟零事件）时，
  自动切到加速度计自行计步（低通分离重力 + 上穿阈值 + 280~1500ms 间隔限制）
- 系统计步器恢复出数时自动关闭兜底，避免双份计数
- 新增诊断日志：
  - `🚶 计步中：窗口 12/50 步，状态=LIGHT_MONITOR`（每 15 秒最多一条）
  - `📊 状态=... 窗口步数=... 上一步=... 计步来源=...`（每 2 分钟一条）
  - `🔍 步态验证未通过：峰数=3 步频=[1.18Hz,1.21Hz] 阈值=2.31 姿态=UPRIGHT`
  - `⏸ 总开关已关闭，所有提醒停用（步数仍在采集）`
- `GaitDetector.debugInfo()` 一次说清是「没测到峰」还是「步频不对」还是「间隔不稳」

### 🎨 关于页

- 「保存壁纸」换成《出川》雪山行军图（1024×1822，JPEG q90，591KB）
- 「支持捐赠」改为**支付宝 + 微信两个收款码**，长按任意一张可存到 `DCIM/SafeWalk/`

---

## [v2.9.0] - 2026-11-02

### 🏗 架构：相机彻底搬离 SystemUI

- 新增 `WalkPreviewService`（App 进程相机前台服务），`RearCameraOverlay` 随之迁移
- Hook 端删除全部相机逻辑，只保留「该显示 / 该收起」的指挥广播（带令牌）
- 解决系统界面无相机权限、CameraService 限制、崩溃连带系统界面三个根本问题
- 预览服务回传真实显示状态，修正 Hook 端去重状态与实况脱节导致「手动关一次后再也不自动开」

### ✨ 新增：车祸预警（Pixel 风格）

- `CrashDetector`：合加速度阈值 + 连续 2 次命中 + 60 秒冷却
- 三档灵敏度：低 ≈2.5g / 中 ≈2.0g / 高 ≈1.5g
- `EmergencyAlertActivity`：全屏急救卡 + 60 秒倒计时，超时先发含位置短信再拨号
- 无权限时降级为跳转短信/拨号界面，不静默失败
- 急救卡页面新增开关与灵敏度设置，权限按需申请

### 📝 日志链路重做

- 日志路径改为 `/storage/emulated/0/Android/Travel safely/diag.log`（用户可见）
- 目录判定由不可靠的 `canWrite()` 改为**探针写入**（真写一字节再删）
- 三级降级：用户目录 → 应用私有目录 → 仅 logcat，结果打进 logcat
- 授予「所有文件访问」后**运行期热切换**，不用重启
- App 启动时向 Hook 索要内存日志，写入同一文件（步态/手电/车祸都在那个进程）

### 🐛 关键修复

- **首页与二级页状态矛盾**：总开关卡片无开关控件、点击静默翻转；关闭后磁贴文案被覆盖成「总开关已关闭」，掩盖真实设置 → 改为明确文案 + 切换提示
- **全屏预览黑屏**：`TextureView` 所在悬浮窗缺 `FLAG_HARDWARE_ACCELERATED`，一帧都上不了屏
- 全屏尺寸用 `displayMetrics`（应用可用区）→ 改真实屏幕像素 + `FLAG_LAYOUT_IN_SCREEN`
- 视图 0×0 只重试一次 → 200ms × 3 退避
- 手电两套开关（开/关各自独立）合并为绝对开关；通知按钮 action 统一
- 夜间手电丢失「屏幕亮」与「预览避让相机」两个门槛，导致开了行走模式就永不提醒
- 总开关关闭时仍能拉起预览 → 改为只记录不弹并说明原因
- `exportConfig` 无 try/catch，存储异常直接崩 UI 线程
- 配置回退路径硬编码 `/data/data/<包名>/` → 改用 `ctx.filesDir`
- 实验区手电开关用本地布尔记账 → 改读 `TorchCallback` 真实状态
- 急救卡缺 `onResume` 刷新，改完总开关回来仍显示旧状态
- 行走模式页诊断行、`testRearCam*` 死字段清理

### 🧪 测试与验证

- 单测扩展到 **43 项 / 8 个类**，新增 `PreviewWindowTest`、`CrashDetectorTest`、`DiagLogTest`
- Lint **0 error**
- 实验区夜间手电测试改为真实通知链路（带可点击的「手电筒」按钮），不再是纯文本通知

---

## [v2.2] - 2026-10-30

### ✨ 新增功能
- 新增独立 `torch` 手电筒悬浮通知模块
- 手电通知按钮动态切换「打开手电 / 关闭手电」
- 使用 `CameraManager.TorchCallback` 全局监听闪光灯状态：
  无论是本模块、系统控制中心开启/关闭手电，通知卡片实时同步状态
- 步态检测联动静置省电提醒：手电筒开启且用户长时间静置（默认20分钟）弹出提醒通知
- 后置看路相机避让：打开相机类APP自动收起悬浮窗，切回后冷却90秒自动恢复
- 关于页面改成 2x2 磁贴布局
- 出门踩坑指南独立二级页面（求职/约会/聚会/旅游四场景）
- 新增致敬名单独立页面
- 捐赠二维码换成支付宝

### 🛠 代码优化
- 彻底移除废弃 `XSharedPreferences`，替换为 JSON 文件跨进程读写方案
  - 消除 LSPosed 管理器"已废弃功能"黄色警告
- GaitDetector 新增 `GaitStateCallback` 回调，向外输出静置状态
- RearCameraOverlay 新增相机避让逻辑
- 实验区新增手电悬浮通知测试按钮

### 🐛 Bug 修复
- 修复后置看路设置页 ClassCastException 崩溃
- 修复广播接收器未指定包名导致 Android 14+ 不送达
- 修复电量广播在 Android 14+ 崩溃（加 RECEIVER_NOT_EXPORTED flag）

### 📱 UI 改进
- 关于页面 2x2 磁贴布局：踩坑指南 / 项目地址 / 致敬名单 / 支持捐赠 / 保存壁纸 / 实验区
- 出门踩坑指南独立二级页面，2x2 场景磁贴
- 后置看路设置页改成 Kotlin Compose 版本

---

## [v2.1] - 2026-10-29

### ✨ 新增功能
- 新增后置看路功能：走路时用后置摄像头看前方路况
  - 全屏半透明叠加 / 右下角小窗 两种形态
  - 自动模式：走起来自动开、停下自动收
  - 触摸完全穿透，不挡操作
- 通知升级：超级岛 + 双按钮 + 冷却机制
- 长按调试入口

### 🛠 代码优化
- 从 Java 体系切换到 Kotlin LSPHook 体系
- 步态检测算法优化：自适应阈值 + 姿态判定

---

## [v2.0] - 2026-10-28

### 🎉 首个正式版本
- 步态识别：纯加速度计，自适应峰值检测
- 5大提醒功能：电量预警、证件提醒、久坐提醒、社交礼仪、夜间手电
- 国风 UI：宣纸底 + 靛蓝 + 墨边 + 朱红印章
- 深色模式自动跟随系统
