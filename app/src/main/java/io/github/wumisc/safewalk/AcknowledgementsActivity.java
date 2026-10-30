package io.github.wumisc.safewalk;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * 致敬名单页面：列出所有参考的开源项目。
 */
public class AcknowledgementsActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_acknowledgements);

        TextView tv = findViewById(R.id.tv_ack_list);
        tv.setText(
            "SafeWalk 是面向 HyperOS / Android 的 LSPosed 出行感知模块，" +
            "实现不依赖 GMS 的步行状态识别。项目大量参考优秀开源项目的算法、架构思路，" +
            "特此感谢各位开发者的贡献：\n\n" +

            "【步态检测】\n" +
            "1. bagilevi/android-pedometer\n" +
            "   纯加速度计步器经典实现：峰值检测、步频区间过滤、自适应阈值的基础思路。\n\n" +

            "2. mdeguzis/StepCounter\n" +
            "   滑动窗口方差统计实现、根据运动状态动态切换传感器采样率的省电策略。\n\n" +

            "3. ashishb/android-step-detector\n" +
            "   基于 FFT 频谱分析识别周期性步态的方案。\n\n" +

            "4. AccStepCounter\n" +
            "   低功耗计步滤波算法实现。\n\n" +

            "5. SensorFusion\n" +
            "   传感器姿态融合、自适应阈值算法。\n\n" +

            "【状态机与系统服务】\n" +
            "6. tonyo567/hysteresis-state-machine\n" +
            "   分离式迟滞状态机思想，独立配置进入防抖、退出防抖计时器。\n\n" +

            "7. p-v/Android-SensorService\n" +
            "   Android 后台传感器监听、系统采样节流、丢采样容错方案。\n\n" +

            "8. UrbanBigDataCentre/meowmotion\n" +
            "   GPS 轨迹分割、停留/移动分类、连续位移校验逻辑。\n\n" +

            "【HyperOS 通知】\n" +
            "9. HyperBridge\n" +
            "   HyperOS 超级岛自定义通知卡片引擎思路。\n\n" +

            "10. WidgetEnhancer\n" +
            "   通知快捷按钮、系统手电筒实现思路。\n\n" +

            "11. HyperLight\n" +
            "   HyperOS 悬浮通知相关研究。\n\n" +

            "【姿态与GPS】\n" +
            "12. KalebKE/FSensor\n" +
            "   Madgwick/Mahony 姿态滤波、卡尔曼 GPS 平滑算法。\n\n" +

            "————————————\n" +
            "声明：SafeWalk 所有代码为独立手写重构，仅借鉴思路与算法原理，" +
            "没有直接拷贝上述项目源码。遵循 GPL-3.0 许可证开源。"
        );
    }
}
