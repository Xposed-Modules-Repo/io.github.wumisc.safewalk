package io.github.wumisc.safewalk.torch

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * 手电操作结果。
 *
 * 以前 setTorch 只返回 Boolean，失败时唯一的信息是「false」。
 * 真机上「手电不能用」有两种完全不同的原因，靠 Boolean 分不出来：
 *   · 相机被占用（本应用的后视预览正开着）→ 必须先把预览让出来；
 *   · 系统/厂商策略禁用（CAMERA_DISABLED）→ 让路也没用。
 * 分开之后上层才能决定是「重试」还是「告诉用户原因」。
 */
data class TorchResult(
    val ok: Boolean,
    /** 失败原因：in_use / disabled / disconnected / no_flash / error */
    val reason: String = "",
    /** 给用户看的说明 */
    val message: String = ""
) {
    val inUse: Boolean get() = reason == "in_use"
}

/**
 * 手电筒控制工具类，封装 Camera2 TorchMode API。
 *
 * 注意：setTorchMode 不需要任何权限，但**同一颗摄像头同一时刻只能有一个使用者**。
 * 本应用自己的后视预览（WalkPreviewService）开着时，这里必然抛 CAMERA_IN_USE。
 */
object TorchHelper {

    private const val TAG = "SafeWalk-Torch"

    fun setTorch(context: Context, enable: Boolean): Boolean = setTorchEx(context, enable).ok

    fun setTorchEx(context: Context, enable: Boolean): TorchResult {
        val camManager = try {
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        } catch (t: Throwable) {
            null
        } ?: return TorchResult(false, "error", "取不到相机服务")

        val targetCameraId = try {
            findFlashCameraId(camManager)
        } catch (t: Throwable) {
            Log.e(TAG, "枚举摄像头失败", t)
            null
        } ?: return TorchResult(false, "no_flash", "这台设备没有可用的闪光灯")

        return try {
            camManager.setTorchMode(targetCameraId, enable)
            TorchResult(true)
        } catch (e: CameraAccessException) {
            val reason = when (e.reason) {
                CameraAccessException.CAMERA_IN_USE,
                CameraAccessException.MAX_CAMERAS_IN_USE -> "in_use"
                CameraAccessException.CAMERA_DISABLED -> "disabled"
                CameraAccessException.CAMERA_DISCONNECTED -> "disconnected"
                else -> "error"
            }
            Log.w(TAG, "setTorchMode 失败 reason=${e.reason} → $reason", e)
            TorchResult(
                false, reason,
                when (reason) {
                    "in_use" -> "后置摄像头正被占用"
                    "disabled" -> "系统已禁用摄像头"
                    "disconnected" -> "摄像头连接已断开"
                    else -> "相机服务异常"
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "setTorchMode 异常", t)
            TorchResult(false, "error", t.message ?: "未知错误")
        }
    }

    /**
     * 遍历摄像头列表，找到有闪光灯的后置摄像头ID
     */
    private fun findFlashCameraId(camManager: CameraManager): String? {
        for (cameraId in camManager.cameraIdList) {
            val characteristics = camManager.getCameraCharacteristics(cameraId)
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val isBackCamera = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            if (hasFlash && isBackCamera) {
                return cameraId
            }
        }
        return null
    }
}
