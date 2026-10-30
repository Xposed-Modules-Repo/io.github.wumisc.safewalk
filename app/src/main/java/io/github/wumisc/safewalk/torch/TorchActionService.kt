package io.github.wumisc.safewalk.torch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.IBinder
import android.widget.Toast

/**
 * 手电筒动作处理Service：绕开广播，直接处理通知按钮点击
 */
class TorchActionService : Service() {

    companion object {
        const val EXTRA_TORCH_ACTION = "torch_action"
        const val ACTION_TURN_ON = 1
        const val ACTION_TURN_OFF = 2

        fun startAction(context: Context, action: Int) {
            val intent = Intent(context, TorchActionService::class.java).apply {
                putExtra(EXTRA_TORCH_ACTION, action)
            }
            context.startService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.getIntExtra(EXTRA_TORCH_ACTION, 0)) {
                ACTION_TURN_ON -> turnOnTorch()
                ACTION_TURN_OFF -> turnOffTorch()
            }
        }
        stopSelf()
        return START_NOT_STICKY
    }

    private fun findFlashCameraId(camManager: CameraManager): String? {
        for (cameraId in camManager.cameraIdList) {
            val characteristics = camManager.getCameraCharacteristics(cameraId)
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val isBackCamera = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            if (hasFlash && isBackCamera) return cameraId
        }
        return null
    }

    private fun turnOnTorch() {
        try {
            val camManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findFlashCameraId(camManager)
            cameraId?.let {
                camManager.setTorchMode(it, true)
                Toast.makeText(this, "手电筒已开启", Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(this, "无闪光灯设备", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "手电开启失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun turnOffTorch() {
        try {
            val camManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findFlashCameraId(camManager)
            cameraId?.let {
                camManager.setTorchMode(it, false)
                Toast.makeText(this, "手电筒已关闭", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "手电关闭失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
