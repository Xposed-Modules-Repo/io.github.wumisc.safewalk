package io.github.wumisc.safewalk

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 急救卡「导出 / 导入」的纯逻辑（不碰文件、不碰 Activity，便于单测）。
 *
 * 【为什么需要它】
 * 发布签名换成本项目自有密钥后，已装旧包的用户必须**卸载重装**才能升级，
 * 而急救卡存在 `EncryptedSharedPreferences`（AndroidKeyStore 密钥 + 应用私有目录）里，
 * 卸载会把密钥和数据一起删掉。加密本身是对的，但用户手里那张卡是他自己填的医疗信息，
 * 不该因为换签名而凭空消失。所以给一条显式的备份通道：
 * 导出成明文 JSON 存到用户选的位置 → 重装后从该文件导入。
 *
 * 【明文风险怎么管】
 * 导出的是明文医疗信息（血型/过敏/住址/联系人）。因此：
 *  - 只在用户**主动点击**「导出急救卡」并确认风险提示后才生成；
 *  - 落盘位置由用户通过 SAF 自己选（本 App 不写任何公共目录、不要存储权限）；
 *  - 文件名带日期，方便用户自己判断新旧。
 *
 * 【格式设计】
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "exportedAt": 1763500000000,
 *   "card": { ...与 EmergencyCard.toJson() 完全一致... }
 * }
 * ```
 * `card` 直接复用 [EmergencyCard.toJson]，是为了**只有一处**字段定义：
 * 以后加字段只改模型，导入导出自带；也避免两个 schema 各自漂移。
 * 顶层 `schemaVersion` 用于将来做兼容迁移：不认识的版本一律拒绝，
 * 绝不"尽力猜"——猜错等于把别人的血型写进你的急救卡。
 *
 * 【刻意不导出什么】
 * - `emerLastSosTs`（上次求助时间戳）等**设备状态**：它属于这台设备发生过的事，
 *   跟着卡片搬到新设备上只会造成「刚装好就提示近期求助过」的误解；
 * - 任何加密材料、密钥、偏好开关：那些与本机环境绑定，搬过去也无意义。
 */
object EmergencyCardBackup {

    /** 当前导出格式版本。不兼容变更时 +1，并在 [importJson] 里做迁移分支。 */
    const val SCHEMA_VERSION = 1

    private const val KEY_SCHEMA = "schemaVersion"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_CARD = "card"

    /** 建议文件名前缀 / 后缀，单测与 UI 共用，避免两边写死后不一致 */
    private const val FILE_PREFIX = "safewalk-emergency-card-"
    private const val FILE_SUFFIX = ".json"

    /**
     * 导出为稳定的 JSON 文本（当前时间作为 exportedAt）。
     *
     * @param card 完整急救卡（通常取 [EmergencyVault.readCard]）
     * @return 可写入文件的 UTF-8 文本，带 2 空格缩进（用户可读、可用文本编辑器核对）
     */
    @JvmStatic
    fun exportJson(card: EmergencyCard): String = exportJson(card, System.currentTimeMillis())

    /**
     * 导出为 JSON 文本，`exportedAt` 由调用方指定（单测可钉死时间，避免随机值）。
     *
     * 容错：`card.toJson()` 万一抛异常（字段里有非法值），退化成空对象导出，
     * 用户至少还能拿到一个结构合法的文件，而不是一个"导出失败"的死胡同。
     */
    @JvmStatic
    fun exportJson(card: EmergencyCard, now: Long): String {
        val cardObj: JSONObject = try {
            JSONObject(card.toJson())
        } catch (_: Throwable) {
            JSONObject()
        }
        return JSONObject()
            .put(KEY_SCHEMA, SCHEMA_VERSION)
            .put(KEY_EXPORTED_AT, now)
            .put(KEY_CARD, cardObj)
            .toString(2)
    }

    /**
     * 解析导入文件。
     *
     * 任何不可信输入都返回 `null`（**不抛异常**）：文件是用户从任意来源选的
     * （微信存的、记事本改过的、别的 App 的同名文件），签名校验不了，只能全部当脏数据。
     *
     * 拒绝的情形：空白串、JSON 语法坏、没有 `schemaVersion`、版本不认识、
     * 缺 `card` 对象、`card` 是空对象（多半是手工改坏或截断）。
     *
     * @return 解析成功的急救卡；失败返回 null，调用方提示"文件无法识别"。
     */
    @JvmStatic
    fun importJson(text: String?): EmergencyCard? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            val root = JSONObject(raw)
            if (!root.has(KEY_SCHEMA)) return null
            val schema = root.optInt(KEY_SCHEMA, -1)
            if (schema != SCHEMA_VERSION) return null
            val cardObj = root.optJSONObject(KEY_CARD) ?: return null
            // 空对象不是"一张空卡"，而是结构缺失（真实导出哪怕空卡也有 id/name 等键）
            if (cardObj.length() == 0) return null
            EmergencyCard.fromJson(cardObj.toString())
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 建议文件名，如 `safewalk-emergency-card-20261119.json`。
     *
     * 用固定时区（UTC+8 不写死，交给 [Locale.US] + 默认时区）与纯数字日期：
     * 文件名里有冒号/空格在部分 ROM 的文件选择器上会出问题，纯数字最稳。
     */
    @JvmStatic
    fun suggestFileName(now: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        val stamp = try {
            fmt.format(Date(now))
        } catch (_: Throwable) {
            "00000000"
        }
        return FILE_PREFIX + stamp + FILE_SUFFIX
    }

    /**
     * 导入确认对话框用的摘要（只在内存里拼字符串，不落盘、不进日志值字段）。
     *
     * 只显示「能让人一眼确认是不是自己那张卡」的最小集合：姓名、血型+Rh、联系人数量。
     * 刻意不打印住址/病史细节 —— 这是展示在对话框上的内容，能少一个字就少一个字。
     */
    @JvmStatic
    fun describe(card: EmergencyCard): String {
        val name = card.name.ifBlank { "（未填姓名）" }
        val blood = card.bloodType.label + " " + card.rh.label
        val contacts = card.contacts.size
        return "姓名：$name\n血型：$blood\n紧急联系人：$contacts 位"
    }
}
