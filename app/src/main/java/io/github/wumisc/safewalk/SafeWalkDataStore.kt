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

    /**
     * 上一次弹出「行走模式已自动恢复」通知的时刻（App 内部状态）。
     *
     * 不加入 [ConfigProvider.WHITELIST]：Hook 不需要知道，多一个跨进程状态
     * 就多一处「两边不一致」的可能。仅用于 [KeepAlivePolicy.shouldNotifyRevive]
     * 的节流，避免系统反复回收时每隔几分钟就打扰一次。
     */
    const val lastReviveNoticeTs = "lastReviveNoticeTs"

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
    /** 过敏史：救援人员最需要知道「对什么药过敏」 */
    const val emerAllergy = "emerAllergy"
    /** 慢性病 / 长期用药 */
    const val emerCondition = "emerCondition"
    /**
     * 上一次真正执行求助（发短信/拨号）的时间戳。
     *
     * 属于隐私相关状态，**不加入** ConfigProvider 白名单（不跨进程暴露）。
     * 用途：一次事故可能因「撞击连续触发」「full-screen 通知被点开重建页面」
     * 等原因多次走到 doSos()，必须去重，否则联系人会收到多条短信、多通电话。
     */
    const val emerLastSosTs = "emerLastSosTs"

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

    /**
     * 低头提醒（参考 Pixel 数字健康 Heads Up）：走路时长时间低头看手机就提醒抬头。
     * 默认关，用户主动开启才生效。App 进程（WalkPreviewService）自行读取。
     */
    const val enableHeadDownRemind = "enableHeadDownRemind"

    /**
     * 久走提醒（5.0.0）：连续出行累计走满 45 分钟，提醒找地方坐一下、喝口水。
     *
     * 与 [enableSitHealthRemind] 是一对相反方向的提醒：那个管「坐太久」，这个管「走太久」。
     * 默认关。**只在 App 进程读**（[WalkPreviewService] 的巡检 tick），
     * 不进 [ConfigProvider.WHITELIST]——Hook 侧不关心，塞进去只会让跨进程清单变长。
     */
    const val enableLongWalkRemind = "enableLongWalkRemind"

    /**
     * 夜间独行提醒（5.0.0）：夜间时段内累计步行 10 分钟，提醒留意周边。
     *
     * 默认关。与夜间手电共用同一组时段（[nightTorchStartHour] / [nightTorchEndHour]），
     * 用户改时段时两条提醒一起跟着变，不用再配一次。同样只在 App 进程读。
     */
    const val enableNightSoloRemind = "enableNightSoloRemind"

    /**
     * 行走语音播报（4.8.0）：视力障碍用户专用，走路时用 TTS 轻播来电与即时消息。
     *
     * 默认关。**只在 App 进程读**（[SafeWalkA11yService] 与 [WalkVoiceAnnouncer]），
     * SystemUI 侧的 Hook 完全不关心这个开关，所以**不需要**加入
     * [ConfigProvider.WHITELIST]——白名单只解决「Hook 进程跨进程读不到」的问题，
     * 往里塞用不上的键只会让那份清单变长、变难维护。
     */
    const val enableWalkVoice = "enableWalkVoice"

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

    // ==================================================================================
    // 【4.9.0 预览小窗自定义】
    //
    // 起因：早期版本 HUD 里那句「路况预览中 · 增强对比度」是调试遗留物，它告诉用户
    // 的事情用户本来就知道（我正在看预览），却不带任何可用信息，还平白占一块画面。
    // 「增强对比度」这个开关更是只为美化这句废话而存在 —— 没有文字时它毫无作用。
    //
    // 这一组 key 把「文字」和「对比度」彻底解耦：
    //  · 文字默认**关**，新用户不会再看到那句提示；
    //  · 想留的人可以自己开、自己改内容、自己调字号；
    //  · 对比度只在有文字时才生效，UI 上联动置灰。
    //
    // 尺寸与边距也给出来，因为固定 150×260dp 在 6.7 寸屏上偏小、在小屏上偏大，
    // 而「挡住哪儿」是用户比开发者更清楚的事。
    // ==================================================================================

    /**
     * 状态文字开关，默认 **false**。
     *
     * 关闭时 HUD 层直接 GONE，画面上不会出现任何文字。开启后才读
     * [previewHudText] / [previewHudTextSize]。
     */
    const val previewHudEnabled = "previewHudEnabled"

    /**
     * 状态文字内容，默认空串（空 = 用 [PREVIEW_HUD_TEXT_DEFAULT]）。
     *
     * 去掉了原来「· 增强对比度」后缀：那是实现细节，不是用户需要知道的信息。
     */
    const val previewHudText = "previewHudText"

    /** 状态文字字号（sp），11~20，默认 13。 */
    const val previewHudTextSize = "previewHudTextSize"

    /**
     * 角落小窗宽度（dp），100~200，默认 150。
     *
     * 高度不单独给：沿用历史比例 150:260（≈1:1.733），保证无论怎么拖，
     * 画面都不会被拉成奇怪的长条。改比例需要同时动相机裁剪，不在本版范围。
     */
    const val previewSmallWidthDp = "previewSmallWidthDp"

    /** 角落小窗距屏幕边缘的边距（dp），0~40，默认 12。 */
    const val previewSmallMarginDp = "previewSmallMarginDp"

    /**
     * 【5.0.0】取景范围 0~100，默认 0。
     *
     * 0 = 完整画面；100 = 只保留画面下半部分并等比放大 2 倍。
     * 数值越大路面占比越高、能看清更近的障碍，代价是横向视野变窄。
     */
    const val previewFramingPct = "previewFramingPct"

    /** 【5.0.0】小窗描边宽度（dp），0~4，默认 0（不描边）。 */
    const val previewBorderDp = "previewBorderDp"

    /** 【5.0.0】低光增强：预览请求下发正向曝光补偿。默认关。 */
    const val previewLowLight = "previewLowLight"

    // ==================================================================================
    // 【5.1.0 智能方案 + 画面增强】
    //
    // 用户两条反馈，把上一版的两个「看着有、其实没用」的坑点破了：
    //
    // ① 「一键方案」只有白天/夜间/极简三个按钮，选完就固定死。可人是在变的环境里
    //    走路的 —— 傍晚出门、天黑回家，一天里要手动切两次。这一组 key 让方案能
    //    **自己判断该用哪一套**：系统深色模式开着就是夜间；没开深色模式的用户，
    //    按他自己设的夜间时段判断。判断逻辑在 [PresetResolve]，纯函数、可单测。
    //
    // ② 上一版的「增强对比度」只给 HUD 文字加描边和衬底 —— 那是**文字**变清楚，
    //    不是**画面**变清楚。用户原话：「我要的是小窗预览增强对比度，这样才能更
    //    看得清」。这一组 key 才是真正的画面处理：对比度、暗部提亮、饱和度，
    //    由 [RearCameraOverlay] 在画面层做像素级处理（AGSL 运行时着色器）。
    // ==================================================================================

    /**
     * 【5.1.0】一键方案是否跟随环境自动切换。默认 **false**（保持用户手动选的方案）。
     *
     * 开启后，白天/夜间由 [PresetResolve] 在每次显示预览前实时判定，
     * 不再使用用户上次手动点的那一套。
     */
    const val presetAuto = "presetAuto"

    /**
     * 【5.1.0】画面增强总开关。默认 **false**。
     *
     * 与 [enablePreviewContrast]（文字描边）是两件完全不同的事，互不影响：
     *  · [enablePreviewContrast] → 文字加黑边黑底，让**字**从画面里跳出来；
     *  · [previewScreenEnhance]  → 动**画面像素**，让路面、台阶、障碍的明暗差拉开。
     */
    const val previewScreenEnhance = "previewScreenEnhance"

    /**
     * 【5.1.0】画面对比度强度 0~100，默认 45（约 +45% 的 S 曲线增益）。
     *
     * 0 = 原始画面；100 = 强对比（暗的更暗、亮的更亮，代价是高光可能过曝）。
     * 中间值走平滑 S 曲线而不是线性缩放，避免「一开就变成黑白照片」。
     */
    const val previewScreenContrast = "previewScreenContrast"

    /**
     * 【5.1.0】暗部提亮 0~100，默认 40。
     *
     * 只抬阴影不压高光：树荫、楼道口、下穿隧道这类「一片黑」的地方，靠它把台阶
     * 和路沿的轮廓提出来。对比度拉高会让暗部更黑，两者配合才既清楚又不发灰。
     */
    const val previewScreenShadowLift = "previewScreenShadowLift"

    /**
     * 【5.1.0】饱和度 0~100，默认 50（= 不变）。
     *
     * 0 = 完全灰度；50 = 原始；100 = 双倍饱和。夜间噪点重时把饱和度降低，
     * 画面会更「干净」，红绿色盲用户也可以靠它把颜色差异转成明暗差异。
     */
    const val previewScreenSaturation = "previewScreenSaturation"

    /**
     * 【5.3.0】画面方向：`auto`（默认）/ `fixed`（固定竖屏）/ `flip`（翻转 180°）。
     *
     * 相机传感器是横装的，屏幕可以竖、横、倒。以前完全没有旋转逻辑，
     * 竖屏靠窗口比例勉强凑合，一横过来画面就侧躺。
     *
     * 默认 `auto`：按传感器方向与屏幕旋转自动转正。留两个手动档不是「多此一举」——
     * 少数 ROM 的 SENSOR_ORIENTATION 标定是反的（尤其拆修过的机器），
     * 自动算出 180° 反而倒着，用户能用 `flip` 一键改回来，不用等新版本。
     *
     * 取值一律经 [PreviewOrientationPolicy.normalizeMode] 归一，写错的值按 auto 处理：
     * 配置文件是用户可编辑的，一个拼错的字符串不该让画面卡住不转。
     */
    const val previewRotationMode = "previewRotationMode"

    /** [previewHudText] 为空时的兜底文案。短、中性、不解释实现。 */
    const val PREVIEW_HUD_TEXT_DEFAULT = "路况预览"

    /**
     * 【已废弃 · 4.3】HyperOS 版本适配模式串。
     *
     * 4.3 起首页不再显示「HyperOS 版本适配」卡片（只对小米有意义，且代码根本
     * 不读版本），改为 [romOverride] 的「本机 UI 是哪一家」。
     *
     * 常量保留是为了**不删旧配置**：常量本身没有副作用，留着能让人看懂老版本
     * 配置里那行 `hyperOsMode=hos3` 是什么。代码中已无任何读取点。
     */
    @Deprecated("4.3 起改用 romOverride；本常量仅为兼容旧配置")
    const val hyperOsMode = "hyperOsMode"

    /**
     * 【系统 UI 适配】用户手动指定的生态，默认 [RomSettings.OVERRIDE_AUTO]。
     *
     * 合法值：auto / HYPEROS / MAGICOS / COLOROS / ORIGINOS / HARMONYOS / AOSP
     * （大小写不敏感，由 [RomSettings.normalize] 兜底）。auto 表示跟随 `Build` 三字段自动识别。
     *
     * 这一项**刻意不进** `ConfigProvider.WHITELIST`：SystemUI 侧的 Hook 只关心
     * 「功能开不开」，不关心「用户以为自己是哪家 UI」。导出它只会让两个进程多一个
     * 可以不一致的状态。
     */
    const val romOverride = "romOverride"

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
        // 【配置防丢】每次保存都镜像一份到外部目录，重装/清数据后可自动恢复
        ConfigBackup.backup(ctx)
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
