# 更新日志

## [4.3.1] - 2026-11-11 修订更新说明（代码与 4.3.0 完全一致）

这版没有功能改动，改的是「给人看的字」。GitHub Release 的说明就是用户眼里的更新说明，
写不准比不写更糟，所以单独发一版把它改对。

| 位置 | 之前 | 现在 |
| --- | --- | --- |
| 4.3.0 说明 · 华为路径 | 「EMUI 5 到 HarmonyOS 4 都是这条」——读起来像已经验过 | 「社区与既有代码显示…，**未真机验证**」 |
| 4.3.0 说明 · 测试数 | 「…共 169 条全绿」——读起来像新增 169 条 | 「本轮新增/改写 5 条；全套 169 条通过」 |
| 4.3.0 说明 · 范围 | 「另外四家用户」 | 「其它厂商的用户」（现在是五家 + 原生） |

两条为什么要较真：

1. **「都是这条」会让人以为华为已经验过**。实际两条自启动 component 只做过静态核对，
   真机上命中哪一条还不知道；写成肯定句，等于把 🟡 说成 ✅。
2. **「169 条」是总数不是增量**。本轮真正新写的是 5 条断言，其余 164 条是既有回归；
   混在一起写，会让后来的人误判测试覆盖的实际增量。

安装包与 4.3.0 功能完全相同（versionCode 45，可直接覆盖安装，无需卸载）。
唯一区别是 `versionName` 与这份说明——留着旧包的用户不必重装。


## [4.3.0] - 2026-11-11 首页改成「五厂商 + 原生」两行，删掉 HyperOS 版本选择

### 一、为什么删「HyperOS 版本适配」

那三个芯片（自动 / HOS3 / HOS4）选的是**版本**，而三个真正影响功能的策略函数
（前台服务类型 / 手电 / 悬浮窗权限）返回的都是与版本无关的常量，`os` 参数一次都没被读过。
让用户为一个不影响行为的选项做决定，只是困惑来源；更糟的是，它让其它厂商的用户看到一张
「HyperOS 版本适配」卡片，既不知道跟自己有没有关系，也不敢点。

现在改成「哪一家 UI」——这件事**确实影响行为**：决定自启动页跳哪儿、引导第 4 步文案怎么写、
用哪张相机包名表。版本识别（`HyperOsCompat.detect()`）保留，但只写日志。

### 二、界面：严格两行

| 行 | 内容 |
| --- | --- |
| 第一行 | 一行注明：「本机 UI：ColorOS · 自动识别」／「本机 UI：HyperOS · 手动指定（仅影响指路）」 |
| 第二行 | 七个芯片：自动 · 小米 · 荣耀 · OPPO · vivo · 华为 · 原生 |

芯片上写**厂商短名**而不是 UI 全名：360dp 宽的机器每个芯片只有约 43dp，
「OriginOS」「HarmonyOS」放不下；UI 全名在上一行注明里给。仍然保留 `autoSizeTextType`，
窄屏再缩字号也不会截成「Orig…」。

### 三、补上华为（EMUI / HarmonyOS 4 及更早）

`RomId.HARMONYOS` + 独立档案：自启动首选 `com.huawei.systemmanager` 的
`StartupNormalAppListActivity`（社区与既有代码显示 EMUI 5～HarmonyOS 4 都是这条，**未真机验证**），备选旧的
`ProtectActivity`，外加应用详情页兜底；相机包名 `com.huawei.camera`。

判定加了 `huawei / harmony / emui` 三个关键字，并**排在荣耀之后**：荣耀老机型的
`MANUFACTURER` 还是 `HUAWEI`、`BRAND` 是 `HONOR`，两个关键字会同时命中，
顺序保证它落在 MagicOS（那正是 MagicUI 的档案）而不是华为。这条有单测锁着。

> HarmonyOS NEXT（5.0 起）不兼容 Android 应用，本模块根本装不上，不在适配范围内。

### 四、文案：别把三星说成「原生」

三星 One UI、摩托 MyOS、索尼都落在 AOSP 兜底，但如果在首页写「本机 UI：原生」，
用户第一眼就知道不对。现在这种情况写「未识别系统 UI · 按原生处理（可手动指定）」，
既说了实话，也暗示可以往下手动指定。

### 五、顺手清掉的技术债

- `MainActivity` 里芯片 key 数组原本在「绑定点击」与「刷新高亮」两处各写一遍，
  漏改一处就变成「点了华为、高亮小米」，且不报错 —— 抽成 `ROM_CHIP_KEYS` 单一来源。
- `WalkPreviewService.fgsOsTag()` 不再读 `hyperOsMode`，固定用自动识别。
- 单测：本轮新增/改写 5 条（华为判定、华为/荣耀不串台、五厂商 + 原生数量、短名与 displayName 一致性），
  全套 169 条通过。


## [4.2.0] - 2026-11-11 国产 UI 适配 P2+P3：图标换矢量加单色层，相机包名补全（顺手抓到一个真 bug）

### 一、图标：从 1024 位图换成矢量三层

旧图标是一张 1024×1024 的 PNG 前景 + 纯色背景。问题不是"不好看"，是**工程上错了**：

| 问题 | 后果 |
| --- | --- |
| 位图前景靠各密度拉伸 | 48dp 下细节糊成一团；APK 里白占 1.17 MB |
| 没有 `<monochrome>` 层 | 系统开「主题图标」时只能把彩色图整体去色，HyperOS 3 / MagicOS / ColorOS 15 / OriginOS 5 上都是一个灰糊块 |
| 没有安全区概念 | 圆形/水滴遮罩会切到伞边 |

现在：矢量前景（渐变伞面 + 顶部高光 + 三道伞骨）+ 渐变背景 + 独立 `monochrome` 剪影。
主体全部收在以画布中心为心、半径 33dp 的安全圆内，四种遮罩都切不到。
`monochrome` 不是把前景去色，而是重画：去掉雨滴与伞骨（小尺寸会变噪点），
伞柄加粗到 2.6dp，保证 48dp 下仍是干净的「一个人打着伞」。

同时删掉 5 个密度的 legacy PNG 与 1.17 MB 的旧前景位图 —— `minSdk 33` 下它们永远不会被读取。
**APK 44.0 MB → 42.3 MB。**

### 二、相机包名：三星那条写错了

`CameraAppPolicy` 里原本是 `com.samsung.android.camera`，而三星实际用的是
`com.sec.android.app.camera`。后果很隐蔽：三星用户打开相机，小窗照样盖在取景框上，
同时自己的预览会话还占着摄像头，对方 CAMERA_IN_USE —— 日志里一切正常，极难排查。

补全（全部是「打开后会独占摄像头」的真·相机）：三星 `sec` 包名、AOSP 第二个真实包名
`com.android.camera2`、一加氧 OS `com.oneplus.camera`、Moto `camera2/camera3`、
索尼、华硕。社交类（微信 / WhatsApp / Telegram）刻意不进表：它们只在视频通话时占相机，
平时收起小窗属于误伤。

### 三、自启动候选：按开源项目核对了一遍

参考 **judemanutd/AutoStarter**（Apache-2.0，跨 OEM 自启动跳转库）的路径表，补齐了三条真实缺口：

| 生态 | 之前缺的 | 为什么重要 |
| --- | --- | --- |
| ColorOS | `...safecenter.permission.startup.StartupAppListActivity` | ColorOS 11 及更早版本的**唯一**入口 |
| ColorOS | `com.oneplus.security` 链式启动页 + `BACKGROUND_OPTIMIZE` | 一加氧 OS / 早期合并版仍用它 |
| OriginOS | `com.iqoo.secure` 白名单页 | iQOO 的自启动管理在自己独立的包里 |
| MagicOS | `com.huawei.systemmanager` 两条 | MagicUI 时代的老荣耀 |

因为跳转是「逐条 `resolveActivity` → 打不开换下一条」，多写一条的代价只是一次失败尝试，
所以这张表允许宁多勿少。致谢页新增「国产 UI 适配」分区，把这几个来源挂上可点链接。

### 验证

- 单测 **144 / 144**（新增 `RomOemCandidatesTest` 6 条 + `CameraAppPolicyTest` 2 条）
- lint **0 error**，322 warning（4.1.0 是 327，减少的 5 条正是 legacy 图标的 `IconLauncherShape`）
- `versionCode 43` / `4.2.0` / `targetSdk 35`（apkanalyzer 实测）
- 自适应图标三层齐全（`aapt2 dump xmltree` 确认 background / foreground / monochrome 都在）

## [4.1.0] - 2026-11-10 国产 UI 适配 P1：不再对荣耀用户说「请开启 HyperOS 自启动」

### 这一版只做一件事：把「指路」从写死小米，改成查表

适配计划里 P1 的验收标准是「每家至少一条自启动页能打开」。本轮把 P0 的数据表真正接进
首次安装的权限引导页，同时把最后一处写死的「（HyperOS）」文案去掉。

| 位置 | 之前 | 现在 |
| --- | --- | --- |
| 第 4 步按钮 | 写死「自启动 / 后台活动（HyperOS）」 | 按 `Build` 判定生成系统名；AOSP 写「本系统无此开关，可跳过」 |
| 自启动跳转 | 只试小米两条 component | 查表 → `resolveActivity` → 打开 → 换下一条 → 全失败退应用详情页 |
| 权限清单 ③ | 「避免 HyperOS 后台杀掉…」 | 「避免系统在后台杀掉…」 |
| 权限清单 ④ 状态 | 永远红字「需手动确认」 | AOSP 显示「本系统无此开关（无需设置）」，不再计入待处理 |
| 首屏副标题 | 无 | 「本机 UI：ColorOS · 自启动在『手机管家 → 自启动管理』」 |

### 三个刻意的取舍

1. **表坏了不能让引导页白屏**。`RomRegistry` 是 Kotlin `object`，表里一条 spec 写错就会在
   首次访问抛 `ExceptionInInitializerError`（`Error` 不是 `Exception`）。所有访问点用
   `Throwable` 兜住并退回通用文案 —— 用户装完 App 打开的第一屏，不能因为一张指路表崩掉。
2. **按钮不显示括号补充**。「开启特殊权限 4/6 · 自启动 / 后台活动（本系统无此开关，可跳过）」
   在单行按钮上会被截成「…（本系统无此开」。清单里保留完整说明，按钮只留短名。
3. **没有真机就不写「已支持」**。`docs/rom-support-matrix.md` 里自启动一栏仍是 🟡，
   含义是「代码路径已通 + 有单测守着顺序与兜底」，不是「在 ColorOS 上实测能开」。

### 验证

- 单测 **136/136**（新增 `RomPageLauncherTest` 10 条：顺序即优先级、全失败返回 null、
  `needsPackageData` 只对三条 per-app action 为真、五家文案不串台、每家自启动候选必带兜底）
- lint **0 error**，无新增 warning
- `versionCode 42` / `4.1.0` / `targetSdk 35`（apkanalyzer 实测）

## [4.0.1] - 2026-11-10 向前兼容补丁：HyperOS 5/6 也不会被识别错

### 一句话回答：不影响 HyperOS 4，也不会因为 HyperOS 5 出现而复发

用户问的是「4.0 这套 HyperOS 3 的适配，会不会把 HyperOS 4 耽误了」。答案是**不会**，
而且原因不在"运气好"，在 4.0 的结构：三个真正影响功能的策略
（前台服务 / 手电 / 悬浮窗）**全部与 ROM 版本无关**，只看运行时结果。
版本识别结论只用来选一句给人看的文案，识别错了也伤不到功能。

但这个补丁修掉了识别层的一个真实短板：4.0 的正则写死 `[34]`，
`ro.mi.os.version.name = HyperOS 5.0` 会**一个版本号都匹配不到**，
只能掉进 `SDK_INT >= 36 → HOS4` 的兜底。结果碰巧对，日志却写成
「未从 prop 提取到 3/4」——排查的人会以为 prop 读坏了。

| 改动 | 之前 | 现在 |
| --- | --- | --- |
| 版本号识别范围 | `[34]` 写死 | `3 ~ 6`（`MAJOR_MAX`），HyperOS 5/6 直接读出 |
| `major >= 4` 的归属 | 只处理 `== 4` | 一律并入 HOS4 分支（策略同构，不新增枚举） |
| 兜底规则 | `SDK >= 36 → HOS4` | 不变，注释写明"及以后"，避免每年改一次 |
| 用户文案 | 「HyperOS 4」 | 「HyperOS 4 及更新版本」 |
| 单测 | 只测策略函数 | 新增 `HyperOsVersionParseTest` 8 条，锁住正则行为 |

顺带修掉两个误读风险（新增单测各锁一例）：`V816`（MIUI 14）里的 `8`、
`V35` 里的 `3` 都不会被当成 HyperOS 大版本。

### 为什么不升 targetSdk

`targetSdk` 仍是 35。这**不是偷懒，而是当前最优解**：

- Android 16（HyperOS 4 基座）的破坏性变更（edge-to-edge 强制、大屏忽略方向锁定、
  JobScheduler 配额、预测性返回默认开启）绝大多数是 **targetSdk 36+ 才生效**；
- 停在 35，这些变更在 HyperOS 4 上**不会**作用到本应用，行为与 HyperOS 3 一致；
- 本应用不走 Google Play，没有"必须升 targetSdk"的时间压力。

什么时候该升：等 HyperOS 5/6 真机普及、且确认 targetSdk 36 的 edge-to-edge 强制
不会打乱沉浸式小窗的 insets 时再升，届时需要重跑一遍导航栏与悬浮窗布局。
详见 `docs/hos4-impact.md`。

## [4.0] - 2026-11-10 稳定版：HyperOS 3/4 适配真正落地 + 浅色导航栏去毛边

### 🔍 先说结论：HyperOS 3 能用，而且比 HyperOS 4 更稳

这个版本把「HyperOS 适配」从**日志里的建议**改成了**运行时真正执行的降级链**。

3.3~3.5 的代码里有一张很漂亮的兼容表（HOS3 用 `specialUse`、HOS4 用 `plain`、
手电 `releasePreviewFirst`、悬浮窗 `strict`），但**它们全部只出现在日志里**：
`WalkPreviewService` 一直硬编码 `FOREGROUND_SERVICE_TYPE_CAMERA`，
`describe()` 输出的那串「前台服务=plain｜手电=releasePreviewFirst｜悬浮窗=strict」
是把建议值当现状展示——等于给用户看了一份没落地的方案。你截图里那行字就是这么来的。

更关键的是：**那张表本身也是错的**。把前台服务类型换成 `plain` 会去掉 camera 语义，
而 Android 11 起 CAMERA 属于 while-in-use 权限，后台就打不开相机——小窗会彻底拍不到
画面。那不是适配，是把功能阉掉。

4.0 的做法：

| 改动 | 说明 |
| --- | --- |
| 前台服务 | `camera` → `specialUse` → 无 type，三档逐级尝试；camera 永远排第一 |
| 判定依据 | **不看 ROM 版本**，只看 `startForeground` 的运行时结果（HOS5 换了限制也不用改代码） |
| 悬浮窗 | 每次显示前真的查 `Settings.canDrawOverlays()`，失权就拦下并发通知 |
| 手电 | 明确为 `inSession`：灯跟小窗同生同灭，不存在「先释放预览再点灯」 |
| 首页文案 | 内部代号换成一句人话，并直接回答「这个版本能不能用」 |

关于「HOS3 到底行不行」，还有一个容易被忽略的事实：本项目 `targetSdk 35`
（= Android 15 = HyperOS 3 的基座），`compileSdk 37`。

- 在 **HyperOS 3** 上，targetSdk 与系统版本一致，走的是最标准的路径，没有兼容性妥协；
- 在 **HyperOS 4**（Android 16）上，targetSdk 低一档，系统会启用兼容行为，
  但厂商对 camera 型前台服务的拦截更积极，所以**降级链主要是为 HOS4 准备的**。

换句话说：HOS3 不是"勉强能用"，它是这次改动里最不需要特殊照顾的那个。

### ✨ 浅色导航栏：这次找到了真正的根因

3.5 只改了两侧描边颜色，深色立刻变好、浅色几乎没变。原因是毛边的来源判断错了：

- 玻璃的 AGSL shader 是**直接栅格化**的，圆角处不做覆盖率采样，
  形状边界会输出一条 1~2dp 的**半透明带**；
- 这条带叠在深色底上几乎看不见（暗 + 暗），叠在浅色底上就是一圈**灰箍**——
  这才是「深色好多了、浅色还有」的全部原因；
- 3.5 的白色描边只能盖住形状**内侧**那半个像素，而灰带在边界上，盖不住；
  浅色底上白描边又与背景同色，等于没画。

4.0 的修法：给玻璃节点加 `graphicsLayer(shape = TabsShape, clip = true)`，
让 **RenderNode 按同一个圆角做硬件裁剪**——outline 裁剪自带抗锯齿，
把那条半透明带连同外溢的模糊采样一起切掉，边界只剩 1px 过渡。

配套：

- 浅色 blur 半径 8dp → 6dp（边缘过渡宽度与模糊半径正相关，6dp 只剩极细一圈）；
- 浅色描边降为白 0.55→0.10（只留一丝边光，不再形成白框）；
- 深色保持原样（白 0.22→0.05，1dp）；
- 代价：按住时玻璃的 +5% 膨胀会被裁掉，视觉上只剩高亮与透镜。已在代码里注明。

### 🧹 其他

- lint 抓到一处会**让降级链在最需要它的时刻断掉**的缺陷：
  声明 `foregroundServiceType="specialUse"` 却没声明
  `FOREGROUND_SERVICE_SPECIAL_USE` 权限，运行时 `startForeground(..., SPECIAL_USE)`
  会直接抛 `SecurityException`。已补。
- 降级到 `specialUse` 时发一次通知说明「后台相机被限制、建议加白名单」，
  同一进程只发一次，避免每次走起来都弹。
- 新增 `HyperOsCompatStrategyTest` 6 条防回归：camera 必须排第一、
  HOS3/HOS4 必须共用同一条链、文案里不许出现内部代号、不许写"完美支持"。

验证：`assembleDebug` BUILD SUCCESSFUL，单测 **104/104**，`lintDebug` **0 error**，
APK `versionCode 40 / versionName 4.0`，清单实测
`foregroundServiceType=0x40000040`（camera|specialUse）+ specialUse 权限已声明。

## [3.5] - 2026-11-10 磁贴改为「自动跟随步行」+ 导航栏圆角收口

### 🔧 磁贴语义修正（3.4 理解错了）

3.4 的磁贴切的是「行走模式总开关」，实际应该是**自动跟随步行**（走起来才显示、停下收起）。

- 点亮：写 `enableRearCamera=true` + `rearCamAuto=true`，走起来才弹小窗；
- 熄灭：两个都写 false。只关 `rearCamAuto` 会留下「总开关开着、档位是常显」，
  下次开机自启就会常显小窗，等于关不干净；
- 磁贴只在「预览开 + 自动跟随开」时亮。App 里只开常显档时磁贴是灰的 ——
  亮着却站着不动也挂小窗，与「自动跟随」矛盾；
- 服务已在跑时只切档位、不闪中转页，并 Toast「走起来才显示」；
  服务没跑才经透明中转页起 camera 前台服务。

### ✨ 液态玻璃导航栏：圆角收口

- 圆角由 `Capsule()`（50% 百分比）改为显式 `RoundedCornerShape(28.dp)`，
  半径落在整像素，圆角起弧不再抖；
- 新增 `cleanEdge()`：在玻璃图层之上描 1dp 同色渐变边，盖住 shader 栅格化的
  台阶与 8dp 模糊混入的透明像素 —— 就是用户说的「毛边」。亮色主题用白 0.85→0.25，
  暗色用白 0.22→0.05，看起来是玻璃的自然边光，不像加了个框；
- 玻璃主体与滑块都收口，滑块略细（0.9dp）；彩色折射层 `alpha=0` 无需处理；
- 没有用 `clip()`：硬裁切同样有单像素锯齿，还会把按压时滑块的外阴影一起裁掉。

验证：`assembleDebug` BUILD SUCCESSFUL，单测 98/98，`lintDebug` 0 error，
APK `versionCode 35 / versionName 3.5`。

## [3.4] - 2026-11-10 新增：控制中心磁贴（下拉即开关行走模式）

### ✨ 一点就开

控制中心/下拉通知栏新增磁贴「路况小窗」，点一下开、再点一下关，不用进应用。

- **开**：经透明中转页 `WalkToggleActivity` 启动预览服务。原因是
  `WalkPreviewService` 是 `camera` 类型前台服务，QS 面板展开时本应用处于后台，
  Android 14+ 直接 `startForegroundService` 会抛
  `ForegroundServiceStartNotAllowedException`；官方稳定豁免是「启动瞬间有可见
  Activity」，中转页存在的 450ms 就是这个窗口，服务提起前台后自己退出，用户无感。
- **关**：写配置 + 发 STOP，面板不收起，磁贴当场变灰。

### 🎛 状态与文案

- 总开关关闭时磁贴置灰（`STATE_UNAVAILABLE`）：此时点开行走模式也不会生效，
  不能做成「看着能点、点了没反应」。
- 副标题说明下一步：`点击开启身后路况` / `身后路况已开启` /
  `已开启 · 预览未运行，点一下关闭`，避免「点了不知道发生了什么」。
- 与 App 内开关双向同步：App 改开关、预览服务启停都会
  `requestListeningState` 主动刷新磁贴，不必等用户下次拉开面板。
- 支持长按磁贴直达「行走模式」页（`QS_TILE_PREFERENCES`）。

### ➕ 添加路径

行走模式页新增「快捷入口 → 添加到控制中心」，走 Android 13 的
`StatusBarManager.requestAddTileService()`，由系统弹「要添加吗」，
不用用户自己进「编辑控制中心」翻列表。失败/系统不支持时给手动添加的说明。

验证：`assembleDebug` BUILD SUCCESSFUL，单测 97/97（新增 `WalkTileStatePolicyTest` 5 条），
`lintDebug` 0 error，APK `versionCode 34 / versionName 3.4`。

## [3.3] - 2026-11-10 修复：无障碍没有入口 / 快捷方式不生效 / 后台收不到定位

### 🐞 系统「无障碍」里找不到本应用（用户反馈）

**现象**：权限引导页把「无障碍」列成一项，点进去只能到系统无障碍总列表，里面根本没有
「安全出行」，也就没有从那里打开应用的入口。

**根因**：应用从未声明任何 `AccessibilityService`，系统列表自然没有条目；而引导页的
状态读的是 `ACCESSIBILITY_ENABLED` **总开关**——只要系统里开着任意一个无障碍服务
（输入法、手势、屏幕朗读）就显示「已开启」，是纯粹的状态误报。

**修复**：
- 新增 `SafeWalkA11yService`（只订阅 `TYPE_WINDOW_STATE_CHANGED`，`canRetrieveWindowContent=false`，
  不读屏、不联网），清单注册并带 `settingsActivity` → 系统列表里点「设置」即可打开本应用；
- 作用：前台是相机 / 录像 / 视频通话类应用时自动收起角落小窗（不依赖 Xposed 的备用通道），
  名单与匹配规则抽成 `CameraAppPolicy` 并加单测；
- 引导页「无障碍」改为**直达本服务详情页**（`android.settings.ACCESSIBILITY_DETAILS_SETTINGS`），
  状态按 `ENABLED_ACCESSIBILITY_SERVICES` 里有没有本组件精确判断。

### 🐞 长按图标没有快捷方式

`res/xml/shortcuts.xml` 里两个快捷方式（切换总开关 / 打开急救卡）早就写好了，但清单里
缺 `android.app.shortcuts` 这条 meta-data，系统根本不会读它 —— 资源等于凭空存在。

### 🐞 到达提醒在后台从不触发

`PlaceArrivalMonitor` 只申请了前台定位。Android 10 起应用进后台就停回调，而「到达某地」
几乎总发生在屏幕黑着、手机装兜里的时候 → 到达提醒只在打开 App 的几秒内有效。
现声明 `ACCESS_BACKGROUND_LOCATION`，引导页新增「后台定位」一项（位置选「始终允许」），
未授予时日志明确提示「只在打开 App 时生效」。

### 🧹 其他修复

- `AutoStartActivity` 在 Android 13 上读 `launchedFromUid`（API 34 才有）→ 异常被吞后 uid=-1，
  日志误报「被不可信 uid 拉起」；改为按版本取值并在日志里说明。
- 清单补 `PACKAGE_USAGE_STATS`（Hook 前台检测第四级兜底用，未授予时原本每次抛 SecurityException）。
- 引导页「无障碍」状态不再把「别的无障碍服务开着」误报成本应用已开启。
- 「关于」页新增常驻入口「权限与无障碍」：引导页只弹一次，此前用户想补授权再也找不到地方。
- 消除 5 条 lint error（MissingPermission / NewApi / AppCompatCustomView），`lintDebug` 通过。

验证：`assembleDebug` BUILD SUCCESSFUL，单测 92/92（新增 `CameraAppPolicyTest` 4 条），
`lintDebug` 0 error，APK `versionCode 33 / versionName 3.3`。

## [3.2] - 2026-11-10 修复：自动跟随全程不触发（纯加速度门槛不可达）

### 🐞 「连续走 N 步」永远等不到

**现象**：无硬件计步器的机型（本机 Redmi / HyperOS 4），正常走路 14 分钟，
角落小窗一次都不弹；日志里反复是「纯加速度需累计 7/15 步」「累计=10/7 达标但被拦截」。

**根因**：上一轮防沙发误触把门槛定成 `max(确认步数×2, 15)`，而
`GaitDetector.confirmedStepCount()` 是滑动窗口大小，队列上限写死 10
（`peakIntervals.size > 10` 就 `removeAt(0)`）。`≥15` 是**永不成立的死条件**，
在该机型上等价于关闭自动跟随。

**修复**：
- 新增 `WalkConfirmPolicy`：门槛 `clamp(用户步数×2, 8, 10)`，并单元测试锁死
  「门槛不得超过采集窗口上限」，避免同类死条件再次出现。
- 判据从「累计多少步」改为**步流的时间形态**：连续 8~10 步、相邻步最大间隔 ≤1.6s、
  平均步间隔 350~1400ms、最近 10 步跨度 ≥4s、间隔离散度受控；`LAY_FLAT` 仍一票否决。
- `WalkPreviewService` 用 `accelStepTs` 记录每个合格步时间戳（`stepTick` 或窗口计数增长时入队，
  断步 3s 清空），不再依赖会「满格后不再增长」的窗口计数。

### 🧹 日志洪泛与误导文案

- `accelOnlyConfirm()` 在窗口满格后每帧都被调用、每条分支都写日志，真机刷出
  **968 行/9 秒（约 107 行/秒）**，全是主线程文件 IO。改为原因变化才打（≥2s），
  原因不变 10 秒一条；`shouldShowNow()` 加 5 秒节流。
- `[STEP] ... 本帧合格步=false` 容易误读成「一步都没走成」（实际只占一帧），
  改为 `最近10s出步=N`，并把「累计合格步」正名为「连续合格步」。
- 新增一次性诊断：`[gait] 本机走纯加速度判定 | 计步传感器=有/无 已出步=0`，
  区分「没有计步器」和「没给身体活动权限」。
- Hook 侧 `[torch:time]` 的「步态联动不会点灯」改为「小窗联动不会点灯」（文案早已过期）。

验证：`assembleDebug` BUILD SUCCESSFUL，单测 88/88（新增 `WalkConfirmPolicyTest` 10 条），
APK `versionCode 32 / versionName 3.2`。

## [Unreleased] 修复：后视画面「相机被系统或策略禁用」误报

### 🐞 后视预览报错原因不准确

**现象**：当相机被系统/设备策略禁用（企业策略、屏幕使用时间、家长控制等）时，
`WalkPreviewService` 弹出「SafeWalk 后视画面打不开」，原因却写成
**「相机没有返回画面」**，与「已授予相机权限、也没被别的应用占用」的真实情况对不上，
用户照着提示排查却怎么都修不好。

**根因**：`RearCameraOverlay.openCamera()` 把 `CameraManager.openCamera` 整个包在
`catch (t: Throwable)` 里。而相机被禁用时框架会抛 `CameraAccessException`
（`CAMERA_DISABLED`，或经 `StateCallback.onError` 的 `ERROR_CAMERA_DISABLED`），
这条路径既没被识别，又进了 `recoverCamera` 空转 `MAX_CAMERA_RETRY` 轮重试——
重试对「策略禁用」永远无效，最后才兜出误导性的「没有返回画面」。

**修复**：
- 新增 `catch (e: CameraAccessException)`：识别 `CAMERA_DISABLED` 后**直接报
  「相机被系统或策略禁用」并停止重试**（策略禁用重试无意义）；其余仍可重试的错误
  （被占用等）维持原有限次重试。
- `StateCallback.onError` 中识别 `ERROR_CAMERA_DISABLED`，同样直接报准确原因、不重试。
- 报错前先 `hide()` 再 `reportFailure()`，沿用既有「先收窗再回执」顺序，避免
  Hook 侧 `showing` 状态被错误锁死。

## [3.0] - 2026-11-08 正式版：统一版式 + 深色模式重建

> 版本号从 2.10.x 跨到 3.0。原因不是功能大改，而是界面层做了一次整体收口：
> 之前三套配色并存、页面各写各的版式，深色模式下大面积失效。这一版把视觉
> 收敛成一套 token + 一套版式，才算能对外叫「正式版」。

### 🌗 深色模式重建（本次核心）

问题不是「某个颜色写错了」，而是**同时存在三套配色**：

| 层 | 原来 | 结果 |
|---|---|---|
| 首页 / 关于 / 急救卡 / 智能提醒 / 行走模式 | 布局里硬编码 `#F2F2F7` `#FFFFFF` `#1D1D1D` | 系统切深色后**完全不变**，白底刺眼 |
| 出门踩坑指南 / 捐赠 | 走旧「宣纸」色板 `@color/bg_end` 等 | 深色下背景与卡片同色，**卡片消失** |
| `values-night/colors.xml` | 另一套 Miuix 命名（`accent_walk`、`button_bg`…） | 只有部分页面引用，页面之间对不上 |
| 底部导航栏（Compose） | 图标/文字硬编码 `Color.Black` | 压在深色玻璃上**等于隐形** |

现在：

- **一套语义 token**（`bg` / `card_bg` / `text_primary` / `text_hint` / `divider` /
  `chevron` / `primary` …），`values` 与 `values-night` 一一对应，只换值不换名
- 所有布局、drawable、代码（含 `LabActivity`、`AcknowledgementsActivity`）
  改为一律读资源，**没有一处硬编码颜色**（急救提醒页除外，见下）
- 主题 `parent` 从 `Theme.MaterialComponents.Light` 改为 **`DayNight`**：
  开关、滑块、默认文字色这些控件级配色跟着系统走
- 关闭系统「强制深色」（`forceDarkAllowed=false`），避免手工适配过的界面被二次反相
- 状态栏 / 导航栏图标深浅走 `@bool/light_statusbar`，深色下自动转浅色图标
- 底部导航图标与文字改为 `isSystemInDarkTheme()` 分支（深色用白、浅色用黑）

急救提醒（`activity_emergency_alert`）是整屏红底告警页，保持固定红白配色不随主题变化 ——
它的第一要求是「在任何情况下都刺眼、都能读清」，不是融入系统主题。

### 🧪 功能实验区：按钮全部消失（P0 回归）

上一版把按钮改成了表驱动，`btn()` 复用了「宽 0dp + weight 1」的参数 ——
那是**横向排布**的写法，挂在纵向 `LinearLayout` 里宽度直接变 0，于是
整页按钮全部不可见，只剩状态卡和三行分组标题，看起来像「实验区彻底废了」。

现在整个页面重建为行式列表：状态卡 + 三个按钮分组（功能测试 / 行走模式预览 / 诊断），
每行标题 + 行尾箭头，与首页同一套视觉；颜色全部取自 token，深色下同样成立。

### 🚶 出门踩坑指南：深色下整页变黑

原来是「宣纸渐变背景 + 2x2 圆角磁贴」。深色下背景变 `#000000`、卡片变 `#1C1C1E`，
两者同色，只剩四个 emoji 浮在空黑页上。现在改为行式入口（求职 / 约会 / 聚会 / 旅游），
点击后在下方卡片展开清单并自动滚动到内容区；补了「‹ 返回」。

### 🎨 二级页面统一

全部对齐首页版式（灰底页 + 平角白卡 + 1px 分隔线 + 24sp 粗标题）：

- 新增 `PageScroll` / `PageTitle` / `PageSubtitle` / `BackLink` / `BodyText` /
  `PrimaryButton` / `SecondaryButton` / `SwitchRow` 等公共样式
- 致敬名单、急救卡、捐赠页补「‹ 返回」，与其他二级页一致
- 捐赠页标题 26sp → 24sp，二维码改为水平居中
- 致敬名单描述里残留的 markdown 星号（`**未落地**`）清掉

### 🧹 清理

删除长期没人引用的历史资源与死代码：

- `component/liquid/`（5 个 kt，早期液态玻璃导航栏原型，已被 `LiquidBottomTabs` 取代）
- 19 个零引用 drawable（`bg_xuanzhi` / `icon_frame_*` / `tile_bg_*` / `bg_hero_*` …）
- 旧色板里已被替换掉的颜色名

### 验证

- `:app:assembleDebug` 通过
- 单元测试 **62 个用例全通过**
- 用 Robolectric 原生渲染把 7 个二级页在**浅色/深色下各截一张图**逐张核对
  （实验区、踩坑指南、致敬名单、急救卡、智能提醒、行走模式、捐赠）

---

## [2.10.3] - 2026-11-08 界面统一 + 一批交互/安全问题（未单独发版，并入 3.0）

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
