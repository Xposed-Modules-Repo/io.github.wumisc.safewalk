package io.github.wumisc.safewalk

import android.os.SystemClock
import java.security.SecureRandom
import java.util.Base64

/**
 * 【v1.1 新增】一次性显示载荷内存槽（App 进程内，绝不落盘）。
 *
 * 四条硬约束：
 * ① 只内存：@Volatile 字段，进程死即消失，不写文件/SP/日志；
 * ② 一次性：take 命中立即 slot=null，第二次 take 恒空（防重放/重复渲染）；
 * ③ TTL 20s：以 SystemClock.elapsedRealtime() 计量，不受用户改系统时间影响；
 * ④ 强随机 token：SecureRandom 16 字节 → Base64-URL（128bit 熵，≥22 字符）。
 */
object DisplayPayloadStore {
    private data class Slot(
        val token: String,
        val fields: DisplayFields,
        val createdAtElapsed: Long,
        val eventId: String
    )

    private val lock = Any()
    private val random = SecureRandom()

    /** 当前槽内 token（仅用于 invalidate 兜底，调用方不得写入日志） */
    @Volatile
    private var slot: Slot? = null

    /** 发布最小载荷，返回一次性 token */
    @JvmStatic
    fun publish(fields: DisplayFields, eventId: String): String {
        val token = newToken()
        synchronized(lock) {
            slot = Slot(token, fields, SystemClock.elapsedRealtime(), eventId)
        }
        return token
    }

    /**
     * 用 token 取件：命中即焚。
     *
     * @param nowElapsed 调用方传入的 elapsedRealtime（DisplayProvider 里现取），
     *                   便于单测注入时间验证 TTL。
     */
    @JvmStatic
    fun take(token: String?, nowElapsed: Long): DisplayFields? {
        if (token.isNullOrBlank()) return null
        synchronized(lock) {
            val s = slot ?: return null
            if (s.token != token) return null
            if (nowElapsed - s.createdAtElapsed > SafeWalkPrefKey.DISPLAY_TTL_MS) {
                slot = null
                return null
            }
            slot = null // 用后即焚，不可逆
            return s.fields
        }
    }

    /** Activity 可见后兜底清槽：只有 token 匹配才清，避免误清下一次事故 */
    @JvmStatic
    fun invalidate(token: String?) {
        if (token.isNullOrBlank()) return
        synchronized(lock) {
            if (slot?.token == token) slot = null
        }
    }

    /** tick 里检查 TTL：过期清槽并返回 true（Hook 侧据此 hide 覆盖层） */
    @JvmStatic
    fun expireIfDue(nowElapsed: Long): Boolean {
        synchronized(lock) {
            val s = slot ?: return false
            if (nowElapsed - s.createdAtElapsed > SafeWalkPrefKey.DISPLAY_TTL_MS) {
                slot = null
                return true
            }
            return false
        }
    }

    @JvmStatic
    fun currentToken(): String? = slot?.token

    @JvmStatic
    fun clear() {
        synchronized(lock) { slot = null }
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
