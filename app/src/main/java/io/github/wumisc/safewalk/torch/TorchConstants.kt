package io.github.wumisc.safewalk.torch

/**
 * 手电筒模块常量定义
 */
const val ACTION_OPEN_TORCH = "io.github.wumisc.safewalk.OPEN_TORCH"
const val ACTION_CLOSE_TORCH = "io.github.wumisc.safewalk.CLOSE_TORCH"

/**
 * 通知按钮投给**应用进程**（App 自己的 TorchActionReceiver）。
 *
 * 与 ACTION_OPEN_TORCH 的区别：那条投给 SystemUI 里的 Hook，由系统身份
 * 直接调 setTorchMode；这条投给应用自己，由 TorchController 让后视预览
 * 先释放摄像头再点灯。预览开着时只有后者能成功——这是「点了没反应」的根因。
 */
const val ACTION_APP_TORCH_ON = "io.github.wumisc.safewalk.APP_TORCH_ON"
const val ACTION_APP_TORCH_OFF = "io.github.wumisc.safewalk.APP_TORCH_OFF"

const val TORCH_CHANNEL_ID = "safewalk_torch"
const val TORCH_NOTIFY_ID = 2003
