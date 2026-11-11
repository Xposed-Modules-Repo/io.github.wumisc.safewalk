# 真机日志诊断与修复报告（2026-11-11 19:05~19:11）

> 设备：Redmi M332BF / HyperOS 4（OS4.0.8.0.XHPCNXM，V816，SDK 37）
> 输入：`粘贴内容_20261111_192906.txt`（443 行，App 进程 + SystemUI 进程回传混合）
> 结论：**3 个确凿缺陷已修复；日志里最扎眼的「抑制激活」不是 bug，是防误触正确工作，未改动。**

## 一、时间线还原

| 时刻 | 事件 | 判定 |
| --- | --- | --- |
| 18:39:19 | Hook 加载进 SystemUI（pid 28047）；`TYPE_STEP_DETECTOR` 注册被拒 → 加速度计兜底启用 | 预期行为：SystemUI 无 `ACTIVITY_RECOGNITION` 权限 |
| 18:39~19:02 | 兜底计步正常出数，19:02:52 窗口 50/50 达标触发「判定为持续步行」累计 119 步 | ✅ 计步与提醒链路正常 |
| 19:05:49 | 首次日志索取，回传 6674 字符 | 正常 |
| **19:06:39** | **SystemUI 进程重启**（新 pid 22010），19:06:47 `core init` 重新执行、状态清零 | 关键事件 |
| **19:06:49** | `[walk] 已拉起透明自启页` → `AutoStart：已请求启动预览服务` → `[FGS] 前台服务已提起｜type=camera` | ✅ **4.4.0 保活复活链路真的生效了** |
| **19:06:57** | `[walk] 静置超时，判定停下 → 收起预览` | ❌ 复活成功仅 8 秒后，预览又被收掉 |
| 19:07:33 / 19:08:19 | `❌ 启动 46s / 92s 仍未收到任何步数事件 … 先到步态自检确认传感器有没有数据` | ❌ 误报 + 45s 复刷 |
| **19:11:38** | 同一秒内 `[gait] ⛔ 硬件计步器 10 秒 0 步` 刷出 **80+ 条**（第 314~396、402~443 行） | ❌ 日志洪泛，文件尾部断在洪泛里 |
| 19:05~19:11 | `[GUARD]` / `[ACTIVE]` 抑制激活：姿态 TILTED/LAY_FLAT、节律稳定=false、硬件计步 10s=0、步频 0.5~2.5Hz 杂乱 | ✅ **防沙发/床上误触按设计工作，不是缺陷** |

## 二、三个缺陷与修法

### P0 · 日志洪泛（`WalkPreviewService`）

`hardwareStepConfirm()` 在 `onSensorChanged`（`SENSOR_DELAY_UI`，每秒几十帧）里被每帧调用；
窗口满格后 `(stepTick || countReached)` 近乎恒真，而 `n == 0` 分支直接 `DiagLog.log(...)`
**没有任何节流**。同文件 `[STEP]` 10s、`[ACTIVE]` 2s、`[GUARD]` 3s 都有节流，唯独这里漏了。

后果不只是难读：`DiagLog` 文件超过 512KB 会整文件删、内存环形缓冲只有 400 行，
洪泛会把 19:05~19:11 的真实现场全部冲掉 —— 这份日志正是断尾在洪泛中，
用户最需要排查的那段反而丢了。

**修法**：判定与 `return` **一字不动**，只把日志换成统一节流出口。

### P1 · 步数探针误报（`SafeWalkLSPHook`）

旧条件 `stepEventCount == 0 && now - initTs > 45s`，但 `stepEventCount` 只在
`recordStep()`（合格步）自增。传感器每帧都在回调、只是没凑成合格步（静止/持握）
也会被报成「传感器静默失效」，还引导用户去步态自检；且每 45 秒复刷。

更关键的是：此时 `系统计步器注册=false 加速度计兜底=true`，注册被拒是
**SystemUI 权限模型决定的预期行为**，兜底明明在跑，却被当成故障。

**修法**：新增 `rawSensorCallbackCount`（原始回调计数），用 `StepProbePolicy` 分级：
- 有原始回调、无合格步 → `SENSOR_ALIVE_NO_STEP`，打一条 ℹ️ 中性提示，不报 ❌；
- 连原始回调都没有 → `SENSOR_SILENT`，才报 ❌ 并引导自检；
- 每个启动会话只报一次（`stepProbeReported`），不再 46s/92s 复刷。

### P1 · 复活即收起（`WalkPreviewService`）

`gaitTick` 只看 `lastStepTs != 0 && now - lastStepTs > stopMs`。服务被保活重新拉起时，
`startGait()` 因 `gaitListener != null` 直接 return，**不重置 `lastStepTs`** ——
沿用上一会话残留的时间戳，于是 19:06:49 刚复活、19:06:57 就被判「静置超时」把预览收掉。
用户看到的正是上一轮反馈的「通知消失，就证明行走模式停止运行了」。

**修法**：引入会话基线 `sessionStartTs`（服务实例创建 / 裸启动复活时刷新），
判定交给 `StaticStopPolicy`：
- 旧会话残留的 `lastStepTs < sessionStartTs` 一律作废；
- 本会话给 30s 宽限，宽限过后才允许收起一次空预览（避免永远挂空窗）；
- 本会话走出合格步后，回到正常 `stopMs` 静置判停；
- 时钟回拨一律不判停。

⚠️ **只在裸启动（`action == null`）刷新基线**：`WalkMode` 里拖静置秒数滑杆会高频
`send(ACTION_SHOW)`，如果每次都续期 30s 宽限，预览将永远不收起。

## 三、代码结构优化

| 之前 | 之后 |
| --- | --- |
| 6 个 `lastXxxDiagTs` 字段散落在 `WalkPreviewService`，每个调用点自己写节流 | `DiagThrottle`（纯逻辑）+ `DiagLog.logThrottled(key, reason, msg)` 按 key 统一收口，业务类不再持有时间戳 |
| 探针/判停条件与日志、时间戳揉在业务方法里 | 抽 `StepProbePolicy` / `StaticStopPolicy` / `DiagThrottle` 三个纯逻辑类，各自带 JVM 单测 |
| `DiagLog.appendLine()` 无同步，`writeRaw()` 有 —— 多线程下行可能交错 | 写文件统一用 `ringLock`，与环形缓冲、回传同一把锁 |

新增类均沿用项目既有的 `XxxPolicy` 风格（纯逻辑、不依赖 `android.*`、可跑 JVM 单测）。

## 四、明确**没有**动的东西（防回归）

经安卓端成员独立复核，以下内容在日志里是**正确行为**，本轮一行未改，避免沙发误触回归：

- `hardwareStepConfirm()` 的全部 `return` 分支与判据（含 `n == 0` 硬拦、`STEP_CONFIRM_MIN=2`）；
- `accelOnlyConfirm()`、`WalkConfirmPolicy.evaluate()` 三道闸；
- `[GUARD]` 的 `credible` 三段判定、`LAY_FLAT` 一票否决；
- `GaitDetector.feedAcc()`、10 步窗口上限、`ACCEL_MIN_STREAK_MS`；
- Hook 侧 `check():1005` 的静置判定、50 步窗口、跨进程显隐分工。

> 日志中「两套计步状态」（SystemUI 报「持续步行」、App 报 `walking=false`）是
> 既定分工：Hook 的 50 步窗口只驱动提醒/久坐，显隐已迁到 App 侧。合并需要跨进程
> 状态同步，风险大于收益，本轮不动。

## 五、诚实的边界

- 无真机验证：以上修复靠日志归因 + 全量单测 + 编译/lint 保证，仍需用户实机复测。
- 洪泛修复后，`diag.log` 不再被冲爆，但**已丢失的 19:05~19:11 现场无法找回**。
- 「复活即收起」修复只保证复活后 30s 内不因旧时间戳误收；若用户真的一直没走路，
  30s 后仍会收起空预览（这是预期）。
- 探针改为「每会话只报一次」后，若传感器在会话中途才失效，不会再补报 ——
  这类场景由「兜底未运行，自动恢复」与 2 分钟状态摘要覆盖。
