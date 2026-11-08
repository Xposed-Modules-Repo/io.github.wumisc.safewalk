# SafeWalk 逻辑设计文档

> **版本**：2.10.2（versionCode 29）
> **重写日期**：2026-11-08
> **对齐源码**：`app/src/main/java/io/github/wumisc/safewalk/`（本仓库当前状态）
> **原则**：以代码为准。本文每条结论都能在标注的文件:行号处核对，若文档与代码冲突，改文档。

## 0. 这份文档替代了什么

上一版是 **v2.1（2026-10-30）**，它描述的是"单进程 + 单配置文件 + 三道步态关卡"的旧架构。当前代码已经是**双进程 + ContentProvider + 步态只做旁证**，两者在关键结论上互相矛盾（详见 §14 对照表）。v2.1 里"作用域勾 android""150 步阈值""VERIFYING 关卡""90 秒避让冷却""息屏全停"这些描述**在代码中都不存在**，照它改必踩坑。

---

## 1. 进程模型（读后面所有内容的前提）

模块不是一个进程在跑，而是**两个进程分工**。这是 2.9.x 之后最大的一次架构调整，也是历史上多数"功能时好时坏"的根源。

```
┌────────────────── SystemUI 进程（com.android.systemui，LSPosed 注入）──────────────────┐
│ SafeWalkLSPHook                                                                       │
│   tick（2s / 5s）                                                                      │
│    ├─ check()         计步 → 状态机 → 出门提醒（证件/社交/夜间手电/久坐/电量）          │
│    ├─ updateOverlay() 只做一件事：算"该不该让路"，给 App 发 SHOW / HIDE 信号            │
│    ├─ CrashDetector   撞击检测 → 广播给 App                                            │
│    ├─ TorchNotify / TorchStaticReminder   监听手电状态、静置提醒                        │
│    └─ setTorchWithRetry()                 手电兜底通道                                  │
│ 没有任何 Camera 对象、没有任何悬浮窗、不写用户可见文件                                  │
└───────────────────────────────┬───────────────────────────────────────────────────────┘
        ConfigProvider（ContentProvider，只读）
        广播（双向，全部带 token）
┌───────────────────────────────┴──────── App 进程（io.github.wumisc.safewalk）──────────┐
│ WalkPreviewService（camera 类型前台服务）                                              │
│   └─ RearCameraOverlay：Camera2 + TYPE_APPLICATION_OVERLAY 窗口                        │
│   └─ 自己的 GaitDetector + TYPE_STEP_COUNTER：判走/停（自动模式）                       │
│ AutoStartActivity（透明页）← Hook startActivity 拉起，代启上面的服务                    │
│ TorchActionActivity → TorchController：先让预览释放相机，再点灯                         │
│ MainActivity / WalkModeActivity / SmartReminderActivity / EmergencyCardActivity / Lab… │
│ DiagLog：汇总两个进程的日志，统一落盘                                                   │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

### 为什么必须拆两个进程

| 能力 | 放 SystemUI 的后果 | 现在的归属 |
|---|---|---|
| 相机（Camera2） | SystemUI 拿不到可靠的 CAMERA 权限，各 ROM 授不授看厂商，实测开不出画面 | App（`WalkPreviewService`） |
| 悬浮窗 | 同上，`TYPE_APPLICATION_OVERLAY` 需要 `SYSTEM_ALERT_WINDOW` | App |
| 计步传感器 | SystemUI 常"注册成功但永不回调"（缺 `ACTIVITY_RECOGNITION`） | 两边都有，**以 App 的为准** |
| 配置存储 | SystemUI 读不到 App 私有目录 | App 写，Provider 暴露给 SystemUI |
| 系统级观察（前台应用、手电 TorchCallback、撞击） | App 进程会被后台策略掐传感器 | SystemUI |

**一句话**：SystemUI 侧负责"判断"，App 侧负责"执行"。相机和悬浮窗的一切都在 App 进程，SystemUI 只发信号。

---

## 2. 启动与初始化链

### 2.1 入口（四层，任一命中即可启动）

```
LSPosed 加载模块
 → SafeWalkLSPHook.handleLoadPackage()            [SafeWalkLSPHook.kt:334]
    ├─ packageName != "com.android.systemui" → 直接 return（只认 SystemUI）
    │
    ├─ 锚点 1  SystemUIApplication.onCreate          [:349-362]
    │     AOSP 与多数 ROM 的主入口
    ├─ 锚点 2  Instrumentation.callApplicationOnCreate  [:365-380]
    │     不认类名，任何 Application 都必经 → OEM 改类名也拦不住
    ├─ 锚点 3  SystemUIService.onCreate             [:383-398]
    │     SystemUI 自己的 Service，启动极早，作为旁路
    │         ↓ 三个锚点统一走 triggerInit(ctx, 来源)
    │         ↓ init() → postDelayed(2000ms) → runInit()（有幂等保护）
    │
    └─ 兜底通道  startContextFallback()             [:402, 443-479]
          独立守护线程，反射 ActivityThread.currentApplication() 自取 Context
          锚点已挂上 → 先等 8 秒；一个都没挂上 → 1 秒后立刻开始
          每 500ms 试一次，最多 40 次（20 秒）；拿到就 triggerInit，随即退出
```

**为什么要有四层**：原来只有锚点 1。OEM 换掉 `SystemUIApplication` 类、或改了启动顺序，
模块就整个静默失效，日志里只留一行「找不到 SystemUIApplication 类」——用户看到的现象是
「装了但什么都不动」，且没有任何其他线索。现在任一层命中都能起来，第一个命中的来源会写进日志
（`[hook] 初始化触发源: …`），排查时一眼能看出这台 ROM 走的是哪条路。

四个入口都只调 `triggerInit`，最终落到自带幂等保护的 `runInit`，**不会重复初始化**（见 §2.2）。

### 2.2 init 做了什么（`init()` → `postDelayed(2000ms)` → `runInit()`）

`init()` 只是延迟 2 秒转调 `runInit()`，真正的初始化在 `runInit()` 里，**可重试**：

```
runInit()
├─ synchronized(initLock)：initSucceeded → return；initRunning → return；置位 initRunning
├─ 核心步骤（唯一会触发重试的部分）
│    ctx = appCtx.applicationContext
│    DiagLog.init(ctx)                          ← 失败则 coreOk=false
├─ coreOk == true 时，逐步执行（每步独立 try，见下）
└─ 收尾：initRunning=false；coreOk → initSucceeded=true；否则 scheduleInitRetry()
```

| 顺序 | 动作 | 失败后果 | 是否单步兜底 |
|---|---|---|---|
| 1 | `ctx` + `DiagLog.init` | 整个模块不可用 → 退避重试 | 否（核心） |
| 2 | `refreshConfig(force=true)` **仅发起**，读取在 `SafeWalk-config` 后台线程 | 沿用默认值 / 旧快照 | 是 |
| 3 | `SafeWalkNotificationManager`、`SensorManager` | 提醒发不出 / 无传感器 | 是 |
| 4 | `updateCrashDetection()` 按开关启停撞击监听 | 车祸预警不可用 | 是 |
| 5 | `startLightMonitor()` 注册环境光（lux） | 夜间自动开灯降级为发通知 | 是 |
| 6 | `createTorchNotifyChannel` + `TorchNotificationManager.startMonitor()` + `TorchStaticReminder` | 手电静置提醒不可用 | 是 |
| 7 | `registerControlReceivers()` 注册 6 个跨进程广播（见 §10） | 对应功能失联 | 是（每个接收器单独登记） |
| 8 | `registerStepDetector()` 系统计步器 | 失败会落到加速度计兜底 | 是 |
| 9 | `startAccFallback("恒定启用")` **无条件**启动加速度计计步 | 与系统计步器并行，300ms 去重 | 是 |
| 10 | `registerBatteryReceiver()`（`ACTION_BATTERY_CHANGED`，非导出） | 电量预警不可用 | 是 |
| 11 | `startTick()` | 整套逻辑不转 | 是（`tickStarted` 防止挂两条心跳） |

**三个约束**（改这块之前务必读）：

1. **单步失败不扩散**。以前整段包在一个 `try` 里，第一处 `registerReceiver` 抛异常会把后面计步器、广播、心跳全部跳过，现象是"模块装了但什么都不动"，日志只有一行"初始化失败"。现在 `step("名字") { }` 逐段兜底，日志能直接指出是哪一步挂了。
2. **重试不许重复注册**。`registeredReceivers` 按名字登记，同一个接收器第二次进来直接跳过；注册失败会从表里摘掉，让下次重试还能补上。否则重试一次 = 一条提醒弹两次。
3. **重试是退避的**。2s → 4s → 8s … 封顶 60s，最多 20 次（约 15 分钟）。SystemUI 本身残缺时无限重试只会刷爆日志、白耗电。

> **关键设计**：第 8、9 步是"两条腿走路"。历史上是"系统计步器不可用才启用兜底"，但这台设备上系统计步器**注册成功却一步都不回**（SystemUI 缺 `ACTIVITY_RECOGNITION`，注册不抛异常、只是永远没回调），结果"走半天没反应"。现在两边恒定并行，`recordStep()` 里按 300ms 去重（人走不出 3.3 步/秒）。

> **配置为什么不在 init 里同步读**：`contentResolver.query` 是同步 Binder 调用，App 被回收时还会把它冷启动起来，耗时几百毫秒到数秒。init 跑在主线程，卡住就是黑屏/ANR。现在 init 只 `refreshConfig(force=true)` 发起请求，结果回到主线程后再重算 `updateCrashDetection()` / `updateOverlay()`（见 §9）。

### 2.3 没有的东西（别再按旧文档找）

- **没有**反射 `ActivityThread.mInitialApplication` 的 12 秒兜底（v2.1 说有）。
- **没有**二级 Hook 锚点（`SystemUIService.onCreate` / `handleBindApplication`）。类名一改就整体失效，属于已知 P1（§13）。

---

## 3. 定时循环 tick

唯一心跳，`Handler.postDelayed` 自调度，**不是** `ScheduledExecutor`。

```kotlin
tickRunnable.run() {                    // [SafeWalkLSPHook.kt:957]
    try {
        check()          // 步态与提醒
        updateOverlay()  // 悬浮窗让路信号
    } catch (t) { diag("tick: $t") }     // 只吞不抛，绝不带崩 SystemUI
    postDelayed(this, if (masterEnabled()) 2000 else 5000)
}
```

- 总开关开：**2 秒**；总开关关：**5 秒**（关着还 2 秒一轮纯属白耗电）。
- tick 里没有递归、**没有阻塞等待**：配置读取已挪到 `SafeWalk-config` 后台线程，tick 只读内存快照（见 §9）。

### 3.1 `check()` 的执行顺序

```
refreshConfig()                          // 60 秒缓存；只在后台线程查，主线程不阻塞
├─ 总开关关 → 停加速度计兜底、静置计时归零、2 分钟一条日志、return
├─ 兜底计步意外停了（30 秒节流）→ startAccFallback("兜底未运行，自动恢复")
├─ 零步探针：启动 45 秒仍 0 步 → 写一条"仍没收到任何步数事件"
├─ 2 分钟状态摘要（状态/窗口步数/上一步多久前/计步来源/累计）
├─ 静置：now - lastStepTs > staticResetMs（默认 20s）
│    → walking=false，清过期步，checkSitRemind()，state=SLEEP，return
├─ 有步：walking=true
├─ 触发冷却：5 分钟内 return
└─ steps.size >= stepThreshold（默认 50）
     → triggerMovement()     发提醒
     → sampleGaitForLog()    采 4 秒加速度计，只写日志
```

### 3.2 状态机（只有两个状态）

```
        recordStep()（收到一步）
SLEEP ───────────────────────────► LIGHT_MONITOR
  ▲                                     │
  │  静置超过 staticResetMs（默认 20s） │
  └─────────────────────────────────────┘
        │ steps.size >= 阈值
        └──► triggerMovement() → 清空窗口 → 回 SLEEP
```

`State` 枚举只有 `SLEEP` / `LIGHT_MONITOR`（`SafeWalkLSPHook.kt:35`）。v2.1 里的 `VERIFYING` 关卡在 2.9.4 被删掉了，原因写在 `check()` 的注释里：4 秒采样只够 5~6 步，要求"每个间隔与均值差 <250ms"，手机在口袋里一晃就不满足，真机上几乎永远通不过——表现就是"走满 50 步不提醒、走满 100 步才兜底"。

> **注意**：`walking` 这个字段现在**只写不读**（`updateOverlay` 已不依赖它，自动显隐交给 App）。留着是为了日志可读性，删掉也不影响逻辑。

---

## 4. 计步与步态判定

### 4.1 双路计步

| 来源 | 传感器 | 采样 | 说明 |
|---|---|---|---|
| 系统计步器 | `TYPE_STEP_DETECTOR` | `SENSOR_DELAY_NORMAL` | 硬件计步，几乎不耗电；很多 ROM 在 SystemUI 里不回数据 |
| 加速度计兜底 | `TYPE_ACCELEROMETER` → `GaitDetector` | `SENSOR_DELAY_UI`（≈15~60Hz） | **恒定开启**，与系统计步器并行 |

两路都汇到 `recordStep(now)`：`now - lastStepTs < 300ms` 直接丢弃，否则入 5 分钟滑动窗口。

`startAccFallback` 复用**同一个 `GaitDetector`**，不再自带一套阈值判断。历史上兜底自带 `0.8*old + 0.2*new` 的滤波（和 `GaitDetector` 曾有的写反 alpha 是同一个错误），"看着已启用、实际一步都不计"，两套实现互相掩盖。

### 4.2 GaitDetector 算法（`GaitDetector.kt`）

信号链：**首帧对齐重力 → 一阶低通分离重力 → 主轴自适应 → 时间衰减包络阈值 → 上升沿计峰 → 步频窗口校验 → 主轴一致性校验**。

| 环节 | 做法 | 代码 |
|---|---|---|
| 重力初值 | 用**第一帧读数**当重力初值，不从 0 收敛 | `:199-206` |
| 低通 | `g = 0.96*g + 0.04*a`（α=0.96 ≈ 0.5Hz 截止） | `:209-211` |
| 预热 | 再丢 3 帧（60ms），挡姿态抖动 | `:69`、`:220-224` |
| 主轴自适应 | 三轴线性加速度的 EMA（α=0.98）取能量最大者 | `:229-236` |
| 包络 | `env = min(max(|v|, env*exp(-dt/1500ms)), 8.0)` | `:243-244` |
| 阈值 | `max(0.6, env*0.6)`，平躺再 ×1.45 | `:247-249` |
| 计峰 | 只认**上穿**（`aboveThreshold` 边沿），200ms 不应期 | `:252-257` |
| 断步 | 距上一峰 > 2500ms → 清空间隔队列重数 | `:259-263` |
| 步频 | 间隔换算频率必须在 **0.5~2.5Hz** | `:265-266` |
| 队列 | 上限 10（必须 ≥ `confirmSteps` 最大档，否则永远攒不满） | `:271-274` |
| 判走 | 合格步 ≥ `confirmSteps`（默认 5）**且**主轴一致率 ≥70% | `:281`、`:321-329` |
| 平躺 | `LAY_FLAT` 姿态不返回 true（步仍累计） | `:285-287` |

### 4.3 参数表（真实值）

| 参数 | 值 | 为什么是这个值 |
|---|---|---|
| `alpha` 重力低通 | **0.96** | 0.2 是低通写反（等于 80% 跟新样本跑），残差只剩 1% 量级，一个峰都不会出现 |
| 预热帧 | 3 帧 | 重力已首帧对齐，这里只挡最初几帧翻转抖动 |
| 主轴 EMA α | 0.98 | 挑"这次走路主要晃哪个轴"，手机在口袋/手里/包里方向随机 |
| 包络系数 `ENV_FACTOR` | **0.6** | 旧的"均值+1.8σ"在小信号下自相矛盾（算出来 1.2 倍幅值 > 幅值本身） |
| 包络衰减 `ENV_DECAY_MS` | **1500ms** | 原来 4 秒，一次拿放手机的瞬态能把阈值顶到 5~6 长达数秒，真实峰值只有 2.5 |
| 包络上限 `MAX_PEAK_VALUE` | **8.0 m/s²** | 超过就是拿放/翻转/拍打，不计峰也不污染包络 |
| 峰值下限 | 0.6 m/s² | 低于它就是噪声 |
| 不应期 `REFRACTORY_MS` | **200ms** | ≈5 次/秒，挡同一脚步抖动与主轴切换造成的重复计数 |
| 断步 `GAIT_RESET_MS` | **2500ms** | 必须明显宽于步频下限（2000ms），否则慢走被当断步清空 |
| 步频窗口 | **0.5 ~ 2.5 Hz** | 真实散步 0.7~1.1Hz，快走 1.5~2Hz；3Hz 以上抖腿挡在外面 |
| `confirmSteps` | **5**（UI 可调 2~10） | 连续合格步数门槛，防单手刷手机误触 |
| 主轴一致率 | **≥70%** | 走路上下颠簸主轴固定；手持乱动方向随机 |
| 平躺阈值 `LAY_FLAT_Z` | **8.5** | `|gz| > 8.5` 判平放 |
| 平躺权重 | **×1.45** | 躺着刷手机更难触发 |
| 姿态门限 | `gMag < 8` → UNKNOWN | 自由落体/异常读数不算姿态 |
| `hasStableGait` 容差 | **400ms** | 只用于自检与日志；250ms 会把真机正常步行判成"不稳" |

### 4.4 `reset()` 的边界

`reset()` 清峰队列、包络、姿态、静置计时，**刻意不清** `gravity` / `ema` / `warmupLeft`（`:356-369`）。原因是 2.9.3 前的真凶之一：每轮采样前 reset 把重力清 0，于是每轮都要从 0 收敛到 9.8，前 0.6 秒残差 9.4，包络被顶死，日志里每轮都是"峰数=0"。

### 4.5 步态复核只写日志（`sampleGaitForLog`）

步数达标触发提醒后，额外采 4 秒加速度计（`SENSOR_DELAY_GAME`，4.6 秒兜底收尾），把 `峰数/步频/阈值/包络/姿态/稳定` 写进日志。

**它不决定要不要提醒。** 判错顶多多弹一条通知，判不出是整趟夜路一条都没有——这个取舍是明确的。

---

## 5. 提醒链

### 5.1 触发条件

```
5 分钟滑动窗口内步数 >= stepThreshold（默认 50，UI 可调）
且距上次触发 >= 5 分钟
```

步数**不清空**地等静置：静置只把 `walking` 置 false 并让滑动窗口自然过期，不清整个窗口。否则"等个红灯、进个电梯、系个鞋带"就把前面几十步作废，走走停停的人永远凑不满。

### 5.2 `triggerMovement()` 发什么

| 顺序 | 提醒 | 开关 | 冷却 | 附加条件 |
|---|---|---|---|---|
| 1 | 证件提醒 | `enableIdReminder`（默认开） | **24 小时** | — |
| 2 | 社交/出行模板提示 | `enableSocialTipCard`（默认开） | 通知层 60 秒 | 按 `travelMode`（求职/约会/聚会/旅游）取模板，取前 3 条 |
| 3 | 夜间手电 | `enableNightTorch`（默认开） | 通知层 60 秒 | 夜间时段 + **屏幕亮着** + **预览未在显示** |
| 3b | 夜间自动开灯 | `enableNightTorchAuto`（默认关） | 同上 | 在 3 之上再要求 `lux < 15` 且读数 10 秒内有效 |
| 4 | `walking = true` | — | — | 行走模式由 `updateOverlay` 统一驱动 |

夜间时段：`nightTorchStartHour`（默认 22）到 `nightTorchEndHour`（默认 6），跨零点判断。

> 手电这一步的判据是 **`lastSentShow == true`**（预览真的在屏幕上），不是"行走模式开关开着"。旧写法 `enableRearCamera && walking` 里 `walking` 在进函数前就被置 true，等于"只要开了行走模式就永远不提醒手电"，自动模式下没显示画面时也白跳过。

### 5.3 其余提醒

| 提醒 | 触发 | 冷却 | 位置 |
|---|---|---|---|
| 久坐 | `now - lastStepTs > 2 小时` 且**非夜间** 且 `lastStepTs != 0` | 24 小时 | `checkSitRemind` `:774` |
| 电量 | 电量 ≤ **15%**（`ACTION_BATTERY_CHANGED` 广播） | 1 小时 | `:933-954` |
| 手电静置 | 见 §8.3 | 首次 + 最多补 2 次 | `TorchStaticReminder` |
| 车祸 | 见 §9 | 60 秒 | `CrashDetector` |

> 久坐的隐藏边界：`lastStepTs == 0` 直接 return，**开机后一次都没走过就永远不会提醒久坐**。这是刻意的保守（避免"刚开机就告诉你坐了 2 小时"），但代价要知道。

### 5.4 通知实现

- 通道 `SAFEWALK_REMIND`，`IMPORTANCE_HIGH`（走超级岛/heads-up），震动 + 默认提示音 + 锁屏可见。
- 通知 ID：`1001` 电量 / `1002` 证件 / `1003` 久坐 / `1004` 社交 / `1005` 夜间手电 / `1006` 预览失败。
- **按 ID 独立 60 秒冷却**（原来是全局一个时间戳，一次出门三条提醒只有第一条能弹）。
- 全部 60 秒自动消失（夜间手电 120 秒）；点通知主体进 App。
- 夜间手电通知带两个按钮：「手电筒」→ `TorchActionActivity`（透明 Activity，不是广播，理由见 §8.1）、「打开SafeWalk」。
- `PendingIntent` 必须 `setClassName(APP_PKG, ...)`：Hook 的 `ctx` 是 SystemUI，用 `Intent(ctx, MainActivity::class)` 会拼成 `com.android.systemui/...MainActivity`，点了必打不开。

---

## 6. 行走模式（后视预览）

### 6.1 职责边界

| 侧 | 做什么 |
|---|---|
| Hook（SystemUI） | 只算"要不要让路"，用 `ACTION_HOOK_SHOW` / `ACTION_HOOK_HIDE` 通知 App |
| `WalkPreviewService`（App） | 决定"到底显不显示"、开相机、挂窗口、按自动/手动模式显隐 |

Hook **不再**用自己进程的 `walking` 决定显隐——因为它在多数 ROM 上恒为 false，这正是"只有手动模式好用"的根因。

### 6.2 Hook 侧判定顺序（`updateOverlay`，命中即 return）

| 顺序 | 条件 | 动作 |
|---|---|---|
| 1 | `!masterEnabled() \|\| !enableRearCamera` | `previewUntil=0`，发 **HIDE** |
| 2 | `now < torchHoldUntil`（点灯后 60 秒）或 `TorchCallback` 报手电开 | **不干预**（return，不发任何指令） |
| 3 | 前台是相机类应用（**四级降级检测**，5 秒节流） | `previewUntil=0`，发 **HIDE** |
| 4 | 其他 | `maybeAutoStartPreview()` → 发 **SHOW**（语义是"让路解除，可以恢复了"） |

#### 第 3 步的前台检测怎么做的（`foregroundPackage`）

原来只有一级 `ActivityTaskManager.getFocusedRootTaskInfo()`，抛异常被 `catch (_: Throwable) { false }`
吞掉：表现是「在相机界面悬浮窗不收起、挡住取景」，而日志里干干净净。现在四级降级，**任意一级拿到就用**：

| 级 | 接口 | 适用 |
|---|---|---|
| 1 | `ActivityTaskManager.getFocusedRootTaskInfo().topActivity` | Android 10+ 主流，正常机型都走这条 |
| 2 | `ActivityTaskManager.getTasks(1)`（反射遍历方法签名，兼容 1/2/3 参数版本） | focused 接口被 ROM 屏蔽时 |
| 3 | `ActivityManager.getRunningTasks(1)` | 老 API；普通应用只能拿到自己，系统进程常能拿到 |
| 4 | `UsageStatsManager.queryEvents(近 15 秒)`，取"最后动作是进入前台"的包 | 前三级全废时的兜底推断 |

- **上次成功的层级会被记住**（`camDetectLayer`），下次直接用，不再反复踩失败路径；换级时写一行
  `[cam] 前台检测启用第 N 级（接口名）`。
- 四级全失败 → 返回 `null` → **不避让**（宁可显示，也不要因为检测坏了就永远不显示），并只告警一次：
  `[cam] ⚠️ 四级前台检测全部失败：相机避让失效`。
- 命中相机时按包名去重记一行：`[cam] 前台是相机应用 <包名> → 让路收起预览`。

相机包名清单（22 个，覆盖华米 OV 荣耀三星魅族索尼摩托华硕中兴 + 高通/联发科参考相机）：
`com.android.camera` / `com.android.camera2` / `com.huawei.camera` / `com.hihonor.camera` /
`com.miui.camera` / `com.xiaomi.camera` / `com.oplus.camera` / `com.coloros.camera` /
`com.vivo.camera` / `com.bbk.camera` / `com.samsung.android.camera` / `com.sec.android.app.camera` /
`com.oneplus.camera` / `com.google.android.GoogleCamera` / `com.meizu.camera` /
`com.sonyericsson.android.camera` / `com.motorola.camera` / `com.motorola.camera2` /
`com.asus.camera` / `com.zte.camera` / `com.org.codeaurora.camera` / `com.mediatek.camera`，
外加"包名含 camera 且不含 safewalk"的关键字兜底。

> **刻意不收微信 / 支付宝 / 抖音 / 云闪付**：它们虽然带扫码，但主要不是取景。把它们算进避让清单，
> 等于悬浮窗在最常用的聊天、支付场景里全收起来，功能直接失效。
>
> `RearCameraOverlay.isCameraAppInForeground()` 那份重复实现已删除（相机搬到 `WalkPreviewService`
> 后它就没有调用方了），避免两份反射逻辑各自漂移。


### 6.3 App 侧显隐决策（`evaluateShow`）

```
hookHidden == true            → 不显示（等 Hook 发 SHOW）
enableRearCamera == false     → 不显示
rearCamAuto == true           → gaitWalking ? doShow() : overlay.hide()
rearCamAuto == false          → 常显 doShow()
```

### 6.4 自动模式（`WalkPreviewService` 内的第二套步态判定）

自动模式的"走/停"**不在 Hook 里判**，而在 camera 前台服务里判——前台服务的传感器回调不会被后台策略掐断。

| 环节 | 规则 |
|---|---|
| 判走 | `GaitDetector.feedAcc()` 返回 true（合格步）后，还要过两道门 |
| 门 1：硬件计步确认 | 最近 **10 秒** 内 `TYPE_STEP_COUNTER` 增量 ≥ **2 步** |
| 门 1 的降级 | 无计步器 / 从未回调 → 不拦；计步器启动后从未计步（被 ROM 节流）→ 判定不可信、停用确认 |
| 门 1 的例外 | 硬件步数不够但 `confirmedStepCount >= confirmSteps*2`（持续判走）→ 放行 |
| 门 2：收起冷却 | 判定停下收起后 **5 秒**内，必须累计步 ≥ `confirmSteps*2` 才允许再显示 |
| 判停 | `now - lastStepTs > gaitStopSec`（默认 10 秒，可调 5~30）；判停后把 `lastStepTs` 清 0，避免每秒重复判停 |
| 巡检 | `gaitTick` 每秒一次 |

门 1 的意义：加速度计判"走"只是波形像走，拿在手里乱动、刷屏、放桌上颠，波形都像走；只有硬件计步器是"真迈步才计数"。
门 2 的意义：打断"显示 → 收起 → 秒开"死循环（用户反馈的"没完没了"）。

### 6.5 相机链路与看门狗（`RearCameraOverlay`）

| 机制 | 值 | 作用 |
|---|---|---|
| 窗口类型 | `TYPE_APPLICATION_OVERLAY` | 需 `SYSTEM_ALERT_WINDOW`；小窗四角可选，全屏铺满 |
| 形态 | `fullscreen` / `smallwindow`；透明度 10~100（默认 50）；四角 `bottom_right` 等 | 配置项 |
| `cameraGen` 代号 | 每次 `closeCamera` +1，回调代号不符就关掉 | 防"旧设备 close 未完成、新设备覆盖字段"导致句柄永久泄漏（曾同时搞坏预览和手电） |
| `opening` 标志 | 异步窗口期挡重复 `openCamera` | `onSurfaceTextureAvailable` 在部分 ROM 会回两次 |
| `REOPEN_GAP_MS` | **400ms** | HAL 释放是异步的，close 后立刻 open 会让 `onConfigured` 永不回调 |
| `SESSION_TIMEOUT_MS` | **3000ms** | 会话配置卡死 → 主动释放重来 |
| `FRAME_TIMEOUT_MS` | **6000ms** | 一帧都没来 → 收起窗口 + 发"预览失败"通知（黑屏 vs 没反应要能分清） |
| `MAX_CAMERA_RETRY` | 2 | 相机被别的应用占着时不能无限重开 |

看门狗报错前会把现场写进日志：`mode/opacity/cam/session/opening/gen/retry/surface/view 尺寸`——黑屏到底是窗口、相机还是合成的问题，一条日志能分辨。

### 6.6 去重与回执（最容易踩的坑）

- Hook 侧 `lastSentShow` 做"只发变化"去重，避免每 2 秒一次跨进程广播。
- 但 App 侧手动关预览后 Hook **毫不知情**，`lastSentShow` 停在 true，下一次"该显示"会被去重吞掉 → 表现为"第一次能开，关掉之后再开不出来"。
- 所以 App 每次**真正**显示/收起的跳变都回一条 `ACTION_PREVIEW_STATE`，Hook 用它校准记录（`previewStateReceiver`）。只在跳变时回执，否则站着不动会形成每 2 秒一次的乒乓广播。

### 6.7 手动预览的 30 秒豁免

App 点"预览"时发 `ACTION_PREVIEW_MANUAL`，Hook 记 `previewUntil = now + 30s`，期间不干涉（否则"停下就收"会在刚点开的一瞬间关掉）。进入相机避让分支时会把 `previewUntil` 清 0，否则"开着预览去拍张照，回来时预览期还没过，悬浮窗再也不回来"。

---

## 7. 开机自启（2.10.2 新增）

### 7.1 为什么不能用 `BOOT_COMPLETED`

- **Android 14+**：CAMERA 是"使用期间"权限，应用在后台时不持有该权限，从后台 `startForegroundService` 一个 camera FGS 直接 `SecurityException`。
- **Android 15**（本项目 `targetSdk 35`）：明令禁止 `BOOT_COMPLETED` 接收器启动 camera / dataSync 等类型的 FGS，抛 `ForegroundServiceStartNotAllowedException`。

官方稳定豁免中最适合本模块的一条：**启动 FGS 的那一刻，本应用有一个可见 Activity**。

### 7.2 链路

```
Hook（SystemUI，系统进程）
  └─ startActivity(AutoStartActivity, NEW_TASK|NO_ANIMATION, 带 token)
       └─ onCreate: 校验 launchedFromUid → WalkPreviewService.start(this)
            └─ postDelayed(400ms) → finish()
```

透明页存在的那几百毫秒里应用可见，`startForegroundService` 合法；随后自己退出，用户基本无感。

### 7.3 触发条件（`maybeAutoStartPreview`）

| 条件 | 说明 |
|---|---|
| `autoStartLaunched == false` | 每个 SystemUI 进程只成功拉一次，避免每 2 秒闪一下透明窗 |
| `now - lastAutoStartTry >= 60s` | 失败后 60 秒冷却重试 |
| `PowerManager.isInteractive == true` | 必须亮屏 |
| `KeyguardManager.isKeyguardLocked == false` | 必须已解锁（锁屏时 `startActivity` 会被拦，透明窗也可能压在锁屏上） |
| 只在 `updateOverlay` 的**最后一步**调用 | 即"总开关 + 行走模式都开、且没被手电/相机让路"时才拉 |

### 7.4 安全校验（`AutoStartActivity.isTrustedLauncher`）

`exported=true`（调用方是 SystemUI，不同 uid），所以必须挡第三方：

1. `uid == SYSTEM_UID(1000)` → 放行；
2. `uid in 1 until FIRST_APPLICATION_UID` → 放行（兼容 SystemUI 变体）；
3. 否则必须携带正确 `token`（兼容给 SystemUI 分配了应用段 uid 的 ROM）。

不通过就写一条 `[walk] AutoStart 被不可信 uid=N 拉起，拒绝` 并直接 finish。

### 7.5 边界

- Hook 必须活着（作用域勾了 SystemUI），否则没有拉起方。
- 少数 ROM 禁止系统进程启动第三方 Activity → 日志会出现"拉起自启页失败，稍后重试"，每 60 秒重试；始终失败就得换"SystemUI 发 PendingIntent"或"开机通知一键开启"兜底。
- 之前"每次重启都要手动拨开关"的根因不是配置丢了（SP 一直持久化着，代码里没有任何地方把 `enableRearCamera` 写回 false），而是**服务从来没被拉起过**：Hook 的 SHOW 广播只由服务内部动态注册的接收器接收，服务没跑 → 接收器不存在 → 广播没人收 → 永远不自己醒。

---

## 8. 手电模块

### 8.1 双通道

| 通道 | 路径 | 何时用 |
|---|---|---|
| **主路径（App 进程）** | 通知按钮 → `TorchActionActivity` → `TorchController` → `TorchHelper.setTorchEx` | 默认；预览正占着相机时只有它能成功 |
| 兜底（SystemUI） | `OPEN_TORCH`/`CLOSE_TORCH` 广播 → Hook `setTorchWithRetry` | App 进程被回收、主路径失败时 |

为什么主路径在 App：一颗摄像头同时只能有一个使用者。预览在 App 进程拿着 `CameraDevice`，SystemUI 调 `setTorchMode` 必抛 `CAMERA_IN_USE`——用户看到的就是"按了没反应"。只有 App 自己知道"我正占着相机"，也只有它能"先收预览再点灯"。

主路径流程：`isPreviewShowing()` → 发 `ACTION_HIDE` → 等 **500ms**（HAL 断开）→ `setTorchEx`；`inUse` 时每 **700ms** 重试，最多 **3 次**；第 1 次重试时若服务还活着，**强制再收一次预览**（"窗口收了但设备没释放干净"的唯一入口）。

兜底重试：`300 / 600 / 1200 / 2000ms` 四次（累计约 4 秒），第一次通常就成功。

### 8.2 与预览互斥

- 点灯成功后 `torchHoldUntil = now + 60s`：期间 `updateOverlay` 直接 return，不把预览拉回来抢摄像头。
- 开灯前 `releasePreviewForTorch()` 发 HIDE 并同步 `lastSentShow=false`（否则去重会吞掉下一次显示指令）。

### 8.3 手电静置提醒（`TorchStaticReminder`）

| 规则 | 值 |
|---|---|
| 触发语义 | **手电开着** 且 **人一直没动**，从"两者都成立"起算满阈值 |
| 阈值 | `torchStaticMinutes`，默认 **12 分钟**（float，历史存过 int，读取用 `optDouble` 兼容） |
| 首次提醒后 | 通知 60 秒自动消失；仍开灯仍静置 → 每 **5 分钟**补一次，最多补 **2 次** |
| 短颠簸宽限 | 连续"在动"不足 **12 秒**不算真走动，保留静置起点（否则坐公交晃一路永远凑不满） |
| 状态源 | `CameraManager.TorchCallback` 全局监听（用户在控制中心点的灯也算） |
| 首帧 | 注册回调会立刻回传一次当前状态，首帧不弹通知 |

> 旧实现把"静置时长"直接当判据，结果是"在家坐了两小时，一开手电出门立刻收到已长时间静置"。现在必须 `max(staticStart, torchOnSince)`。

手电通知通道：`safewalk_torch`，ID `2003`，按钮文字随状态切换「打开/关闭手电」。

---

## 9. 车祸预警

| 项 | 值 |
|---|---|
| 位置 | SystemUI 进程 `CrashDetector` |
| 开关 | `masterEnable && enableCrashDetection`（默认关） |
| 滤波 | 低通 α=**0.8** 估重力，动态加速度 = `|a - g|` |
| 阈值 | 灵敏度 0/1/2 → **25 / 20 / 15 m/s²**（约 2.5g / 2.0g / 1.5g） |
| 触发 | 连续 **2** 个超阈值采样点（滤单点毛刺） |
| 冷却 | **60 秒** |
| 采样 | `SENSOR_DELAY_UI`（≈60ms），比 GAME 省电 |
| 出口 | 广播 `CRASH_DETECTED`（带 token）→ `CrashAlertReceiver` → `EmergencyAlertActivity` 全屏 + 倒计时 |

误报兜底在 App 端："我没事"按钮。开关变化通过 `configChangedReceiver` → `updateCrashDetection()` 即时生效，灵敏度档位由 lambda 每次回调读最新配置，不必重建监听器。

---

## 10. 配置链路

### 10.1 存储与传递

```
UI 改 SharedPreferences("safewalk_xsp")（App 私有）
  └─ SafeWalkConfig.exportConfig()
       ├─ 过滤 WHITELIST → 写 files/safewalk_config.json
       └─ 发 ACTION_CONFIG_CHANGED（setPackage com.android.systemui，带 token）
Hook 读：SafeWalkConfig.loadConfig(ctx)
  ① content://io.github.wumisc.safewalk.config/config  （主通道）
  ② ctx.filesDir/safewalk_config.json                  （同进程/兜底）
  ③ /data/data/<包名>/files/safewalk_config.json       （root 兜底）
```

- **为什么用 Provider 而不是 XSharedPreferences**：Hook 在 SystemUI 进程，读不到 App 私有目录；`MODE_WORLD_READABLE` 早已废弃。
- **为什么还要广播**：原来 Hook 每 10 秒跨进程查一次 Provider，会把已被系统回收的 App 进程反复拉起来——一个主打低功耗的模块自己在后台定时唤醒主人。现在写完就喊一声，轮询降级为 **60 秒**兜底（`refreshConfig` 缓存）。
- **读取在后台线程**：`refreshConfig()` 只是把任务丢给 `SafeWalk-config` 单线程池，主线程立刻返回。`contentResolver.query` 是同步 Binder，App 被回收时还会被冷启动，这个等待**绝不能**发生在 SystemUI 主线程上。
- 读完回主线程整体替换 `configJson`（`@Volatile` 引用，主线程只读、永不原地改），再重算 `updateCrashDetection()` / `updateOverlay()`。
- 读失败时**保留上一份配置**，不清空；缓存时间戳往前拨 10 秒，让下一次 tick 尽快重试。

### 10.2 安全

- `query` 只认 `/config` 路径；`isTrustedCaller()` **fail-closed**（判定不成立返回空，不是放行）。
- 判据用 **uid** 不用包名：`myUid` / `SYSTEM_UID(1000)`；少数 ROM 给 SystemUI 独立 uid 时，要求 `uid < FIRST_APPLICATION_UID` **且** `callingPackage == com.android.systemui`。普通应用（uid ≥ 10000）永远进不来。
- **白名单只出功能开关与显示参数**：急救卡的姓名/血型/住址/联系电话一律不导出，JSON 文件里也不带（root 一句 `cat` 就能看全）。
- **控制类广播加签名级权限**：`io.github.wumisc.safewalk.permission.CONTROL`（`protectionLevel="signature"`）。SystemUI 侧 6 个导出接收器统一用 `registerExportedWithPermission()` 注册，系统在**分发阶段**校验发送方签名——第三方即使反编译拿到令牌字符串也发不进来。源码里那个 `safewalk-bc-2.5` 只剩第二道校验作用。

### 10.3 配置键全表

| Key | 类型 | 默认 | 范围/取值 | 谁读 |
|---|---|---|---|---|
| `masterEnable` | bool | **true** | — | Hook |
| `enableBatteryWarning` | bool | true | — | Hook |
| `enableIdReminder` | bool | true | — | Hook |
| `enableSitHealthRemind` | bool | true | — | Hook |
| `enableSocialTipCard` | bool | true | — | Hook |
| `enableNightTorch` | bool | true | — | Hook |
| `enableNightTorchAuto` | bool | false | 需要 lux 传感器 | Hook |
| `nightTorchStartHour` | int | 22 | 0~23 | Hook |
| `nightTorchEndHour` | int | 6 | 0~23 | Hook |
| `stepThreshold` | int | **50** | UI 可调 | Hook |
| `staticResetSec` | int | **20** | 秒 | Hook |
| `torchStaticMinutes` | float | **12** | 分钟，≥1 | Hook |
| `enableCrashDetection` | bool | false | — | Hook |
| `crashSensitivity` | int | 1 | 0/1/2 | Hook |
| `travelMode` | string | "求职" | 求职/约会/聚会/旅游 | Hook |
| `enableRearCamera` | bool | false | — | Hook + App |
| `rearCamAuto` | bool | **false** | false=手动常显 | App |
| `rearCamMode` | string | "fullscreen" | fullscreen / smallwindow | App |
| `rearCamOpacity` | int | 50 | 10~100 | App |
| `rearCamCorner` | string | "bottom_right" | 四角 | App |
| `gaitConfirmSteps` | int | **5** | 2~10 | **仅 App** |
| `gaitStopSec` | int | **10** | 5~30 | **仅 App** |
| `enableDiagLog` | bool | true | — | Hook + App |
| `testRearCamAction` | string | "" | open_full/open_small/close | Hook |
| `testRearCamTs` | long | 0 | 一次性指令写入时刻 | Hook |
| `enableAutoTravelMode` | bool | false | 定位自动识别模式 | App |

两点要注意：

1. `gaitConfirmSteps` / `gaitStopSec` **不在白名单里**，因为它们是 App 侧服务直接读 SP 的，Hook 用不到。
2. `testRearCamAction` 是一次性指令，必须配 `testRearCamTs` 且有 **180 秒 TTL**（`TEST_ACTION_TTL_MS`）。没有 TTL 会出现"幽灵预览"：设置页调参数时 Hook 没激活，几天后 Hook 上线读到陈年指令，手机毫无征兆弹后视画面。TTL 必须 > Hook 的 60 秒配置缓存，取 3 倍余量。

---

## 11. 日志链路

### 11.1 出口

`DiagLog`（`object`，两进程共用）：

| 层 | 说明 |
|---|---|
| logcat | `adb logcat -s SafeWalk` 永远可用，排查首选 |
| 内存环形 | 400 行，供 `snapshot()` 回传给 App |
| 文件 | 512KB 轮转（超过就删了重建），写失败只报一次 |

### 11.2 落盘目录（探针写入，逐级降级）

| 优先级 | 目录 | 前提 |
|---|---|---|
| ① | `/storage/emulated/0/Android/Travel safely/diag.log` | 需要"所有文件访问"（`MANAGE_EXTERNAL_STORAGE`） |
| ② | `getExternalFilesDir(null)/logs/diag.log` | 无需特殊权限，但路径深 |
| ③ | `getDir("safewalk")/logs/diag.log` | 永远可写，只有 root 能看 |

**为什么不用 `File.canWrite()`**：Android 11+ 分区存储下它会**误报 true**（`Android/` 下非 data/obb/media 目录普通应用既不能建也不能写），于是目录被"选中"、真正写入抛 EACCES、又被 catch 吞掉——表现就是"日志永远是空的，连报错都没有"。现在真的写 1 字节临时文件再删，写得进去才算可用，并把每个候选目录的探测结果写进 logcat。

运行中拿到权限后会把落盘目录**切回**用户目录，并把降级期间的日志接过去（否则"我明明授权了还是没日志"）。

### 11.3 跨进程回传

SystemUI 的 `getExternalFilesDir()` 是 `Android/data/com.android.systemui/…`，用户根本找不到；SystemUI 也写不进 App 私有目录。所以：

```
App 启动 → 发 REQUEST_LOG（带 token）
Hook 收到 → 先补一条"现场"（总开关/状态/窗口步数/上一步多久前/计步来源/注册状态/累计）
         → 回 LOG_DUMP + 正文（snapshot()，上限 60KB，防 Binder 超 1MB）
App 的 LogBridgeReceiver → DiagLog.writeRaw() 落到同一个 diag.log
```

### 11.4 排查用的真实日志串

| 关键字 | 含义 | 没有它说明 |
|---|---|---|
| `===== Hook 加载到进程:` | LSPosed 把模块加载进了这个进程 | 模块没装好 / 没勾作用域 |
| `成功挂载至 SystemUI【系统界面】进程` | 包名匹配成功 | 作用域勾错了（不是 SystemUI） |
| `Hook SystemUIApplication.onCreate 成功` | Hook 点命中 | ROM 类名不同（见下面「锚点不存在」） |
| `[hook] 锚点已挂上: <名字>` | 该入口可用（三个锚点各自一行） | 三个锚点全都不见了 = 该 ROM 结构差异极大 |
| `[hook] 锚点不存在（该 ROM 无此入口）: <名字>` | 该类/方法在这台机器上不存在（不算错误） | — |
| `[hook] 锚点挂载失败: <名字> → 异常` | 挂载抛异常，但不是致命（还有别的锚点） | — |
| `[hook] 初始化触发源: <名字>` | **第一个**真正把模块拉起来的入口 | 有锚点但没有这行 = 锚点挂了却没被调用 |
| `[hook] 兜底通道拿到 Context（第 n 次；锚点命中 N 个）` | 靠反射轮询救活，N=0 说明三条锚点全废 | 这条出现 = 该 ROM 值得单独适配 |
| `[hook] ⚠️ 兜底轮询 20 秒仍拿不到 Context` | 四条路全废，模块无法启动 | — |
| `init 已执行过，跳过重复初始化` | 幂等生效 | — |
| `core init: 第 n 次尝试` | 核心步骤（Context + 日志）成功 | 只有下面的重试行 = Context 都拿不到 |
| `❌ 初始化失败（核心步骤，第 n 次）: …` | 核心步骤抛异常，将退避重试 | — |
| `↻ Ns 后重试初始化（已失败 n 次）` | 重试已排期（2s→60s 退避，最多 20 次） | — |
| `❌ 初始化步骤失败[名字]: …` | 某一步单独挂了，其余照常跑 | — |
| `❌ 注册广播失败[名字]: …` | 该接收器没注册上，下次 init 重试会补 | 对应功能失联 |
| `[cam] 前台检测启用第 n 级（接口名）` | 前台检测降级了，第 1 级在这台机器上不可用 | — |
| `[cam] ⚠️ 四级前台检测全部失败：相机避让失效` | 拿不到前台包名，选择不避让 | 这条出现 = 相机界面会挡取景 |
| `[cam] 前台是相机应用 <包名> → 让路收起预览` | 避让生效（按包名去重，进入时一条） | 在相机里悬浮窗不收 = 检测没识别出该包 |
| `✅ 计步器已注册:` | 系统计步器可用 | — |
| `✅ 加速度计计步已启用（原因：…）` | 兜底在跑 | 两路都没跑 = 永远没步数 |
| `🚶 计步中：窗口 x/50 步` | 每 15 秒一条，确实在计步 | 计数一直不涨 = 传感器/权限问题 |
| `📊 状态=… 窗口步数=x/50 上一步=Ns 前` | 每 2 分钟摘要 | tick 没转 |
| `❌ 启动 Ns 仍未收到任何步数事件` | 45 秒零事件的探针 | — |
| `🚶 步数达标（窗口 x 步 / 阈值 50），直接触发` | 阈值判定通过 | 走够了没这条 = 冷却中或 tick 卡住 |
| `✅ 判定为持续步行（窗口 n 步）→ 已发提醒：…` | 触发成功的唯一证据 | 有"步数达标"无这条 = 提醒开关全关 |
| `🔍 步态复核：峰数=… 步频=… 稳定=…` | 事后旁证 | 不影响提醒 |
| `[walk] 已拉起透明自启页` | 开机自启链路发起 | 重启后要手动拨开关 |
| `[walk] 拉起自启页失败，稍后重试: …` | ROM 拦了后台 Activity 启动 | 60 秒后重试，一直失败要换兜底方案 |
| `[walk] AutoStart：已请求启动预览服务` | 透明页代启成功 | — |
| `[gait] 累计步 n/5 峰数=… 阈值=… 包络=… 姿态=…` | 自动模式每 2 秒的判定现场 | 自动模式不动 |
| `[walk] 检测到步行 → 显示预览` | 自动模式判走成功 | — |
| `[walk] 静置超时，判定停下 → 收起预览` | 判停成功 | — |
| `[gait] 加速度像走但硬件计步 n/2（10s内）→ 疑似拿手机乱动` | 被硬件计步挡住 | — |
| `[cam] ❌ 6000ms 无画面 \| mode=… cam=… session=…` | 黑屏，带现场参数 | — |
| `[cam] ❌ 预览失败: 应用没有相机权限` | 权限没给 | — |
| `[torch] ✅ 已开启手电（第 n 次尝试）` | 点灯成功 | — |
| `[torch] 摄像头仍被占用（…），Nms 后第 n 次尝试` | 预览/别的应用占着相机 | — |
| `[lux] 环境光监测已启动` / `夜间暗光(…lux < 15) → 自动开灯` | 自动开灯生效 | — |
| `[crash] 疑似撞击 magnitude=…` | 撞击判定 | — |

---

## 12. 线程、传感器与耗电

### 12.1 线程分布

| 位置 | 线程 | 备注 |
|---|---|---|
| `init` / `tickRunnable` / `check` / `updateOverlay` | SystemUI 主线程 | 全部 try-catch，异常只写日志 |
| `refreshConfig` → `contentResolver.query` | **SystemUI 主线程** | 跨进程，会冷启动 App 进程（§13 P0-2） |
| `DiagLog.appendLine` | 调用方线程（多为主线程） | 同步 `FileOutputStream` |
| 各类 `postDelayed` 重试（手电、看门狗、会话超时） | 主线程 | — |
| `WalkPreviewService.gaitTick` | App 主线程 | 每秒一次 |
| 传感器回调 | Android SensorThread / 主线程注册后由系统投递 | `feedAcc` 是纯计算，无 I/O |

### 12.2 传感器占用

| 传感器 | 采样 | 何时 |
|---|---|---|
| `TYPE_STEP_DETECTOR` | `SENSOR_DELAY_NORMAL` | 注册成功后常驻 |
| `TYPE_ACCELEROMETER`（Hook，计步） | `SENSOR_DELAY_UI` | **常驻**（总开关关时会停） |
| `TYPE_ACCELEROMETER`（Hook，步态复核） | `SENSOR_DELAY_GAME` | 每次触发后 4 秒 |
| `TYPE_ACCELEROMETER`（Hook，车祸） | `SENSOR_DELAY_UI` | 车祸开关开着时 |
| `TYPE_ACCELEROMETER`（App，自动模式） | `SENSOR_DELAY_UI` | 自动模式 + 行走模式开着 |
| `TYPE_STEP_COUNTER`（App） | `SENSOR_DELAY_UI` | 自动模式，硬件计步确认 |
| `TYPE_LIGHT` | `SENSOR_DELAY_NORMAL` | 常驻（功耗极低） |

### 12.3 省电手段（真实存在的）

1. 总开关关 → tick 降到 5 秒、停加速度计兜底。
2. 配置缓存 60 秒 + 变更广播，避免每 tick 跨进程。
3. 悬浮窗指令"只发变化"，稳态下零广播。
4. 手电点灯后 60 秒内不拉预览，避免相机反复开关。
5. 步态队列、日志文件都有上限（10 个间隔 / 512KB / 400 行环形）。
6. 日志可关（`enableDiagLog`），关掉后 `log()` 直接 return，不写文件不做缓冲。

> **注意**：代码里**没有**"息屏全停"。Hook 没有注册 `ACTION_SCREEN_OFF`，`check()` 也不看屏幕状态。息屏后加速度计计步、步态复核、车祸监听照常跑（`SENSOR_DELAY_UI`）。v2.1 文档里"息屏 = 全停、待机耗电有保障"的描述与现状不符，若要实现需新增 `SCREEN_OFF` 接收器统一 suspend/resume。

---

## 13. 已知问题（按严重度，供后续维护）

> **2026-11-08 已修掉三条 P0、两条 P1 与一条 P2**（见 13.1）。13.2 是仍然存在的，修之前不要以为"文档写了就是已解决"。

### 13.1 已修复（2026-11-08）

| 原编号 | 问题 | 修法 | 落点 |
|---|---|---|---|
| **P0-1** | `inited = true` 写在 `try` **之前**，后续任何一步抛异常都只 log 不重试，该进程永久停在"已初始化"的假状态 | 拆成 `initRunning` / `initSucceeded` 两个标志；核心步骤失败走**退避重试**（2s→4s→…→封顶 60s，最多 20 次）；其余步骤用 `step("名字"){}` 逐段兜底，单步失败只损失那一步 | `SafeWalkLSPHook.kt` `runInit()` / `step()` / `scheduleInitRetry()` |
| **P0-2** | 主线程跨进程 `query` Provider，App 被回收时会把 App 冷启动，主线程等 Binder 数百 ms~数秒 → 掉帧甚至 ANR | 配置读取全部挪到单线程池 `SafeWalk-config`（daemon）；`configJson` 改成 `@Volatile` 快照，**只在后台整体替换、主线程只读引用**；读完回主线程再重算 `updateCrashDetection` / `updateOverlay`；`configLoading` 去重，失败保留旧快照并 10 秒后重试 | `refreshConfig()` / `configExecutor` |
| **P0-3** | 广播令牌硬编码 + 接收器全 exported：反编译拿到 token 就能反复发 `RESTART_SYSTEMUI` 杀 SystemUI（黑屏 DoS）、静默点灯、刷配置 | 新增 **signature 级权限** `io.github.wumisc.safewalk.permission.CONTROL`；SystemUI 侧 6 个导出接收器统一走 `registerExportedWithPermission()`，**系统在分发阶段就校验发送方签名**，第三方广播根本到不了代码。令牌保留为第二道校验 | `AndroidManifest.xml`、`ReceiverCompat.registerExportedWithPermission()`、`registerOnce()` |
| **P1-1** | Hook 单点、零兜底：只挂 `SystemUIApplication.onCreate`，OEM 改类名/启动点就整模块静默失效 | 改成**四层入口并联**：① `SystemUIApplication.onCreate` ② `Instrumentation.callApplicationOnCreate`（不认类名） ③ `SystemUIService.onCreate` ④ 守护线程反射 `ActivityThread.currentApplication()` 轮询兜底。全部落到幂等的 `triggerInit()`，第一个命中的来源写日志 | `handleLoadPackage()` / `hookAnchor()` / `triggerInit()` / `startContextFallback()`（§2.1） |
| **P1-2** | `cameraInForeground` 只有一级反射，抛异常被吞成 `false` → 相机避让失效、悬浮窗挡取景，且日志无痕 | 改成**四级降级检测**（`getFocusedRootTaskInfo` → `ActivityTaskManager.getTasks` → `getRunningTasks` → `UsageStatsManager`），记住上次成功的层级、换级写日志；四级全失败时**不避让 + 一次性告警**；相机包名清单从 9 个扩到 22 个 | `foregroundPackage()` 及 4 个 `pkgFrom*`（§6.2） |
| **P2-3** | `enableDiagLog` 不在 `ConfigProvider.WHITELIST`，Hook 侧永远读默认 `true` → 关掉日志开关后 SystemUI 侧仍在写 | 加入白名单（它只是开关，不含隐私），`exportConfig` 会自动带上 | `ConfigProvider.kt` `WHITELIST` |

**顺带清理**：`RearCameraOverlay.isCameraAppInForeground()` 与它的 `CAMERA_PACKAGES` 已删除——
相机搬到 `WalkPreviewService` 后这份实现**没有任何调用方**，留着只会和 Hook 侧的新逻辑各自漂移。

**P0-3 的一个边界**：这套权限只能护住 **App → SystemUI** 方向（发送方是模块自己，持有签名权限）。反方向 **SystemUI → App** 的广播（预览状态回执、日志回传、重启 ACK）发送方是系统界面，它拿不到我们的签名权限，所以 App 侧的接收器**仍然只能靠令牌**。被伪造的后果限于"让悬浮窗显隐错乱 / 往日志里灌文本"，不涉及杀进程或开摄像头。

### 13.2 仍然存在

| 级别 | 问题 | 位置 | 后果 | 建议改法 |
|---|---|---|---|---|
| **P2-1** | 加速度计常开 | `:400`、`:649` | 与 v2.1"平均 <1Hz"的说法矛盾，功耗需实测 | 若实测偏高，考虑息屏降采样或只在候选窗口开 |
| **P2-2** | `DiagLog.appendLine` 同步写文件在调用方线程 | `DiagLog.kt:207` | tick 里写日志会有主线程 I/O 抖动 | 改成单线程串行队列 + 批量 flush |
| **P2-3** | App 侧接收器仍只能靠令牌（反方向 SystemUI → App 不受签名权限保护） | `CrashAlertReceiver` / `LogBridgeReceiver` / `TorchActionReceiver` 等 | 第三方拿到令牌可伪造"预览状态回执 / 日志文本 / 点灯请求" | 若要彻底解决，需 per-install 随机令牌（Hook 从导出的配置里读），改动面 20+ 处 |
| **P2-4** | 久坐依赖 `lastStepTs != 0` | `:944` | 开机后没走过一次就永不提醒久坐 | 可接受（有意保守），但要在 UI 说明 |
| **P2-5** | `walking` 只写不读 | `:40`、`:559`、`:770`、`:780`、`:1106` | 死变量，容易误导后续维护者以为它在控制显隐 | 删除，或改成只用于日志 |
| **P2-6** | `minSdk 33` 让 `ReceiverCompat` 的 API<33 分支成死代码 | `ReceiverCompat.kt` | 无害，但读代码时会以为在兼容 11/12 | 保留（防降 minSdk），注释说明 |

**P1 修复的验证要点**（真机上看日志即可判断走的是哪条路）：

| 日志 | 含义 |
|---|---|
| `[hook] 锚点已挂上: SystemUIApplication.onCreate` | 正常机型，主入口可用 |
| `[hook] 初始化触发源: Instrumentation.callApplicationOnCreate` | OEM 改了 Application 类名，靠锚点 2 兜住 |
| `[hook] 兜底通道拿到 Context（第 N 次；锚点命中 0 个）` | 三条锚点全部失效，靠反射轮询救活——**说明该 ROM 值得单独适配** |
| `[hook] ⚠️ 兜底轮询 20 秒仍拿不到 Context` | 四条路全废，模块无法启动 |
| `[cam] 前台检测启用第 N 级（接口名）` | 前台检测降级了，第 1 级在这台机器上不可用 |
| `[cam] ⚠️ 四级前台检测全部失败` | 相机避让失效（悬浮窗在相机界面不收起） |

---

## 14. 与 v2.1 文档的差异（旧文档不要再引用）

| v2.1 说 | 现状（2.10.2） |
|---|---|
| 唯一通道是 `Download/safewalk.conf` | 没有这个文件。通道是 `ConfigProvider` + 广播 + `files/safewalk_config.json` |
| 全部功能跑在 SystemUI 单进程，App 只改配置 | **两进程**：相机/悬浮窗在 App 的 `WalkPreviewService`（camera FGS） |
| `SystemUIApplication.onCreate` + 反射 `mInitialApplication` 12 秒兜底 | **只有** `onCreate` 一个入口，**没有任何兜底** |
| 状态机 SLEEP → LIGHT_MONITOR → VERIFYING → TRIGGERED | 只有 `SLEEP` / `LIGHT_MONITOR`，VERIFYING 已删（2.9.4） |
| 步数阈值 150 步 | **50 步**（UI 可调） |
| 5 秒采样一次步数窗口 | tick 2 秒（开）/ 5 秒（关），窗口是 5 分钟滑动 |
| 静置 20 秒 → 回 SLEEP 并清空窗口 | 静置 20 秒 → `walking=false` + `state=SLEEP`，**不清窗口**（交给滑动窗口过期） |
| 自适应窗口 60 样本，阈值 = 均值 + 1.8σ | 包络阈值 = 时间衰减包络 × 0.6（1.8σ 在小信号下自相矛盾） |
| 步频 1.4~2.2 Hz | **0.5~2.5 Hz** |
| 步间隔稳定度 ±180ms 才算稳定步态 | `hasStableGait` 容差 400ms，且**只用于日志**，不决定提醒 |
| 平躺 `|gz| > 8.5`、权重 ×1.45 | 一致（这两条是对的） |
| 相机避让"未实现，90 秒冷却" | 已实现：反射查前台 + **5 秒节流**，无 90 秒冷却 |
| 手电 22:00~07:00 + 连续 30 秒 + 距上次 10 分钟 + 悬浮窗没开 | 夜间时段可配（默认 22~6）+ 亮屏 + 预览未显示；无"连续 30 秒"，冷却来自通知 60 秒 + 触发 5 分钟 |
| 安全提醒"最多驻留 2 条通知" | 每个 ID 独立 60 秒冷却，无"最多 2 条"限制 |
| 息屏 = 全停（停 tick、模块全 disable、隐藏悬浮窗） | **不存在**。没有 `SCREEN_OFF` 接收器，息屏照常计步与监听 |
| 日志三路径含 `Download/safewalk_diag.log` | `Android/Travel safely/diag.log` → `getExternalFilesDir/logs` → 私有 `logs`，且 Hook 侧靠**回传**给 App 落盘 |
| 作用域必须勾 `android` | **`com.android.systemui`**（`xposedscope` 元数据已声明） |
| `minSdk 30`，targetSdk 35 | `minSdk 33`，`targetSdk 35`，`compileSdk 37` |
| XSharedPreferences 废弃警告 | 已不用 XSharedPreferences，改 Provider + JSON |

---

## 15. 模块 → 文件对照表

| 文件 | 职责 |
|---|---|
| `SafeWalkLSPHook.kt`（1193 行） | Hook 入口、init、tick、`check`、`updateOverlay`、手电兜底、车祸开关、让路判定、开机自启触发 |
| `GaitDetector.kt`（384） | 步态算法 + `MotionConfig` 参数 |
| `WalkPreviewService.java`（601） | camera FGS：自动模式步态、显隐决策、相机与窗口生命周期 |
| `RearCameraOverlay.kt`（745） | Camera2 预览 + `TYPE_APPLICATION_OVERLAY` 窗口 + 看门狗 |
| `AutoStartActivity.kt`（92） | 透明中转页，代启预览服务（绕过 camera FGS 后台启动限制） |
| `TorchController.kt` / `torch/*` | 手电控制、TorchCallback、静置提醒、通知通道 |
| `CrashDetector.kt` / `CrashAlertReceiver.java` / `EmergencyAlertActivity.java` | 撞击检测 → 广播 → 全屏急救卡 |
| `ConfigProvider.kt` | 只读 ContentProvider + uid 白名单 |
| `SafeWalkDataStore.kt` | 配置 Key 常量、导出 JSON、读取降级 |
| `DiagLog.kt` | 双通道日志、探针落盘、环形缓冲、回传快照 |
| `SafePrefs.java` | 类型安全读 SP（历史 int↔float 变更不崩） |
| `SafeWalkNotificationManager.kt` | 6 类通知 + 按 ID 冷却 + 超级岛 extras |
| `LocationModeEngine.kt` | 定位 → 地址 → 关键词投票 → 出行模式建议 |
| `ReceiverCompat.kt` | API 33 `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` 兼容封装 |
| `MainActivity` / `WalkModeActivity` / `SmartReminderActivity` / `LabActivity` / `EmergencyCardActivity` / `TipsActivity` | UI 与实验区（步态自检、日志 tail、重启 SystemUI） |

---

## 16. 出问题按这个顺序查

1. **模块有没有进来**：`Download/…/diag.log` 或 `adb logcat -s SafeWalk` 里找 `成功挂载至 SystemUI`。
2. **init 有没有跑完**：找 `SystemUIApplication.onCreate 触发` 和之后的 `❌ 初始化失败`。
3. **有没有在计步**：看 `🚶 计步中` / `📊 状态=` 这两条是否周期性出现、窗口步数是否在涨。
4. **有没有触发**：`🚶 步数达标` → `✅ 判定为持续步行 → 已发提醒`。只有前者没有后者 = 三个提醒开关都关着。
5. **悬浮窗不出**：先看 `enableRearCamera` 是否 true，再看有没有 `[walk] 已拉起透明自启页`，再看 `[gait]`（自动模式）或 `[cam]`（相机链路）。
6. **手电不亮**：找 `[torch] 摄像头仍被占用` → 说明预览/别的应用占着相机；`[torch] ❌ …失败` 后面跟的 `reason` 是真实原因。
7. **日志本身没有**：看 logcat 里的 `日志目录探测:` 一行，三个候选路径哪个是 ✓。

---

## 17. 适配与部署注意事项

| 项 | 值 |
|---|---|
| 作用域 | **只勾 `com.android.systemui`**，不要勾系统框架（`android`） |
| 运行时权限 | `ACTIVITY_RECOGNITION`（计步）、`POST_NOTIFICATIONS`、`CAMERA`、`SYSTEM_ALERT_WINDOW`（悬浮窗）、`FOREGROUND_SERVICE_CAMERA` |
| 特殊权限 | 「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`）才能把日志写到 `Android/Travel safely` |
| 车祸预警额外权限 | `CALL_PHONE` / `SEND_SMS` / `ACCESS_FINE_LOCATION` / `USE_FULL_SCREEN_INTENT`（按需申请） |
| Android 版本 | `minSdk 33` / `targetSdk 35` / `compileSdk 37` |
| 构建 | AGP9 + Java 21 bytecode，Kotlin 2.4.21 compose 插件；`compileOnly de.robv.android.xposed:api:82` |
| 单元测试 | `app/src/test/` 9 个类（步态、崩溃、日志、通知落点、预览窗口、稳定性、主流程、子页） |

> 本文档不覆盖 UI 层的交互细节（设置页布局、液态玻璃导航等）。那些以 `MainActivity` / `WalkModeActivity` / `LabActivity` 为准，改动频率高，写进文档只会再次过时。
