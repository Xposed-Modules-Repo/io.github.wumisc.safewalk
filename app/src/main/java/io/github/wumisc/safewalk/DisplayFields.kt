package io.github.wumisc.safewalk

import org.json.JSONObject

/**
 * 【v1.1 新增】覆盖层最小显示载荷（≤8 项）。
 *
 * 白名单式字段集：类里**根本没有** address / insuranceId / qrPayload /
 * medications / organDonation / 多联系人 这些属性，从类型层面杜绝"顺手多带一个字段"。
 * 只由 EmergencyVault.buildDisplayFields() 构造，经 DisplayProvider 一次性通道传出。
 */
data class DisplayFields(
    val name: String = "",
    val bloodType: String = "",
    val rh: String = "",
    val allergy: String = "",
    val allergySevere: Boolean = false,
    val conditions: String = "",
    val primaryContactName: String = "",
    val primaryContactPhone: String = "",
    val age: Int = 0
) {
    fun toJson(): String = JSONObject()
        .put("name", name)
        .put("bloodType", bloodType)
        .put("rh", rh)
        .put("allergy", allergy)
        .put("allergySevere", allergySevere)
        .put("conditions", conditions)
        .put("primaryContactName", primaryContactName)
        .put("primaryContactPhone", primaryContactPhone)
        .put("age", age)
        .toString()

    companion object {
        /**
         * 从完整卡白名单式抽取最小载荷。
         *
         * U1 决策：住址不进覆盖层（Activity 面保留显示）。
         * U2 决策：撞击确认为真时忽略 showBloodOnLock 隐私开关，强制显示关键字段。
         */
        @JvmStatic
        fun fromCard(card: EmergencyCard): DisplayFields {
            val p = card.primaryContact()
            return DisplayFields(
                name = card.name,
                bloodType = card.bloodType.label,
                rh = card.rh.label,
                allergy = card.allergy,
                allergySevere = card.allergySevere,
                conditions = card.conditions,
                primaryContactName = p.name,
                primaryContactPhone = p.phone,
                age = card.age
            )
        }

        @JvmStatic
        fun fromJson(raw: String?): DisplayFields {
            if (raw.isNullOrBlank()) return DisplayFields()
            return try {
                val o = JSONObject(raw)
                DisplayFields(
                    name = o.optString("name", ""),
                    bloodType = o.optString("bloodType", ""),
                    rh = o.optString("rh", ""),
                    allergy = o.optString("allergy", ""),
                    allergySevere = o.optBoolean("allergySevere", false),
                    conditions = o.optString("conditions", ""),
                    primaryContactName = o.optString("primaryContactName", ""),
                    primaryContactPhone = o.optString("primaryContactPhone", ""),
                    age = o.optInt("age", 0)
                )
            } catch (_: Throwable) {
                DisplayFields()
            }
        }

        /** 字段全空说明没有可显示内容（覆盖层只画非隐私壳） */
        @JvmStatic
        fun isBlank(f: DisplayFields): Boolean =
            f.name.isBlank() && f.bloodType.isBlank() && f.allergy.isBlank() &&
                f.conditions.isBlank() && f.primaryContactName.isBlank() &&
                f.primaryContactPhone.isBlank() && f.age <= 0
    }
}
