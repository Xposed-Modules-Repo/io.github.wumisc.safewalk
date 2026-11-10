# 安全出行 SafeWalk

> 海阔天空，勇敢去走。

纯本地、无服务器的**出行感知** LSPosed 模块。不依赖 GMS，不联网，不上传任何数据；
用手机自带加速度计判断「你是不是真的在走路」，再决定要不要提醒你。

- 运行环境：**HyperOS / Android 13+**（minSdk 33，targetSdk 35）
- 作用域：**只需勾选「系统界面（SystemUI）」**，不需要系统框架
- 开源协议：**AGPL-3.0**（见 [LICENSE](LICENSE)）
- 当前版本：**3.0**（2026-11-08）· [下载 APK](https://github.com/Xposed-Modules-Repo/io.github.wumisc.safewalk/releases/latest) · [更新日志](CHANGELOG.md)

---

## ✨ 功能一览

| 功能 | 触发 | 说明 |
|---|---|---|
| 🔋 电量预警 | 电量 ≤15% 且步行中 | 提醒充电，1h 冷却 |
| 🪪 证件提醒 | 确认持续步行 | 证件贴身放好，每日 1 次 |
| 🪑 久坐提醒 | 停止走动满 2h | 起身活动，每日 1 次 |
| 🔦 夜间提醒 | 22:00~06:00 步行中 | 通知带「手电筒」按钮，一键开灯 |
| 🤝 社交礼仪 | 确认持续步行 | 按求职/约会/聚会/旅游给 3 条提示 |
| 🚶 行走模式 | 开始步行 | 后置摄像头把身后路况叠在屏上 |
| 🚑 车祸预警 | 检测到剧烈撞击 | 全屏急救卡 + 60s 倒计时，无响应自动求助 |

> 所有功能共用**同一个总开关**。关闭时二级菜单里的开关保留设置但不生效。

---

## 🎨 界面

3.0 把视觉层收敛成**一套语义 token + 一套版式**，深色模式不再是补丁：

- `colors.xml` 只保留语义命名（`bg` / `card_bg` / `text_primary` / `text_body` / `text_hint` / `divider` / `chevron` / `primary` …），`values-night` 逐项覆盖，**只换值不换名**
- 布局与代码一律读资源，没有一处硬编码色值，切换到深色不会再出现「白底刺眼」或「卡片与背景同色」的情况
- 全部二级页面统一为「卡片 + 行列表」版式：左侧标题、右侧说明与 chevron，分隔线内缩对齐
- 底部导航为 Compose 液态玻璃，选中态跟随左右滑动实时联动，深色下自动切换前景色
- 窗口统一 `forceDarkAllowed=false`，避免系统二次压暗把自定义配色弄脏

---

## ⚙️ 核心机制

**步态检测**（跑在应用进程 `WalkPreviewService`，不在 SystemUI）：靠五重门槛层层收敛——
幅值上限挡大瞬态、步频窗口 0.5~2.5Hz、主轴稳定一致率 ≥70%、步数门槛 N、断步重置。
自适应阈值：最近 60 样本 `均值+1.8×标准差`，钳制 0.6~2.8 m/s²。

**行走模式**：SystemUI 只做「指挥」（传感器/步态/显隐广播），App 前台服务负责开相机渲染。
两种形态：全屏叠加（穿透）/ 角落小窗（150×260dp），形态/位置/透明度实时可调。
**重启不用重新拨开关**：服务被杀或设备重启后，亮屏解锁时 Hook 会经一个透明中转页把预览重新拉起
（绕开 Android 14+ 对 camera 类型前台服务的后台启动限制），无需手动进入 App 操作。

**手电筒**：夜间提醒通知直接开灯（`setTorchMode`，无需相机权限）；`TorchCallback` 全局状态同步；
静置超时提醒关灯省电；夜间暗光（<15lx）可自动开灯。

**车祸预警**：合加速度超阈值且**连续 2 次命中**判定撞击，60s 冷却防误报；三档灵敏度（1.5/2.0/2.5g）；
全屏急救卡 + 60s 倒计时，超时先发含位置短信、再拨紧急联系人，无权限时降级跳转不静默失败。

**通知**：统一高优先级 `IMPORTANCE_HIGH` heads-up，可被 HyperOS 超级岛拦截；各通道独立冷却。

---

## 🔄 HyperOS 版本兼容

**不靠版本号做功能判断。** 三个真正影响功能的策略（前台服务类型、手电、悬浮窗权限）
返回的都是与 ROM 无关的常量，由运行时结果决定走哪一档：

```
camera（首选）→ specialUse（被拒时保住服务与悬浮窗）→ 无 type
```

- 系统放行 → 用首选路径，根本不进降级分支；
- 系统拒绝 → 自动降级，功能不完全丢失，日志里写清是哪一档被拒（`[FGS]` 前缀）；
- 新版本改了限制 → 不需要改代码。

版本号识别（`HyperOsCompat.detect()`）只用来选一句给人看的文案，**识别错了不会影响功能**。
识别范围是 HyperOS 3~6，`>= 4` 统一归入「比 3 更严」的分支。

`targetSdk` 停在 **35**（= Android 15 = HyperOS 3 基座）：Android 16 的破坏性变更
（edge-to-edge 强制、大屏忽略方向、预测性返回默认开启、JobScheduler 配额）绝大多数
是 `targetSdk 36+` 才生效，停在 35 意味着这些变更不会作用到本应用。

完整的 Android 16 × SafeWalk 影响矩阵、以及"什么条件下才需要改代码"：
**[docs/hos4-impact.md](docs/hos4-impact.md)**。

---

## 📱 国产 UI 适配（HyperOS / MagicOS / ColorOS / OriginOS / AOSP）

功能主干与 ROM 无关：Hook 有 `ActivityThread` 兜底、前台服务三档降级、悬浮窗显示前复核、
磁贴绕过后台启动限制 —— 这些都不查表。**表只管一件事：把用户送到该去的设置页。**

自启动 / 后台弹窗这类开关没有公开 API，只能指路。指路表按本机 UI 选候选，运行时逐条
`resolveActivity` 校验，打不开就换下一条，全失败退应用详情页：

```
候选（component / action）→ 校验 → 打开 → 换下一条 → 应用详情页兜底
```

首次安装的权限引导页会显示「本机 UI：X · 自启动在『…』」，第 4 步按钮也按本机系统名生成，
AOSP 上直接写「本系统无此开关，可跳过」。

适配进度与真机验证状态见 **[docs/rom-support-matrix.md](docs/rom-support-matrix.md)**：
🟡 表示代码就绪但**未真机验证**，不会写成「已支持」。

---

## 🚀 安装

1. LSPosed 中启用「安全出行」
2. 作用域**只勾「系统界面（SystemUI）」**
3. 重启系统界面（或重启手机）
4. 打开 App，授予「身体活动」权限（Android 13+ 另有通知权限）
5. 打开首页总开关，再按需进二级菜单配置

## 🔧 编译

工具链（版本不匹配会直接报错，不会静默降级）：

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | **21** | Miuix 是 Java 21 bytecode（class 65），JDK 17 会报错 |
| Gradle | **9.6.0+** | AGP 9.4.1 的下限是 9.6.0；仓库已附带 Wrapper，无需自备 |
| Android SDK Platform | **android-37.2** | `compileSdk 37` 对应新的 minor 版本平台 |
| Build-Tools | **36.0.0** | AGP 默认；缺失时会自动下载 |

```bash
# 只需指定 JDK 与 SDK，其余交给 wrapper
export JAVA_HOME=<jdk21 路径>
export ANDROID_HOME=<sdk 路径>
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug        # Windows: gradlew.bat
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> **首次编译会拉全量依赖**，约 1.7 GB、十几分钟（取决于网络）；之后增量构建只要几十秒。
> 若 `./gradlew` 卡在下载 Gradle 发行包，说明 `services.gradle.org` 不可达，
> 可自行下载 `gradle-9.6.0-bin.zip` 放进 `~/.gradle/wrapper/dists/gradle-9.6.0-bin/<hash>/`，
> 或者直接用本地安装的 Gradle 9.6.0 执行同样的命令。

## 🧪 测试

- **步态判定**：纯 JVM 单元测试 `GaitDetectorPureTest`，无需真机/外网
- **界面与逻辑**：Robolectric 本地测试 `./gradlew :app:testDebugUnitTest`（首次需联网拉依赖）
- **界面截图**：`UiShotTest` 用 Robolectric 原生渲染把二级页面在浅色/深色下各出一张 PNG，落到临时目录供目视核对

当前共 **10 个测试类 / 62 个用例**，全部通过（`GaitDetectorTest` 13、`SubPageTest` 13、`GaitDetectorPureTest` 8、`NotificationTargetTest` 7、`StabilityTest` 6、`MainFlowTest` 5、`PreviewWindowTest` 4、`CrashDetectorTest` 2、`DiagLogTest` 2、`UiShotTest` 2）。

---

## ⚠️ 已知限制

| # | 问题 | 影响 | 状态 |
|---|---|---|---|
| 1 | 依赖加速度计持续监听 | 极端后台策略下传感器回调可能被掐断 | 前台服务缓解 |
| 2 | 全屏叠加个别 ROM 不出画面 | 已限定透明窗口路径，仍可能被厂商悬浮窗策略拦截 | 待真机反馈 |
| 3 | 车祸检测靠加速度阈值 | 手机脱落可能误报，留 60s 倒计时可取消 | 设计取舍 |
| 4 | 配置 Provider 白名单基于包名 | 包名被冒充可读开关（不含隐私字段） | 残余风险可接受 |
| 5 | 实验区入口是长按「关于」页按钮 | 新用户找不到 | 刻意保留 |
| 6 | HyperOS 4 无真机验证 | 兼容性结论来自代码路径 + 清单 + 单测，非实机 | 已知边界，日志可定位 |

---

## 🎖️ 致敬

建立在 **LSPosed / Xposed** 之上，算法与架构思路参考：

- **UI 液态玻璃**（本项目底部导航栏直接集成）：[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) —— 液态玻璃材质、拖拽阻尼交互、透镜折射、交互高光全套特效；配套渲染库 io.github.kyant0:backdrop-android
- **步态检测**：bagilevi/android-pedometer、mdeguzis/StepCounter、ashishb/android-step-detector、AccStepCounter、SensorFusion
- **状态机/传感器**：tonyo567/hysteresis-state-machine、p-v/Android-SensorService、UrbanBigDataCentre/meowmotion、KalebKE/FSensor
- **HyperOS 通知**：HyperBridge、WidgetEnhancer、HyperLight

> 所有代码为独立手写重构，仅借鉴思路与算法原理，未直接拷贝上述项目源码。

---

## 📄 许可证

AGPL-3.0，见 [LICENSE](LICENSE)。
