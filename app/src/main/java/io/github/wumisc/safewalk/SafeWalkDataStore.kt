package io.github.wumisc.safewalk

import android.content.Context
import org.json.JSONObject

/**
 * 配置 Key 常量。
 * 新版方案：UI端写入 SharedPreferences（私有），同时导出成 JSON 文件到 files/ 目录。
 * Hook端直接读 JSON 文件，不需要 XSharedPreferences，不需要 MODE_WORLD_READABLE。
 */
object SafeWalkPrefKey {
    // 总开关
    const val masterEnable = "masterEnable"

    // 5个功能开关
    const val enableBatteryWarning = "enableBatteryWarning"    // 电量预警
    const val enableIdReminder = "enableIdReminder"          // 证件提醒
    const val enableSitHealthRemind = "enableSitHealthRemind" // 久坐提醒
    const val enableSocialTipCard = "enableSocialTipCard"    // 社交礼仪提示
    const val enableNightTorch = "enableNightTorch"          // 夜间手电提醒
    /** 夜间时段起点(小时 0-23)，默认 22 点 */
    const val nightTorchStartHour = "nightTorchStartHour"
    /** 夜间时段终点(小时 0-23)，默认 6 点 */
    const val nightTorchEndHour = "nightTorchEndHour"
    /** 诊断日志总开关：关掉后不再写日志文件，省 I/O 省电。默认开 */
    const val enableDiagLog = "enableDiagLog"
    /** 步态确认步数：连续走够这么多步才弹预览（2~10），默认 5 */
    const val gaitConfirmSteps = "gaitConfirmSteps"
    /** 停下后多少秒收起预览（5~30），默认 10 */
    const val gaitStopSec = "gaitStopSec"
    /** 基于定位自动识别出行模式：开启后不再手动选，由定位地点自动切换。默认关 */
    const val enableAutoTravelMode = "enableAutoTravelMode"

    // ===== 行走模式 =====
    /** 行走模式总开关：走路时用后置摄像头看路况。 */
    const val enableRearCamera = "enableRearCamera"
    /** 行走模式形态："fullscreen" 全屏半透明 / "smallwindow" 角落小窗。 */
    const val rearCamMode = "rearCamMode"
    /** 行走模式自动模式：true=走起来自动开、停下自动收；false=手动开关。 */
    const val rearCamAuto = "rearCamAuto"
    /** 行走模式透明度：0~100，值越大越不透明。 */
    const val rearCamOpacity = "rearCamOpacity"
    /** 小窗方向："bottom_right" / "bottom_left" / "top_right" / "top_left"。 */
    const val rearCamCorner = "rearCamCorner"

    // 出行模式
    const val travelMode = "travelMode"  // 求职/约会/聚会/旅游

    // 步态参数
    const val stepThreshold = "stepThreshold"    // 触发步数阈值
    const val staticResetSec = "staticResetSec"  // 静置多少秒重置

    // ===== 手电筒静置提醒 =====
    /** 手电静置提醒时间（分钟）：手电开着+静置多久后弹提醒 */
    const val torchStaticMinutes = "torchStaticMinutes"

    // ===== 行走模式测试指令（实验区用，跨进程） =====
    /** 行走模式测试动作：open_full / open_small / close / ""（空=无动作） */
    const val testRearCamAction = "testRearCamAction"

    /**
     * 一次性指令的写入时刻。
     *
     * 没有它就会出现「幽灵预览」：用户在设置页调了一次参数，但当时 Hook 没激活
     * （LSPosed 没勾作用域）；几天后 Hook 上线，轮询读到这条陈年指令，
     * 手机上毫无征兆地弹出后置摄像头画面。
     * Hook 端现在会丢弃超过 [TEST_ACTION_TTL_MS] 的指令。
     */
    const val testRearCamTs = "testRearCamTs"

    /**
     * 一次性指令的有效期。
     *
     * 必须**大于** Hook 端配置缓存的 60 秒：广播被 ROM 拦掉时，指令只能等
     * tick 兜底读取，而 tick 读的是缓存配置。有效期若是 60 秒，会出现
     * 「指令刚写进去就已经过期」的死角，预览照样点不动。取 3 倍余量。
     */
    const val TEST_ACTION_TTL_MS = 180_000L

    // 急救卡
    const val emerName = "emerName"
    const val emerBlood = "emerBlood"
    const val emerAddress = "emerAddress"
    const val emerContactName = "emerContactName"
    const val emerContactPhone = "emerContactPhone"

    // ===== 车祸预警（急救卡，Pixel 风格） =====
    /**
     * 车祸预警总开关：SystemUI 进程持续监测加速度，检测到撞击后
     * 广播给 App 弹全屏急救卡 + 倒计时，超时未响应自动求助。
     */
    const val enableCrashDetection = "enableCrashDetection"
    /**
     * 撞击灵敏度：0=低(约2.5g) 1=中(约2.0g，默认) 2=高(约1.5g)。
     * 值越小越不敏感，误报越少；值越大越敏感，轻微碰撞也会触发。
     */
    const val crashSensitivity = "crashSensitivity"

    // ==================================================================================
    // 【本次全量迭代新增】BUG 修复 / 新增功能 / HyperOS3-4 双版本适配 三类开关
    // 全部为纯开关或显示参数，不含隐私字段，因此都必须同步加入
    // [ConfigProvider.WHITELIST]，否则 Hook 侧（SystemUI 进程）读不到、永远拿默认值。
    // ==================================================================================

    /**
     * 【BUG 修复 · 联动对象纠正】「夜间手电 · 角落小窗联动闪光灯」总开关，默认关。
     *
     * 用户澄清：「闪光灯是跟角落小窗一起打开，联动的是角落小窗」。
     * 需求语义：在 [nightTorchStartHour]~[nightTorchEndHour] 的夜间时段内，
     * **角落小窗（后视预览）正在显示时**闪光灯一起常亮；小窗收起则熄灭。
     * 不再与 SystemUI 侧 walking 步态挂钩 —— 躺床上手机抖动会被误判成走路，
     * 正是最新真机日志「bug1 闪光灯在床上自动打开」的根因。
     *
     * 实现：WalkPreviewService.doShow() → RearCameraOverlay 预览会话内
     * CaptureRequest.FLASH_MODE_TORCH（与预览共用同一颗摄像头，不抢设备）。
     * key 名沿用历史值以避免破坏 ConfigProvider 白名单与已有用户配置。
     *
     * 与既有开关彼此独立、互不覆盖：
     *  · [enableNightTorch] —— 只弹「建议开手电」通知，不点灯（保留原行为）；
     *  · 本开关            —— 只认「时间区间 + 角落小窗显示」，是夜间手电的唯一开关
     *    （原「夜间自动开灯」lux 暗光独立点灯路径已合并删除，二者本为同一功能）。
     */
    const val enableNightTorchGait = "enableNightTorchGait"

    /**
     * 【新增对比度 UI 模块】增强对比度独立开关，默认关。
     *
     * 开启后：悬浮窗在相机画面层之上叠加一层 HUD 文字层，文字带黑色外描边 +
     * 阴影 + 半透明深色衬底；原有 [rearCamOpacity] 透明度**只继续作用于画面层**，
     * 不作用于文字层 —— 于是「画面调淡看路况」和「文字始终清晰」可以同时成立。
     */
    const val enablePreviewContrast = "enablePreviewContrast"

    /**
     * 【对比度强度进度条】增强对比度的强度，Int 0~100，默认 50。
     *
     * 与 [enablePreviewContrast] 配合：开关是总闸，本值是强度。强度越高 →
     * HUD 文字层的黑色外描边越粗、阴影越大、深色衬底越不透明；强度越低 →
     * 越接近原始画面。无论取何值都不影响 [rearCamOpacity] 对画面层的透明度作用，
     * 也不改相机采集链路。必须同步加入 ConfigProvider.WHITELIST，否则导出的
     * JSON 配置里会丢掉这一项。
     */
    const val previewContrastLevel = "previewContrastLevel"

    /**
     * 【HyperOS 版本适配模块】适配模式串，默认 [HyperOsCompat.MODE_AUTO]。
     *
     * 合法值：auto（方案A，自动识别）/ hos3 / hos4（方案B，手动强制兜底）。
     * 由 [HyperOsCompat.resolve] 统一解析；写进白名单让 SystemUI 侧的 Hook
     * 也能读到同一份结论，两个进程的版本判断不会打架。
     */
    const val hyperOsMode = "hyperOsMode"

    /** 配置文件名，放在 files/ 目录下，Hook端直接读这个文件 */
    const val CONFIG_FILE = "safewalk_config.json"

    /**
     * 配置变更广播（App → SystemUI 里的 Hook）。
     *
     * 之前 Hook 端每 10 秒跨进程查一次 ConfigProvider，而查 Provider 会把
     * 已经不存在的应用进程重新拉起来——一个主打「低功耗」的模块，
     * 自己在后台每 10 秒唤醒一次主人，说不过去。
     * 改成写完配置主动喊一声，Hook 收到立即刷新；轮询降级为 60 秒兜底。
     */
    const val ACTION_CONFIG_CHANGED = "io.github.wumisc.safewalk.CONFIG_CHANGED"

    /**
     * 广播令牌。
     *
     * 接收器注册在 SystemUI 里，为了跨进程就必须是 EXPORTED，
     * 也就意味着**任何应用**都能往这个 action 发广播。没有令牌的话：
     *   · 谁都能发 PREVIEW_CAM，让 SystemUI 静默打开后置摄像头（这是偷拍）；
     *   · 谁都能发 CONFIG_CHANGED，让 SystemUI 反复跨进程查配置（耗电）。
     * 令牌挡不住反编译 APK 的人，但能挡掉「随手写个 Intent 就能用」。
     */
    const val EXTRA_BROADCAST_TOKEN = "token"
    const val BROADCAST_TOKEN = "safewalk-bc-2.5"
}

/**
 * 新版配置读写：UI端写入 + 导出JSON，Hook端读JSON。
 * 不需要 XSharedPreferences，不需要 MODE_WORLD_READABLE。
 */
object SafeWalkConfig {

    /** UI端：写入配置并导出成JSON文件 */
    @JvmStatic
    fun exportConfig(ctx: Context) {
        // 导出失败不能让调用方崩：这里几乎全是点击回调 / onPause，
        // 存储满、目录被回收都会抛 IOException，以前直接冒到 UI 线程
        try {
            exportConfigInner(ctx)
        } catch (t: Throwable) {
            DiagLog.log("❌ 配置导出失败: ${t.message}")
        }
    }

    private fun exportConfigInner(ctx: Context) {
        val sp = ctx.getSharedPreferences("safewalk_xsp", Context.MODE_PRIVATE)
        val json = JSONObject()

        // 只导出 Hook 真正需要的开关字段。
        // 之前是「sp.all 全量导出」，急救卡的姓名/血型/住址/联系电话
        // 也一起躺进了 JSON —— Provider 那边虽然挡了白名单，但文件本身
        // 多带一份隐私副本没有任何好处，root 一句 cat 就能看全。
        sp.all.forEach { (k, v) ->
            if (k !in ConfigProvider.WHITELIST) return@forEach
            when (v) {
                is Boolean -> json.put(k, v)
                is Int -> json.put(k, v)
                is Long -> json.put(k, v)
                is Float -> json.put(k, v)
                is String -> json.put(k, v)
            }
        }

        // 写到 files/ 目录
        ctx.openFileOutput(SafeWalkPrefKey.CONFIG_FILE, Context.MODE_PRIVATE).use {
            it.write(json.toString().toByteArray())
        }

        // 主动通知 SystemUI 里的 Hook：配置变了，别等轮询
        try {
            val i = android.content.Intent(SafeWalkPrefKey.ACTION_CONFIG_CHANGED)
            i.setPackage("com.android.systemui")
            i.putExtra(SafeWalkPrefKey.EXTRA_BROADCAST_TOKEN, SafeWalkPrefKey.BROADCAST_TOKEN)
            ctx.sendBroadcast(i)
        } catch (_: Throwable) {
            // 广播失败无所谓，Hook 还有 60 秒兜底轮询
        }
    }

    /**
     * Hook端：读取配置。
     *
     * 优先走 [ConfigProvider]（跨进程可用，Hook 跑在 SystemUI 进程，读不到本应用私有目录）；
     * 失败时回退到私有 JSON 文件（同进程或 root 场景）。
     */
    @JvmStatic
    fun loadConfig(ctx: Context?): JSONObject? {
        if (ctx != null) {
            try {
                val uri = android.net.Uri.parse("content://${ConfigProvider.AUTHORITY}/config")
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val s = c.getString(0)
                        if (!s.isNullOrEmpty()) return JSONObject(s)
                    }
                }
            } catch (_: Throwable) {
                // 跨进程通道不可用，继续走文件回退
            }
            // Provider 走不通时，同进程（App 自身）直接读自己的 files/ 目录。
            // 下面的 loadConfigFromFile() 是硬编码 /data/data/<包名>/ 的 root 兜底，
            // 换包名 / 工作资料场景会失效，这里用 ctx.filesDir 才可靠。
            try {
                val f = java.io.File(ctx.filesDir, SafeWalkPrefKey.CONFIG_FILE)
                if (f.exists()) return JSONObject(f.readText())
            } catch (_: Throwable) {
            }
        }
        return loadConfigFromFile()
    }

    /** Hook端：清空实验区一次性测试指令 */
    @JvmStatic
    fun clearTestAction(ctx: Context?) {
        try {
            val uri = android.net.Uri.parse("content://${ConfigProvider.AUTHORITY}/config")
            ctx?.contentResolver?.call(uri, "clearTestAction", null, null)
        } catch (_: Throwable) {
        }
    }

    /** 兼容旧路径：直接读私有 JSON 文件（仅同进程或 root 可读） */
    @JvmStatic
    fun loadConfigFromFile(): JSONObject? {
        return try {
            val path = "/data/data/io.github.wumisc.safewalk/files/${SafeWalkPrefKey.CONFIG_FILE}"
            val f = java.io.File(path)
            if (!f.exists()) return null
            JSONObject(f.readText())
        } catch (e: Exception) {
            null
        }
    }
}
