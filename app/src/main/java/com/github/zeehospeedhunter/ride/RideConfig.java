package com.github.zeehospeedhunter.ride;

import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.core.Keys;
import com.github.zeehospeedhunter.core.RemoteSettings;

/**
 * 骑行指标计算的全部可调参数（对应技术文档里的 {@code Config.java}）。
 *
 * <p><b>为什么全部改成运行时可读</b>：压弯 / 急刹的阈值是<b>标定值</b>，不是物理常数。
 * 实测把 {@code BEND_V_MIN} 从 25 降到 10 之后召回高得离谱（7 月整月 1211 次压弯，
 * 而人肉体感一天只有个位数到十几次）—— 这种东西必须能在车上直接调、当场看效果，
 * 而不是改一个常量、编译、装机、重灌一遍。
 * 现在每个参数都能在模块首页的「压弯标定」页里拖动滑块，实时生效（走
 * {@code RemoteSettings} 广播，hook 进程内存即时生效 + 落盘，冷重启也还在）。</p>
 *
 * <p><b>红线</b>：参数只影响模块自己算出来的数，不影响请求/响应的任何原始字段。
 * 服务端给了真值的地方永远以服务端为准（见 {@code RideFill#isZero} 的调用点）。</p>
 *
 * <p>改参数后记得点「清空 day store 再回填」—— 已经落盘的汇总是用旧参数算的，
 * 不会因为改了参数而自动重算（原始轨迹不常驻，只留汇总）。</p>
 */
public final class RideConfig {

    // ==================== 参数默认值 ====================
    // ↓↓↓ 下面这些 DEFAULT_* 同时也是 UI 滑块的初始位置，改默认值只要改这里 ↓↓↓

    /** 压弯速度下限默认值（km/h）。 */
    public static final double DEF_BEND_V_MIN = 20;
    /** 单步转角阈值默认值（度）。 */
    public static final double DEF_BEND_SEED = 5;
    /**
     * 最小累计转角默认值（度）。
     *
     * <p>★ 取 <b>15</b>，与 iOS 侧定稿严格一致（{@code iOS-rocket/ZeehoRideFill.js} v15
     * 的 {@code C.BEND_CUM_MIN: 15}）。</p>
     *
     * <p><b>为什么曾经写成 30</b>：本项目早期按技术文档 v1.0 的 {@code BEND_V_MIN: 10}
     * 跑，7 月整月算出 1211 次压弯（152 次/100km），体感明显偏高。当时为了压住总量，
     * 把 cum_min 从 15 提到 30 —— <b>但这个理由是错的</b>：真正把数推爆的是
     * {@code v_min=10}（降到 20 后密度 152→85），而不是 cum_min。
     * 16 趟真实轨迹实测：cum=15 → 135 次/100km，cum=30 → 85 次/100km，
     * <b>cum=15 是 cum=30 的 1.60 倍</b>，而 30 并没有「更准」的依据 ——
     * iOS 侧的 15 有一整条骑行的人工矫正背书（「7/12 那天压了 4 次」）。</p>
     *
     * <p>⇒ 现在跟 iOS 对齐取 15。7 月合计会从 616 涨到约 985（85→135 次/100km），
     * 这个量级与服务端在有真值那天的密度（126 次/100km）同档，是合理的。</p>
     */
    public static final double DEF_BEND_CUM_MIN = 15;
    /** 累计转角上限默认值（度）。 */
    public static final double DEF_BEND_CUM_CAP = 90;
    /** 压弯判定系数默认值。 */
    public static final double DEF_BEND_COEF = 1.0;
    /** 急刹标定系数默认值。 */
    public static final double DEF_BRAKE_CALIB = 2.5;
    /** 压弯速度上限默认值（km/h）。 */
    public static final double DEF_BEND_V_MAX = 80;
    /**
     * 最短行程门槛默认值（km）—— 短于此里程的行程整趟记 0 次压弯。
     *
     * <p>★ 这是<b>最有效</b>的一个旋钮。实测（16 趟真实轨迹样本）按里程分档的压弯密度：</p>
     * <pre>
     *   &lt;0.5km  → 787 次/100km   ← 纯噪声，GPS 在停车状态下乱飘
     *   0.5-1km  →   0 次/100km
     *   1-3km    → 198 次/100km
     *   3-10km   →  90 次/100km   ← 正常
     *   ≥10km    →  98 次/100km   ← 正常
     * </pre>
     * <p>即：短行程的密度是长途的 <b>8 倍</b>，而 7 月有 16 趟、8 月 38 趟、9 月 27 趟
     * 属于 &lt;1km —— 它们贡献了大量虚高数字。直接把这类行程判 0 最干净。</p>
     */
    public static final double DEF_BEND_MIN_KM = 1.0;

    /** 急刹：速度下限（km/h）—— 固定，不进 UI。 */
    public static final double BRAKE_V_MIN = 15;
    /** 急刹：大窗口（秒）。 */
    public static final int BRAKE_WIN_LARGE = 5;
    /** 急刹：小窗口（秒）。 */
    public static final int BRAKE_WIN_SMALL = 3;
    /** 急刹：大窗口最小 Δv（km/h）。 */
    public static final double BRAKE_DV_LARGE = 15;
    /** 急刹：小窗口最小 Δv（km/h）。 */
    public static final double BRAKE_DV_SMALL = 20;
    /** 急刹：最小减速度（m/s²）。 */
    public static final double BRAKE_A_MIN = 0.5;
    /** 同一次刹车内候选点的最小间隔（点数）。 */
    public static final int BRAKE_MERGE_GAP = 5;
    /** 急刹：最短历时（秒）。 */
    public static final double BRAKE_MIN_DUR = 1.5;

    /** 压弯：单步转角超过这个就不信（GPS 抖动 / 掉点）（度）。 */
    public static final double BEND_STEP_MAX = 45;
    /** 压弯：一个弯最少占几个点。 */
    public static final int BEND_RUN_MIN = 1;
    /** 压弯：同向弯合并间隔（点数），0 = 不合并。 */
    public static final int BEND_MERGE_GAP = 0;
    /** 压弯：异向弯之间允许空几个点仍算同一个弯，0 = 过渡点直接断开。 */
    public static final int BEND_ALLOW_GAP = 0;
    /** 短于此距离的相邻点对不参与航向计算（米）。 */
    public static final double BEND_SEG_DIST_MIN = 6;
    /** 相邻点时间差超过这个就断段（毫秒）。 */
    public static final double BEND_GAP_MS_MAX = 25000;

    /** 移动时长判定速度阈值（km/h）。 */
    public static final double MOVING_V_MIN = 5;
    /** 判定「有效采样点」的最少点数。 */
    public static final int MIN_POINTS = 4;
    /** 地球半径（米）。 */
    public static final double EARTH_R = 6371000;

    /** 导出轨迹样本时每趟最多写多少个点（避免把 external files 撑爆）。 */
    public static final int EXPORT_MAX_POINTS = 1500;

    private RideConfig() {
    }

    // ==================== 运行时参数（UI 可调） ====================

    /**
     * 压弯速度下限（km/h）。
     *
     * <p>★ 最影响召回的参数：25→10 会把市区低速转弯、小区掉头全算进来
     * （实测 7 月从「6 天 20 条」变成「21 天 81 条、整月 1211 次」，明显偏多）。
     * 调到 15~20 比较接近人肉体感。</p>
     */
    public static double bendVMin() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_V_MIN, DEF_BEND_V_MIN), 1, 60);
    }

    /** 单步转角阈值（度）：调大 → 只认明显的转弯，漏掉小弯。 */
    public static double bendSeed() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_SEED, DEF_BEND_SEED), 1, 20);
    }

    /** 最小累计转角（度）：★ 第二个有效旋钮，调大 → 一个小弯的多次抖动合成一次压弯。 */
    public static double bendCumMin() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_CUM_MIN, DEF_BEND_CUM_MIN), 5, 90);
    }

    /** 累计转角上限（度）：超过就不算弯（原地画圈）。 */
    public static double bendCumCap() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_CUM_CAP, DEF_BEND_CUM_CAP), 20, 180);
    }

    /** 压弯判定系数：最终结果 = 原始计数 × 本值，纯粹的整体缩放。 */
    public static double bendCoef() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_COEF, DEF_BEND_COEF), 0.1, 2.0);
    }

    /**
     * 最短行程门槛（km）：短于此里程的行程整趟记 0 次压弯。
     *
     * <p>★ 实测最有效的旋钮 —— 短行程的压弯密度是长途的 8 倍（见 {@link #DEF_BEND_MIN_KM}）。</p>
     */
    public static double bendMinKm() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_MIN_KM, DEF_BEND_MIN_KM), 0, 20);
    }

    /** 压弯速度上限（km/h）：超过整段丢弃（高速过弯不算压弯）。 */
    public static double bendVMax() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BEND_V_MAX, DEF_BEND_V_MAX), 30, 120);
    }

    /** 急刹标定系数：双窗口会把同一脚刹车数两次，乘回去。 */
    public static double brakeCalib() {
        return clamp(RemoteSettings.getDouble(Keys.KEY_BRAKE_CALIB, DEF_BRAKE_CALIB), 1.0, 5.0);
    }

    /** 是否把原始轨迹样本导出到文件（标定用；导出后可在电脑上离线扫参数）。 */
    public static boolean exportTrack() {
        return RemoteSettings.getBool(Keys.KEY_TUNE_EXPORT, false);
    }

    /**
     * 是否逐 run 打印压弯判定过程（<b>复刻 iOS 侧 {@code C.DEBUG} + {@code debugPrint()}</b>）。
     *
     * <p>打开后每趟行程的日志里会多一行：候选 run 的下标区间、累计转角、方向、段内最高速，
     * 以及被拒的原因（{@code cum<min / cum>cap / speed>max / short / merged}）。</p>
     *
     * <p><b>默认关</b>：每趟一行、每行最多 900 字符，7 月 81 趟就是 81 行，
     * 翻日志会被刷屏。调参时用 {@code tune_export} 那个开关一起打开即可。</p>
     */
    public static boolean debugBend() {
        return RemoteSettings.getBool(Keys.KEY_DEBUG_BEND, false);
    }

    /** 诊断开关的出厂默认（UI 侧滑块初始位置用；恒为关）。 */
    public static boolean debugBendDefault() {
        return false;
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || v <= 0) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    /** 一行摘要，进日志方便对账。 */
    public static String describe() {
        return String.format(java.util.Locale.ROOT,
                "bend{vMin=%.0f seed=%.0f cum=%.0f..%.0f coef=%.2f vMax=%.0f minKm=%.2f}"
                        + " brake{calib=%.2f}",
                bendVMin(), bendSeed(), bendCumMin(), bendCumCap(),
                bendCoef(), bendVMax(), bendMinKm(), brakeCalib());
    }

    static void logConfig() {
        HookLog.log(HookLog.RIDE + " config " + describe());
    }
}
