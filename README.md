# 安全出行 SafeWalk

> 海阔天空，勇敢去走。

纯本地、无服务器的**出行感知** LSPosed 模块。不依赖 GMS，不联网，不上传任何数据；
用手机自带加速度计判断「你是不是真的在走路」，再决定要不要提醒你。

- 运行环境：**HyperOS / Android 12+**（minSdk 33，targetSdk 35）
- 作用域：**只需勾选「系统界面（SystemUI）」**，不需要系统框架
- 开源协议：**AGPL-3.0**（见 [LICENSE](LICENSE)）
- 当前版本：**2.10.2**

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

## ⚙️ 核心机制

**步态检测**（跑在应用进程 `WalkPreviewService`，不在 SystemUI）：靠五重门槛层层收敛——
幅值上限挡大瞬态、步频窗口 0.5~2.5Hz、主轴稳定一致率 ≥70%、步数门槛 N、断步重置。
自适应阈值：最近 60 样本 `均值+1.8×标准差`，钳制 0.6~2.8 m/s²。

**行走模式**：SystemUI 只做「指挥」（传感器/步态/显隐广播），App 前台服务负责开相机渲染。
两种形态：全屏叠加（穿透）/ 角落小窗（150×260dp），形态/位置/透明度实时可调。

**手电筒**：夜间提醒通知直接开灯（`setTorchMode`，无需相机权限）；`TorchCallback` 全局状态同步；
静置超时提醒关灯省电；夜间暗光（<15lx）可自动开灯。

**车祸预警**：合加速度超阈值且**连续 2 次命中**判定撞击，60s 冷却防误报；三档灵敏度（1.5/2.0/2.5g）；
全屏急救卡 + 60s 倒计时，超时先发含位置短信、再拨紧急联系人，无权限时降级跳转不静默失败。

**通知**：统一高优先级 `IMPORTANCE_HIGH` heads-up，可被 HyperOS 超级岛拦截；各通道独立冷却。

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

当前共 **9 个测试类 / 60 个用例**，全部通过（`GaitDetectorTest` 13、`GaitDetectorPureTest` 8、`SubPageTest` 13、`NotificationTargetTest` 7、`StabilityTest` 6、`MainFlowTest` 5、`PreviewWindowTest` 4、`CrashDetectorTest` 2、`DiagLogTest` 2）。

---

## ⚠️ 已知限制

| # | 问题 | 影响 | 状态 |
|---|---|---|---|
| 1 | 依赖加速度计持续监听 | 极端后台策略下传感器回调可能被掐断 | 前台服务缓解 |
| 2 | 全屏叠加个别 ROM 不出画面 | 已限定透明窗口路径，仍可能被厂商悬浮窗策略拦截 | 待真机反馈 |
| 3 | 车祸检测靠加速度阈值 | 手机脱落可能误报，留 60s 倒计时可取消 | 设计取舍 |
| 4 | 配置 Provider 白名单基于包名 | 包名被冒充可读开关（不含隐私字段） | 残余风险可接受 |
| 5 | 实验区入口是长按「关于」页按钮 | 新用户找不到 | 刻意保留 |

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
