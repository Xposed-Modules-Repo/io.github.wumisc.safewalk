package io.github.wumisc.safewalk

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

/**
 * 医疗 QR 生成（ZXing core，纯 Java，无 GMS / 无网络）。
 *
 * 只在用户点「生成 QR」时调用一次；Bitmap 由调用方在 onDestroy 回收，不缓存。
 * QR 内容为 [EmergencyCard.qrPayload] 的紧凑 JSON 明文，隐私权衡由 allowQr 开关约束。
 */
object MedicalQr {
    private const val DEFAULT_SIZE = 512

    @JvmStatic
    @JvmOverloads
    fun encode(card: EmergencyCard, size: Int = DEFAULT_SIZE): Bitmap? = try {
        val hints: MutableMap<EncodeHintType, Any> = HashMap()
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.MARGIN] = 1
        val matrix: BitMatrix = MultiFormatWriter().encode(
            card.qrPayload(), BarcodeFormat.QR_CODE, size, size, hints
        )
        toBitmap(matrix, size, size)
    } catch (t: Throwable) {
        EmergencyDiag.event(EmergencyDiag.P_EMERGENCY, "qr_generate_failed: ${t.javaClass.simpleName}")
        null
    }

    private fun toBitmap(matrix: BitMatrix, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
