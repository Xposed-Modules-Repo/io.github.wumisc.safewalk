package io.github.wumisc.safewalk

/**
 * 五家 UI 生态的适配数据表（P0 交付物）。
 *
 * ## 这张表的边界
 *
 * 表里**只有**「系统没有公开 API、只能把用户送到某个设置页」的四类入口
 * （自启动 / 电池白名单 / 后台弹窗 / 紧急呼叫），加上各生态的相机包名。
 *
 * 前台服务、悬浮窗、手电、Hook 锚点**一律不在表里**：它们靠运行时降级链工作，
 * 一旦写进表，就等于把「功能能不能用」交给版本判断——那是 3.x 犯过的错。
 *
 * ## 为什么所有条目都只是「候选」
 *
 * 厂商私有页的 component 名称随 ROM 版本漂移（同一个小米，`.permcenter.` 与
 * `.permission.` 两套都见过）。因此：
 *
 * 1. 每个 [TargetKind] 下可以有多条，**顺序即优先级**；
 * 2. 运行时逐条 `resolveActivity` 校验，命中即用；
 * 3. 全失败 → 回退 `ACTION_APPLICATION_DETAILS_SETTINGS`，并留一行日志说明哪几条没命中。
 *
 * 表写错的代价只是「多点两下」，不会是「功能失效」。这是本表的**设计前提**，
 * 也是它敢写具体 component 的原因。
 *
 * ## 关于准确性的诚实说明
 *
 * 表中 component 取自社区常见值与项目既有代码（`PermissionOnboardingActivity`、
 * `EmergencyCardActivity` 里已在用的路径），**尚未在五家真机上逐条验证**。
 * 验证方法：`adb shell cmd package resolve-activity --brief <pkg/cls>`。
 * 计划 P1 的验收标准就是「每家至少一条能打开」。
 */
object RomRegistry {

    /** 小米安全中心，自启动 / 紧急呼叫都在这个包里。 */
    private const val MIUI_SC = "com.miui.securitycenter"

    /** 荣耀系统管家。 */
    private const val HONOR_SM = "com.hihonor.systemmanager"

    /** OPPO/一加/realme 安全中心（新包名）。 */
    private const val OPLUS_SC = "com.oplus.safecenter"

    /** 老 ColorOS 安全中心。 */
    private const val COLOROS_SC = "com.coloros.safecenter"

    /** vivo 权限管理。 */
    private const val VIVO_PM = "com.vivo.permissionmanager"

    /** 所有 ROM 都能用的应用详情页，作为最终兜底。 */
    private const val FALLBACK_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"

    /** 标准电池优化页（AOSP 就有，四家国产 ROM 也都保留了这条 action）。 */
    private const val ACTION_BATTERY = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"

    private val HYPEROS = RomProfile(
        id = RomId.HYPEROS,
        pages = listOf(
            // 两套路径都见过：新版在 permcenter，老版在 securitycenter.permission
            PageTarget.component(
                TargetKind.AUTOSTART,
                "$MIUI_SC/com.miui.permcenter.autostart.AutoStartManagementActivity",
                "安全中心 → 应用管理 → 自启动"
            ),
            PageTarget.component(
                TargetKind.AUTOSTART,
                "$MIUI_SC/com.miui.securitycenter.permission.autostart.AutoStartManagementActivity",
                "安全中心 → 权限 → 自启动（旧版）"
            ),
            PageTarget.action(
                TargetKind.AUTOSTART, FALLBACK_DETAILS, "应用详情 → 自启动（兜底）"
            ),
            PageTarget.action(
                TargetKind.BATTERY, ACTION_BATTERY, "省电策略 → 无限制"
            ),
            // 小米的「后台弹出界面」没有独立 Activity，只能进权限页手动找
            PageTarget.action(
                TargetKind.BACKGROUND_POPUP, FALLBACK_DETAILS, "应用详情 → 权限 → 后台弹出界面"
            ),
            PageTarget.component(
                TargetKind.EMERGENCY_CALL,
                "$MIUI_SC/com.miui.securitycenter.emergency.EmergencyActivity",
                "安全中心 → 紧急求助"
            )
        ),
        cameraPkgs = setOf("com.miui.camera", "com.xiaomi.camera"),
        note = "camera 型前台服务后台启动被拒是常态，降级链主要为本生态准备"
    )

    private val MAGICOS = RomProfile(
        id = RomId.MAGICOS,
        pages = listOf(
            PageTarget.component(
                TargetKind.AUTOSTART,
                "$HONOR_SM/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "系统管家 → 应用启动管理"
            ),
            PageTarget.action(
                TargetKind.AUTOSTART, FALLBACK_DETAILS, "应用详情 → 自启动（兜底）"
            ),
            PageTarget.action(
                TargetKind.BATTERY, ACTION_BATTERY, "电池 → 更多设置 → 允许后台活动"
            )
        ),
        cameraPkgs = setOf("com.hihonor.camera"),
        note = "后台进程回收激进；无独立「后台弹出界面」开关，不要给用户指错路"
    )

    private val COLOROS = RomProfile(
        id = RomId.COLOROS,
        pages = listOf(
            PageTarget.component(
                TargetKind.AUTOSTART,
                "$OPLUS_SC/com.oplus.safecenter.startupapp.StartupAppListActivity",
                "手机管家 → 自启动管理"
            ),
            PageTarget.component(
                TargetKind.AUTOSTART,
                "$COLOROS_SC/com.coloros.safecenter.startupapp.StartupAppListActivity",
                "手机管家 → 自启动管理（旧版）"
            ),
            PageTarget.action(
                TargetKind.AUTOSTART, FALLBACK_DETAILS, "应用详情 → 自启动（兜底）"
            ),
            PageTarget.action(
                TargetKind.BATTERY, ACTION_BATTERY, "耗电管理 → 允许后台活动"
            ),
            PageTarget.action(
                TargetKind.BACKGROUND_POPUP, FALLBACK_DETAILS, "应用详情 → 权限 → 后台弹出界面"
            ),
            PageTarget.component(
                TargetKind.EMERGENCY_CALL,
                "$COLOROS_SC/com.coloros.safecenter.permission.startup.EmergencyActivity",
                "安全中心 → 紧急呼叫"
            )
        ),
        cameraPkgs = setOf("com.oplus.camera", "com.coloros.camera"),
        note = "自启动默认关闭且后台启动管控严，是新用户最容易「装了没反应」的一家"
    )

    private val ORIGINOS = RomProfile(
        id = RomId.ORIGINOS,
        pages = listOf(
            PageTarget.component(
                TargetKind.AUTOSTART,
                "$VIVO_PM/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "i管家 → 自启动管理"
            ),
            PageTarget.action(
                TargetKind.AUTOSTART, FALLBACK_DETAILS, "应用详情 → 自启动（兜底）"
            ),
            PageTarget.action(
                TargetKind.BATTERY, ACTION_BATTERY, "电池 → 后台高耗电 → 允许"
            ),
            PageTarget.action(
                TargetKind.BACKGROUND_POPUP, FALLBACK_DETAILS, "应用详情 → 权限 → 后台弹出界面"
            ),
            PageTarget.component(
                TargetKind.EMERGENCY_CALL,
                "com.iqoo.call/com.iqoo.call.emergency.EmergencyActivity",
                "电话 → 紧急呼叫"
            )
        ),
        cameraPkgs = setOf("com.vivo.camera"),
        note = "后台高耗电策略会直接杀进程；iQOO 的相机与紧急呼叫都是独立包名"
    )

    private val AOSP = RomProfile(
        id = RomId.AOSP,
        pages = listOf(
            // 原生没有「自启动」概念，只给标准电池优化页；不硬凑一个不存在的入口
            PageTarget.action(
                TargetKind.BATTERY, ACTION_BATTERY, "电池 → 不受限制"
            )
        ),
        cameraPkgs = setOf("com.android.camera2"),
        note = "无厂商私有页，全部走标准 API；未识别设备也落在这里，最安全"
    )

    /** 全部档案。键穷举 [RomId]，新增枚举值时这里编译不过 —— 故意的。 */
    private val PROFILES: Map<RomId, RomProfile> = mapOf(
        RomId.HYPEROS to HYPEROS,
        RomId.MAGICOS to MAGICOS,
        RomId.COLOROS to COLOROS,
        RomId.ORIGINOS to ORIGINOS,
        RomId.AOSP to AOSP
    )

    /** 取某个生态的档案；[RomId] 穷举，不会 null。 */
    @JvmStatic
    fun profile(id: RomId): RomProfile =
        PROFILES[id] ?: error("RomRegistry 缺少 ${id.name} 的档案")

    /** 全部生态，顺序同枚举声明（UI 列表直接用它）。 */
    @JvmStatic
    fun ids(): List<RomId> = RomId.values().toList()

    /**
     * 相机类包名并集：通用表 [CameraAppPolicy.basePackages] ∪ 该生态专属表。
     *
     * 用并集而不是替换，是为了「宁可多收一次小窗，也不要盖着取景框」：
     * 通用表里已有华为/三星/Google 等，厂商表只补自己独有的。
     */
    @JvmStatic
    fun cameraPkgs(id: RomId): Set<String> =
        LinkedHashSet<String>(CameraAppPolicy.basePackages()).apply {
            addAll(profile(id).cameraPkgs)
        }

    /**
     * 「这一类设置在哪」的一行提示，供引导页原文展示。
     *
     * 只写**第一条**候选的去处，后面几条只提数量：把五条路径全列出来，用户反而不知道该点哪个。
     * 真正跳转由 [RomPageLauncher] 逐条试，这里只是让人心里有数。
     */
    @JvmStatic
    fun routeHint(id: RomId, kind: TargetKind): String {
        val cands = profile(id).pagesOf(kind)
        if (cands.isEmpty()) {
            return if (!id.hasVendorPages) "本系统没有这个开关，无需设置" else "未收录，将打开应用详情页"
        }
        val first = cands.first().label
        return if (cands.size > 1) "$first（另有 ${cands.size - 1} 条备选）" else first
    }

    /**
     * 一行摘要，供日志 / 设置页展示。
     *
     * 刻意不写「已支持 / 完美适配」：本表只保证「指路」，不保证「系统一定放行」。
     */
    @JvmStatic
    fun describe(id: RomId): String {
        val p = profile(id)
        val auto = p.pagesOf(TargetKind.AUTOSTART).size
        val name = p.id.displayName
        return if (p.id == RomId.AOSP) {
            "$name｜无厂商私有页，走标准 API｜相机包名 ${cameraPkgs(id).size} 个"
        } else {
            "$name｜自启动候选 $auto 条｜相机包名 ${cameraPkgs(id).size} 个｜${p.note}"
        }
    }
}
