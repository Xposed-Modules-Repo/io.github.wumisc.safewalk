package io.github.wumisc.safewalk

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 急救卡加密存储（仅 App 进程可访问）。
 *
 * 数据源：safewalk_emer_secure（EncryptedSharedPreferences，AndroidKeyStore + AES256-GCM）。
 * 明文旧数据：safewalk_xsp 的 7 个 emerXxx key，由 [migrateIfNeeded] 幂等迁移。
 *
 * 【失败策略】KeyStore 不可用 / 设备无锁屏时，降级到**本次会话内存态**并让 UI 明示
 * "本次未落盘"，**绝不静默回退明文 SharedPreferences**（这正是老版本泄露的根因）。
 */
object EmergencyVault {
    private const val KEY_CARD = "card_v1"
    private const val LEGACY_PREFS = "safewalk_xsp"

    /** 已打开的加密偏好；null 表示只能内存态 */
    @Volatile
    private var enc: SharedPreferences? = null

    /** 内存兜底副本（仅进程存活期间有效，不落盘） */
    @Volatile
    private var memoryCard: EmergencyCard? = null

    /** 是否真正加密落盘（供 UI 提示"本次未落盘"） */
    @JvmStatic
    fun isEncrypted(): Boolean = enc != null

    /**
     * 打开加密存储。可在 onCreate 中重复调用（幂等）。
     *
     * @return true = 加密存储可用；false = 只能内存态（调用方须提示用户）
     */
    @JvmStatic
    fun init(ctx: Context): Boolean {
        if (enc != null) return true
        return try {
            val app = ctx.applicationContext ?: ctx
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            enc = EncryptedSharedPreferences.create(
                app,
                SafeWalkPrefKey.EMER_VAULT_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            EmergencyDiag.install()
            true
        } catch (t: Throwable) {
            EmergencyDiag.event(
                EmergencyDiag.P_VAULT,
                "encrypted_storage_unavailable: ${t.javaClass.simpleName}（本次仅内存态，不回退明文）"
            )
            false
        }
    }

    /**
     * 幂等迁移旧明文 → 加密存储。
     *
     * 顺序：读旧明文 → 写加密 → 校验读回一致 → 才 remove 旧 key。
     * 任何一步失败都保留旧值，下次再试；因此老用户数据绝不会因为一次失败而丢失。
     */
    @JvmStatic
    fun migrateIfNeeded(ctx: Context): Boolean {
        val app = ctx.applicationContext ?: ctx
        if (enc == null) init(app)
        val encSp = enc ?: return false
        val legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        return migrateIfNeeded(legacy, encSp)
    }

    /**
     * 可测试的迁移核心：与 Android 加密实现解耦，单测可注入两个普通 SharedPreferences
     * 验证"迁移幂等 / 校验读回 / 失败不删旧明文"。生产调用方传加密后的 SharedPreferences。
     */
    @JvmStatic
    fun migrateIfNeeded(legacy: SharedPreferences, target: SharedPreferences): Boolean {
        return try {
            if (target.getBoolean(SafeWalkPrefKey.emerVaultMigrated, false)) return true
            // 已迁过新格式：只补一个标记即可，不去碰旧 key（可能已被清理）
            val legacyCard = readLegacy(legacy)
            if (isLegacyEmpty(legacyCard)) {
                target.edit().putBoolean(SafeWalkPrefKey.emerVaultMigrated, true).commit()
                return true
            }
            val card = readLegacyCard(legacy)
            val written = target.edit().putString(KEY_CARD, card.toJson()).commit()
            if (!written) return false

            // 校验读回：只有逐字段一致才允许删除旧明文
            val back = EmergencyCard.fromJson(target.getString(KEY_CARD, null))
            if (back.name != card.name || back.address != card.address ||
                back.allergy != card.allergy || back.bloodType != card.bloodType ||
                back.rh != card.rh || back.conditions != card.conditions ||
                back.contacts.size != card.contacts.size
            ) {
                EmergencyDiag.event(EmergencyDiag.P_VAULT, "migrate_verify_failed: 保留旧明文待重试")
                return false
            }

            // emerLastSosTs 是隐私状态，一并迁进 Vault（它本来就不在白名单）
            val lastSos = legacy.getLong(SafeWalkPrefKey.emerLastSosTs, 0L)
            if (lastSos > 0L) target.edit().putLong(SafeWalkPrefKey.emerLastSosTs, lastSos).commit()

            target.edit().putBoolean(SafeWalkPrefKey.emerVaultMigrated, true).commit()
            legacy.edit()
                .remove(SafeWalkPrefKey.emerName)
                .remove(SafeWalkPrefKey.emerBlood)
                .remove(SafeWalkPrefKey.emerAddress)
                .remove(SafeWalkPrefKey.emerContactName)
                .remove(SafeWalkPrefKey.emerContactPhone)
                .remove(SafeWalkPrefKey.emerAllergy)
                .remove(SafeWalkPrefKey.emerCondition)
                .remove(SafeWalkPrefKey.emerLastSosTs)
                .commit()
            EmergencyDiag.event(EmergencyDiag.P_VAULT, "migrate_done: 旧明文已清理")
            true
        } catch (t: Throwable) {
            EmergencyDiag.event(
                EmergencyDiag.P_VAULT,
                "migrate_failed: ${t.javaClass.simpleName}（旧明文保留，下次重试）"
            )
            false
        }
    }

    /** 读完整卡（解密）；失败返回空卡，绝不返回明文副本 */
    @JvmStatic
    fun readCard(): EmergencyCard {
        val sp = enc ?: return memoryCard ?: EmergencyCard()
        return try {
            EmergencyCard.fromJson(sp.getString(KEY_CARD, null))
        } catch (_: Throwable) {
            EmergencyCard()
        }
    }

    /** 保存完整卡；加密不可用时只留内存副本并返回 false（UI 提示"本次未落盘"） */
    @JvmStatic
    fun saveCard(card: EmergencyCard): Boolean {
        val sp = enc
        if (sp == null) {
            memoryCard = card
            return false
        }
        return try {
            val ok = sp.edit().putString(KEY_CARD, card.toJson()).commit()
            if (ok) memoryCard = null else memoryCard = card
            ok
        } catch (_: Throwable) {
            memoryCard = card
            false
        }
    }

    /**
     * 白名单式最小载荷：仅覆盖层所需字段。
     *
     * U1：住址不进覆盖层（Activity 面保留）。
     * U2：撞击确认为真时忽略 showBloodOnLock / showAddressOnLock，强制显示。
     */
    @JvmStatic
    fun buildDisplayFields(): DisplayFields = DisplayFields.fromCard(readCard())

    @JvmStatic
    fun buildDisplayFields(card: EmergencyCard): DisplayFields = DisplayFields.fromCard(card)

    @JvmStatic
    fun lastSosTs(): Long = try {
        enc?.getLong(SafeWalkPrefKey.emerLastSosTs, 0L) ?: 0L
    } catch (_: Throwable) {
        0L
    }

    /** 求助去重"先落盘后执行"：先调本方法标记，再发短信/拨号 */
    @JvmStatic
    fun markSos(action: SosAction) {
        val now = if (action.executedAt > 0L) action.executedAt else System.currentTimeMillis()
        try {
            enc?.edit()?.putLong(SafeWalkPrefKey.emerLastSosTs, now)?.commit()
        } catch (_: Throwable) {
        }
        EmergencyDiag.event(
            EmergencyDiag.P_SOS,
            "mark event=${action.eventId} type=${action.type} receipt_len=${action.receipt.length}"
        )
    }

    @JvmStatic
    fun clear() {
        memoryCard = null
        try {
            enc?.edit()?.clear()?.commit()
        } catch (_: Throwable) {
        }
    }

    // ===== 旧明文读取（仅迁移使用）=====
    private data class LegacyCard(
        val name: String,
        val blood: String,
        val address: String,
        val contactName: String,
        val contactPhone: String,
        val allergy: String,
        val condition: String
    )

    private fun readLegacy(sp: SharedPreferences): LegacyCard = LegacyCard(
        name = sp.getString(SafeWalkPrefKey.emerName, "") ?: "",
        blood = sp.getString(SafeWalkPrefKey.emerBlood, "") ?: "",
        address = sp.getString(SafeWalkPrefKey.emerAddress, "") ?: "",
        contactName = sp.getString(SafeWalkPrefKey.emerContactName, "") ?: "",
        contactPhone = sp.getString(SafeWalkPrefKey.emerContactPhone, "") ?: "",
        allergy = sp.getString(SafeWalkPrefKey.emerAllergy, "") ?: "",
        condition = sp.getString(SafeWalkPrefKey.emerCondition, "") ?: ""
    )

    private fun isLegacyEmpty(l: LegacyCard): Boolean =
        l.name.isBlank() && l.blood.isBlank() && l.address.isBlank() &&
            l.contactName.isBlank() && l.contactPhone.isBlank() &&
            l.allergy.isBlank() && l.condition.isBlank()

    /** 旧血型是自由文本（如 "A+" / "AB" / "B型"），这里拆成枚举避免迁移后显示"未知" */
    private fun parseLegacyBlood(raw: String): Pair<BloodType, RhFactor> {
        if (raw.isBlank()) return BloodType.UNKNOWN to RhFactor.UNKNOWN
        val upper = raw.trim().uppercase()
        val rh = when {
            upper.contains('+') -> RhFactor.POSITIVE
            upper.contains('-') || upper.contains("阴") -> RhFactor.NEGATIVE
            else -> RhFactor.UNKNOWN
        }
        val type = when {
            "AB" in upper -> BloodType.AB
            "A" in upper -> BloodType.A
            "B" in upper -> BloodType.B
            "O" in upper -> BloodType.O
            "其他" in raw -> BloodType.OTHER
            else -> BloodType.UNKNOWN
        }
        return type to rh
    }

    private fun readLegacyCard(sp: SharedPreferences): EmergencyCard {
        val l = readLegacy(sp)
        val (bt, rh) = parseLegacyBlood(l.blood)
        val contacts = if (l.contactName.isBlank() && l.contactPhone.isBlank()) {
            emptyList()
        } else {
            listOf(
                EmergencyContact(
                    name = l.contactName,
                    phone = l.contactPhone,
                    relation = "",
                    primary = true
                )
            )
        }
        return EmergencyCard(
            name = l.name,
            bloodType = bt,
            rh = rh,
            address = l.address,
            allergy = l.allergy,
            conditions = l.condition,
            contacts = contacts,
            updatedAt = System.currentTimeMillis()
        )
    }
}
