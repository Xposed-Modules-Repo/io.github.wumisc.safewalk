package io.github.wumisc.safewalk.torch

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * 手电筒控制工具类，封装 Camera2 TorchMode API
 */
object TorchHelper {
    fun setTorch(context: Context, enable: Boolean): Boolean {
        return try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetCameraId = findFlashCameraId(camManager)
            targetCameraId?.let {
                camManager.setTorchMode(it, enable)
                true
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 遍历摄像头列表，找到有闪光灯的后置摄像头ID
     */
    private fun findFlashCameraId(camManager: CameraManager): String? {
        for (cameraId in camManager.cameraIdList) {
            val characteristics = camManager.getCameraCharacteristics(cameraId)
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val isBackCamera = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            if (hasFlash && isBackCamera) {
                return cameraId
            }
        }
        return null
    }
}
