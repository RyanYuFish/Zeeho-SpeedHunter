package com.github.zeehospeedhunter.ride;

import com.github.zeehospeedhunter.core.Keys;
import com.github.zeehospeedhunter.core.RemoteSettings;

/**
 * 骑行区块开关。
 *
 * <p><b>主机制已经换掉</b>：网络层能力位改写（{@code com.github.zeehospeedhunter.net.NetPatch}）
 * 把 {@code vehicleKinds} / {@code cyclingEventStatisticFlag} 写对之后，App 会自己把
 * 「骑行分析」入口与四项统计渲染出来 —— 不需要再去猜哪个 view 被藏了。</p>
 *
 * <p>老的视图层四通道（全局扫描 + setVisibility 拦截 + onAttached + setText）保留下来
 * 作为<b>兜底</b>，默认关闭：它是「把整棵容器扳成 VISIBLE」的蛮力方案，
 * 开着会盖住网络层的效果，也没法判断到底是哪一层在起作用。</p>
 */
public final class RideOptions {

    /**
     * 视图层四通道兜底 —— 由模块 App 界面控制（{@code Keys.KEY_VIEW_LAYER}，默认关）。
     * 注意：hook 是否安装是<b>进程启动时</b>决定的，改完必须冷重启 ZEEHO App。
     */
    public static boolean viewFallback() {
        return RemoteSettings.getBool(Keys.KEY_VIEW_LAYER, false);
    }

    /** 视图兜底开着时，记录每个被恢复的数值控件（首次赋值才记，用来确认数据是否真的回填了）。 */
    public static final boolean LOG_TEXT = true;

    /** 扫描窗口：onResume 后 0～12 秒、每 500 ms 扫一遍整棵视图树。 */
    public static final int SCAN_WINDOW_MS = 12000;
    public static final int SCAN_STEP_MS = 500;

    private RideOptions() {
    }
}
