package io.github.wumisc.safewalk

import org.json.JSONArray
import org.json.JSONObject

/** 血型：A/B/AB/O/其他/未知（可选，未知不误导输血决策） */
enum class BloodType(val label: String) {
    A("A型"), B("B型"), AB("AB型"), O("O型"), OTHER("其他"), UNKNOWN("未知");

    companion object {
        @JvmStatic
        fun from(value: String?): BloodType {
            if (value.isNullOrBlank()) return UNKNOWN
            return values().firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.label == value
            } ?: UNKNOWN
        }
    }
}

/** Rh 因子：阳性 / 阴性 / 未知 */
enum class RhFactor(val label: String) {
    POSITIVE("Rh+"), NEGATIVE("Rh-"), UNKNOWN("Rh未知");

    companion object {
        @JvmStatic
        fun from(value: String?): RhFactor {
            if (value.isNullOrBlank()) return UNKNOWN
            return values().firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.label == value
            } ?: UNKNOWN
        }
    }
}

/** 性别：可选 */
enum class Gender(val label: String) {
    MALE("男"), FEMALE("女"), OTHER("其他"), UNKNOWN("未知");

    companion object {
        @JvmStatic
        fun from(value: String?): Gender {
            if (value.isNullOrBlank()) return UNKNOWN
            return values().firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.label == value
            } ?: UNKNOWN
        }
    }
}

/** 紧急联系人：至少 3 位，标记一位 primary 供覆盖层只展示一个号码 */
data class EmergencyContact(
    val name: String = "",
    val phone: String = "",
    val relation: String = "",
    val primary: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("phone", phone)
        .put("relation", relation)
        .put("primary", primary)

    companion object {
        @JvmStatic
        fun fromJson(o: JSONObject?): EmergencyContact {
            if (o == null) return EmergencyContact()
            return EmergencyContact(
                name = o.optString("name", ""),
                phone = o.optString("phone", ""),
                relation = o.optString("relation", ""),
                primary = o.optBoolean("primary", false)
            )
        }
    }
}

/**
 * 隐私开关。
 *
 * v1.1 用户裁决：撞击确认为真时**忽略** showBloodOnLock / showAddressOnLock，
 * 强制显示关键字段；这两个开关仅约束"手动预览急救卡"场景。
 * allowQr 默认关（QR 为明文，任何扫码者可读），开启时必须二次确认。
 */
data class PrivacyFlags(
    val showBloodOnLock: Boolean = false,
    val showAddressOnLock: Boolean = false,
    val allowSmsLocation: Boolean = true,
    val allowQr: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("showBloodOnLock", showBloodOnLock)
        .put("showAddressOnLock", showAddressOnLock)
        .put("allowSmsLocation", allowSmsLocation)
        .put("allowQr", allowQr)

    companion object {
        @JvmStatic
        fun fromJson(o: JSONObject?): PrivacyFlags {
            if (o == null) return PrivacyFlags()
            return PrivacyFlags(
                showBloodOnLock = o.optBoolean("showBloodOnLock", false),
                showAddressOnLock = o.optBoolean("showAddressOnLock", false),
                allowSmsLocation = o.optBoolean("allowSmsLocation", true),
                allowQr = o.optBoolean("allowQr", false)
            )
        }
    }
}

/**
 * 急救卡完整数据模型（仅 App 进程可见，加密落盘）。
 *
 * 字段全集依据 PRD §3.4；JSON 序列化用 Android 内置 org.json，无第三方依赖。
 * 反序列化对脏数据零容忍崩溃：每个字段都有默认值并独立 try/catch。
 */
data class EmergencyCard(
    val id: String = "self",
    val name: String = "",
    val age: Int = 0,
    val gender: Gender = Gender.UNKNOWN,
    val bloodType: BloodType = BloodType.UNKNOWN,
    val rh: RhFactor = RhFactor.UNKNOWN,
    val address: String = "",
    val allergy: String = "",
    val allergySevere: Boolean = false,
    val conditions: String = "",
    val medications: String = "",
    val contacts: List<EmergencyContact> = emptyList(),
    val insuranceId: String = "",
    val organDonation: Boolean = false,
    val privacy: PrivacyFlags = PrivacyFlags(),
    val updatedAt: Long = 0L
) {
    /** 完整卡 JSON：只用于加密存储，不进白名单/日志/广播 */
    fun toJson(): String {
        val arr = JSONArray()
        contacts.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("age", age)
            .put("gender", gender.name)
            .put("bloodType", bloodType.name)
            .put("rh", rh.name)
            .put("address", address)
            .put("allergy", allergy)
            .put("allergySevere", allergySevere)
            .put("conditions", conditions)
            .put("medications", medications)
            .put("contacts", arr)
            .put("insuranceId", insuranceId)
            .put("organDonation", organDonation)
            .put("privacy", privacy.toJson())
            .put("updatedAt", updatedAt)
            .toString()
    }

    /**
     * 医疗 QR 载荷：紧凑 JSON + 版本号，带全字段（隐私权衡由 allowQr 开关约束）。
     * QR 是明文，施救者无需 App 即可扫码；因此绝不能自动展示，必须用户显式开启。
     */
    fun qrPayload(): String {
        val arr = JSONArray()
        contacts.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("v", 1)
            .put("n", name)
            .put("a", age)
            .put("g", gender.name)
            .put("b", bloodType.name)
            .put("r", rh.name)
            .put("al", allergy)
            .put("as", allergySevere)
            .put("c", conditions)
            .put("m", medications)
            .put("ad", address)
            .put("ct", arr)
            .put("i", insuranceId)
            .put("od", organDonation)
            .put("t", updatedAt)
            .toString()
    }

    /** 主联系人：优先标记 primary 的，其次第一位有电话的 */
    fun primaryContact(): EmergencyContact =
        contacts.firstOrNull { it.primary && it.phone.isNotBlank() }
            ?: contacts.firstOrNull { it.phone.isNotBlank() }
            ?: EmergencyContact()

    companion object {
        @JvmStatic
        fun fromJson(raw: String?): EmergencyCard {
            if (raw.isNullOrBlank()) return EmergencyCard()
            return try {
                val o = JSONObject(raw)
                val arr = o.optJSONArray("contacts") ?: JSONArray()
                val list = ArrayList<EmergencyContact>(arr.length())
                for (i in 0 until arr.length()) {
                    list.add(EmergencyContact.fromJson(arr.optJSONObject(i)))
                }
                EmergencyCard(
                    id = o.optString("id", "self"),
                    name = o.optString("name", ""),
                    age = safeInt(o, "age", 0),
                    gender = Gender.from(o.optString("gender", "")),
                    bloodType = BloodType.from(o.optString("bloodType", "")),
                    rh = RhFactor.from(o.optString("rh", "")),
                    address = o.optString("address", ""),
                    allergy = o.optString("allergy", ""),
                    allergySevere = o.optBoolean("allergySevere", false),
                    conditions = o.optString("conditions", ""),
                    medications = o.optString("medications", ""),
                    contacts = list,
                    insuranceId = o.optString("insuranceId", ""),
                    organDonation = o.optBoolean("organDonation", false),
                    privacy = PrivacyFlags.fromJson(o.optJSONObject("privacy")),
                    updatedAt = safeLong(o, "updatedAt", 0L)
                )
            } catch (_: Throwable) {
                EmergencyCard()
            }
        }

        private fun safeInt(o: JSONObject, key: String, def: Int): Int = try {
            o.optInt(key, def)
        } catch (_: Throwable) {
            def
        }

        private fun safeLong(o: JSONObject, key: String, def: Long): Long = try {
            o.optLong(key, def)
        } catch (_: Throwable) {
            def
        }
    }
}

/** 求助动作类型（用于可见回执，不进日志值字段） */
enum class SosType { SMS, DIAL, SYSTEM_SOS, FALLBACK_120 }

/** 求助动作摘要：showReceipt() 用，字段均为结果标志，无隐私值 */
data class SosAction(
    val eventId: String = "",
    val type: SosType = SosType.SMS,
    val targetPhone: String = "",
    val smsSent: Boolean = false,
    val dialPrefilled: Boolean = false,
    val systemSosOpened: Boolean = false,
    val receipt: String = "",
    val executedAt: Long = 0L
)
