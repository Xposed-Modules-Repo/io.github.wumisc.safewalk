package io.github.wumisc.safewalk

/**
 * 【5.6.12】出行提醒 / 故障告知的通用焦点通知（超级岛）`miui.focus.param` JSON 构造。
 *
 * 背景：[SafeWalkNotificationManager.baseBuilder] 原来往 `miui.focus.param` 塞的是
 * 一个 Bundle（`miui.focus.template=notification`），而小米焦点通知要的是 **JSON
 * 字符串**——系统读到 Bundle 直接忽略，于是注释写着「全部走超级岛」实际一条都没上岛。
 * 这里按《开发指南》客户端接入示例给出正确结构，单点修复后全部提醒正确上岛；
 * 无焦点权限的机型系统自动退化为普通横幅通知（filterWhenNoPermission 默认 false）。
 *
 * 本条是**一次性提醒**，无按钮（点通知主体进 App）、updatable=false、岛属性信息展示为主
 * （islandProperty=1）。
 *
 * 纯字符串模板拼接（文案为固定中文 + 截断后的提醒正文，无引号/反斜杠注入风险，
 * 见 [sanitize]），不引 org.json，保证纯 JVM 可测。
 */
object FocusNotifyParam {

    /** 岛摘要态文字左右各约 4 字，frontTitle/title 必须收在这个宽度内。 */
    const val ISLAND_TITLE_MAX = 4

    /** 去掉会破坏 JSON 的字符（提醒正文理论上不含，防御性处理）。 */
    private fun sanitize(s: String): String =
        s.replace("\\", "").replace("\"", "").replace("\n", " ").replace("\r", " ")

    /**
     * @param title      通知完整标题（进 baseInfo，可长）
     * @param content    通知完整正文（进 baseInfo，可长）
     * @param tag        岛摘要态短标签（≤4 字，如「电量」「证件」「故障」）
     * @param actions    岛内按钮 key 列表（对应 miui.focus.actions Bundle）；空=无按钮
     * @param hintAction 强调区按钮 key（一般取 actions 首个）；null=不写 hintInfo
     */
    @JvmOverloads
    fun json(
        title: String,
        content: String,
        tag: String,
        actions: List<String> = emptyList(),
        hintAction: String? = null
    ): String {
        val t = sanitize(title)
        val c = sanitize(content)
        val shortTag = sanitize(tag).take(ISLAND_TITLE_MAX)
        val islandContent = sanitize(content).take(8)
        val oper = if (actions.isEmpty()) 1 else 2
        val base = """"baseInfo":{"title":"$t","content":"$c","type":1}"""
        val hint = if (hintAction != null)
            """"hintInfo":{"type":1,"title":"$shortTag","actionInfo":{"action":"$hintAction"}}"""
        else null
        val act = if (actions.isEmpty()) null else
            """"actions":[""" + actions.joinToString(",") { """{"action":"$it"}""" } + "]"
        val tail = listOfNotNull(base, hint, act).joinToString(",")
        return """{"param_v2":{"protocol":1,"business":"safewalk_remind",""" +
            """"enableFloat":false,"islandFirstFloat":true,"updatable":false,""" +
            """"timeout":1,"ticker":"$shortTag",""" +
            """"param_island":{"islandProperty":$oper,""" +
            """"bigIslandArea":{"imageTextInfoLeft":{"type":1,""" +
            """"picInfo":{"type":1},"textInfo":{"frontTitle":"安全出行",""" +
            """"title":"$shortTag","content":"$islandContent","useHighLight":false}}},""" +
            """"smallIslandArea":{"picInfo":{"type":1}}},""" +
            tail + "}}"
    }
}