package io.github.wumisc.safewalk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.util.function.Predicate

/**
 * 把 [RomRegistry] 里的候选页变成真正能打开的 `Intent`（P1 交付物）。
 *
 * ## 职责边界
 *
 * 这里**只做指路**，不做能力判断。它回答的是「用户点了『去开自启动』之后该跳到哪一页」，
 * 不回答「跳过去之后系统会不会放行」——后者永远取决于运行时，见计划第 3 节。
 *
 * ## 为什么每条都要 resolveActivity
 *
 * 表里的 component 取自社区常见值，随 ROM 版本漂移。直接 `startActivity` 的后果是
 * 用户看到「应用已停止运行」（部分 ROM 把 `ActivityNotFoundException` 抛成崩溃），
 * 或者更糟：跳到一个存在但空白的页面，用户以为开好了。
 *
 * 所以顺序是：**先 `resolveActivity` 筛一遍，再逐条 try**。两道关卡的失败语义不同：
 * - `resolveActivity` 返回 null → 这台上没有这个页面，静默跳过（不写 error 日志）；
 * - `startActivity` 抛异常 → 页面存在但不可导出 / 权限不足，记一笔再试下一条。
 *
 * ## 为什么最终一定要回退应用详情页
 *
 * 应用详情页在所有 Android 上必然存在，且里面有「权限」「电池」「通知」入口。
 * 用户到了那里至少能自己找；停在引导页看一句「打开失败」则什么也做不了。
 */
object RomPageLauncher {

    /**
     * 需要 `data=package:<pkg>` 才算完整的 action。
     *
     * 这三条如果只发裸 action，系统会打开「应用列表」而不是「本应用」的详情，
     * 用户还得自己找一遍——正是要避免的事。
     */
    @JvmStatic
    fun needsPackageData(action: String): Boolean = when (action) {
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" -> true
        else -> false
    }

    /**
     * 按声明顺序取第一条「可用」的候选（纯函数，单测直接调）。
     *
     * 抽成纯函数而不是内联在 [pick] 里，是为了让「顺序即优先级」这条约定有测试守着：
     * 表里两条小米路径的先后顺序一旦被手滑调换，真机上就会先跳旧版页再跳新版页，
     * 这种错误只有单测能低成本发现。
     */
    @JvmStatic
    fun firstResolvable(cands: List<PageTarget>, canResolve: Predicate<PageTarget>): PageTarget? {
        for (c in cands) {
            if (canResolve.test(c)) return c
        }
        return null
    }

    /** 由一条候选构造 `Intent`；action 形式按 [needsPackageData] 决定是否补 data。 */
    @JvmStatic
    fun intentFor(ctx: Context, t: PageTarget): Intent {
        val a = t.action()
        if (a != null) {
            val i = Intent(a)
            if (needsPackageData(a)) i.data = Uri.fromParts("package", ctx.packageName, null)
            return i
        }
        val spec = t.componentSpec()!!.split('/')
        return Intent().setComponent(ComponentName(spec[0], spec[1]))
    }

    /** 该 Intent 在本机能否解析到具体页面。 */
    private fun canResolve(ctx: Context, t: PageTarget): Boolean = try {
        val i = intentFor(ctx, t)
        // 显式 component 用 0 即可；action 形式需要 DEFAULT_ONLY，否则可能匹配到
        // 只声明了 CATEGORY_DEFAULT 以外的过滤器，startActivity 时才失败。
        ctx.packageManager.resolveActivity(i, 0) != null
    } catch (_: Throwable) {
        false
    }

    /**
     * 安全取候选列表。
     *
     * `RomRegistry` 是 Kotlin object，表里任何一条 spec 写错都会在首次访问时抛
     * `ExceptionInInitializerError`。指路层不能让一张表把整个引导页拖崩，
     * 所以这里兜住并当成「没有候选」→ 调用方回退应用详情页。
     */
    private fun candidates(id: RomId, kind: TargetKind): List<PageTarget> = try {
        RomRegistry.profile(id).pagesOf(kind)
    } catch (t: Throwable) {
        DiagLog.log(
            "[ROM] 适配表不可用（${t.javaClass.simpleName}），「${kind.label}」按无候选处理"
        )
        emptyList()
    }

    /**
     * 选出该生态、该类别的第一条可用候选；全不可用返回 null。
     *
     * @param id 不传则按 [RomSettings.effective] 现场判定（手动指定优先）。
     *   4.3 之前这里直接调 `RomDetector.detectFromBuild()`，结果是：用户在首页手选了
     *   ColorOS，引导页文案也按 ColorOS 生成，但点「去开启」时**这里又独立判了一次**，
     *   手选被无声忽略、仍然拿自动识别的表。默认值必须和 UI 用同一个入口。
     */
    @JvmStatic
    @JvmOverloads
    fun pick(ctx: Context, kind: TargetKind, id: RomId = RomSettings.effective(ctx)): PageTarget? {
        val cands = candidates(id, kind)
        val hit = firstResolvable(cands) { canResolve(ctx, it) }
        if (hit == null) {
            DiagLog.log(
                "[ROM] ${id.displayName} 的「${kind.label}」无可用候选（共 ${cands.size} 条），" +
                    "将回退应用详情页"
            )
        }
        return hit
    }

    /**
     * 选中并打开。返回实际用掉的那条候选；返回 null 表示一条都没打开，调用方应回退。
     *
     * 注意：这里**不**替调用方回退应用详情页。回退是本类 [openOrDetails] 的职责，
     * 分开是为了让「只想知道能不能开、不想真的开」的场景（如引导页探测）不产生副作用。
     */
    @JvmStatic
    @JvmOverloads
    fun open(ctx: Context, kind: TargetKind, id: RomId = RomSettings.effective(ctx)): PageTarget? {
        for (t in candidates(id, kind)) {
            if (!canResolve(ctx, t)) continue
            try {
                val i = intentFor(ctx, t)
                if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                DiagLog.log("[ROM] ${id.displayName}「${kind.label}」已打开：${t.label}（${t.spec}）")
                return t
            } catch (e: Throwable) {
                // 页面能解析但打不开（未导出 / 被系统策略拦），换下一条继续试。
                DiagLog.log(
                    "[ROM] ${id.displayName}「${kind.label}」打开失败：${t.label} " +
                        "${e.javaClass.simpleName} ${e.message}"
                )
            }
        }
        return null
    }

    /**
     * 打开；全失败则回退应用详情页。返回 null 表示连详情页都没打开
     * （极端情况：设置被禁用 / 用户空间受限）。
     */
    @JvmStatic
    @JvmOverloads
    fun openOrDetails(
        ctx: Context,
        kind: TargetKind,
        id: RomId = RomSettings.effective(ctx)
    ): PageTarget? {
        val hit = open(ctx, kind, id)
        if (hit != null) return hit
        val details = PageTarget.action(
            kind, Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "应用详情（兜底）"
        )
        return try {
            val i = intentFor(ctx, details)
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            DiagLog.log("[ROM] 「${kind.label}」回退应用详情页")
            details
        } catch (e: Throwable) {
            DiagLog.log(
                "[ROM] 「${kind.label}」连应用详情页都打不开：" +
                    "${e.javaClass.simpleName} ${e.message}"
            )
            null
        }
    }
}
