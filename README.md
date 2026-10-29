# 安全出行 SafeWalk

行走安全 LSPosed / Xposed 模块，注入 SystemUI 进程。走路时用后置摄像头看路况，低头看手机过久会提醒你抬头看路。

基于 GPL-3.0 开源。适配 HyperOS / Android 15+。

## 功能

- **后置摄像头路况**：走路时用后置摄像头实时显示前方路况。
  - 两种形态：全屏半透明叠加 / 角落小窗，可切换。
  - 触摸完全穿透：悬浮窗只显示、不挡操作，下层应用照常使用。
  - 手动模式：配置页或快速设置磁贴开关。
  - 自动模式：加速度计检测步行节奏，走起来自动开、坐下/停下自动收。
  - 相机避让：前台进入相机、微信、支付宝等相机类应用时自动收起路况摄像头让给对方；切回后冷却约 90 秒，确认仍在行走再自动恢复。
- **看路提醒**：行走中低头看手机持续超过设定秒数（默认 3 秒），弹 Toast 提醒「看路！走路时请留意四周」，20 秒节流。
- **深色模式**：跟随系统深浅色自动切换。
- **省电**：灭屏自动全部暂停；诊断日志默认关闭；配置按需重读，待机功耗趋近于零。

## 作用域

LSPosed 作用域勾选 **系统界面（com.android.systemui）**：
- 摄像头悬浮窗由系统进程叠加，无需悬浮窗权限；
- 传感器检测、快速设置磁贴、看路提醒都在 SystemUI 内运行。

## 安装

1. 在 LSPosed 中启用「安全出行」并勾选作用域「系统界面」。
2. 重启系统界面（或重启手机）。
3. 打开配置页，授予 root（保存配置需要超级用户权限），开启「总开关」，按需配置后保存。

## 配置

- 配置文件：`/data/local/tmp/safewalk.conf`（应用经 su 写入，SystemUI 读取）。
- 诊断日志：`/sdcard/Download/safewalk_diag.log`，默认关闭（排查问题时可手动在配置里写 `debug=true` 开启）。

## 编译

需要 JDK 17、Android SDK（compileSdk 35 / minSdk 30）：

```bash
JAVA_HOME=<jdk17路径> ANDROID_HOME=<sdk路径> gradle assembleDebug --no-daemon
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 支持捐赠

如果觉得好用，请作者喝杯奶茶。微信扫一扫即可，作者：@酷安锦无名。

<p align="center">
  <img src="donate_qr.webp" alt="微信捐赠二维码" width="240" />
</p>

## 🎖️ 致敬

本模块建立在 **LSPosed / Xposed** 框架之上，致敬并感谢这些伟大的开源项目，以及整个 Xposed / LSPosed 社区——正是他们让安卓模块生态生生不息。

## 许可证

GPL-3.0，见 [LICENSE](LICENSE)。

# Acknowledgments

SafeWalk 是面向 HyperOS / Android 的 KSU/LSPosed 出行感知模块，实现不依赖 GMS 的步行状态识别。项目大量参考优秀开源项目的算法、架构思路，特此感谢各位开发者的贡献：

1. **bagilevi/android-pedometer** — 纯加速度计步器经典实现：峰值检测、步频区间过滤、自适应阈值的基础思路，是步态检测部分最重要的参考来源。
2. **mdeguzis/StepCounter** — 滑动窗口方差统计实现、根据运动状态动态切换传感器采样率的省电策略。
3. **ashishb/android-step-detector** — 基于 FFT 频谱分析识别周期性步态的方案，作为高级增强功能备选。
4. **tonyo567/hysteresis-state-machine** — 分离式迟滞状态机思想，独立配置进入防抖、退出防抖计时器，解决状态频繁抖动切换。
5. **KalebKE/FSensor** — Madgwick/Mahony 姿态滤波、卡尔曼 GPS 平滑算法，作为后续 GPS 漂移优化的备选方案。
6. **p-v/Android-SensorService** — Android 后台传感器监听、系统采样节流、丢采样容错、前台服务适配方案，适配国内定制 ROM 后台限制。
7. **UrbanBigDataCentre/meowmotion** — GPS 轨迹分割、停留/移动分类、连续位移校验逻辑，用于离线导出轨迹日志调参。

> 声明：SafeWalk 所有代码为独立手写重构，仅借鉴思路与算法原理，没有直接拷贝上述项目源码。遵循 GPL-3.0 许可证开源。
