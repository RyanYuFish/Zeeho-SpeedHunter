package com.github.zeehospeedhunter.core;

/**
 * UI 进程与 hook 进程共享的常量。
 *
 * <p>两个进程各自加载本类（static final String / int 会在编译期内联），所以这里
 * <b>绝对不要</b>引用任何 Android / Xposed 平台类，保持纯净。</p>
 *
 * <p>配置链路：UI 写 SharedPreferences（尽力而为）+ 发广播 →
 * hook 进程的动态接收器收下 → 内存即时生效 + 落盘到 com.cfmoto 自己的外部 files 目录 →
 * 冷重启后从该文件读回。</p>
 */
public final class Keys {

    /** UI 写的 SharedPreferences（尽力而为，LSPosed 可能重定向）。 */
    public static final String PREFS = "zeeho_settings";

    /** UI → hook 的配置广播 action。 */
    public static final String ACTION_CONFIG = "com.github.zeehospeedhunter.CONFIG";

    /**
     * 广播的定向包名（= hook 所在的 App）。
     *
     * <p>★ 必须 {@code intent.setPackage(TARGET_PKG)}：Android 8（API 26）起
     * <b>隐式广播被系统限制</b>，{@code sendBroadcast(new Intent(action))} 收不到动态注册的
     * receiver（实测：UI 侧发 ACTION_CONFIG / CLEAR_DAY_STORE，hook 进程毫无反应；
     * 而 shell 的 {@code am broadcast} 因为有特权权限反而能送达，容易误判成「广播正常」）。
     * 带上 setPackage 后系统会在该包内匹配 receiver，不受该限制。</p>
     */
    public static final String TARGET_PKG = "com.cfmoto";

    /** UI → hook：请求清空按天账本（改完标定参数后要重算，旧账是旧参数算的）。 */
    public static final String ACTION_CLEAR_DAY_STORE =
            "com.github.zeehospeedhunter.CLEAR_DAY_STORE";

    /**
     * UI → hook：查询账本状态（已记几天 / 还差几天没重灌）。
     *
     * <p>为什么需要查询：day store 在 {@code com.cfmoto} 的 external files 下，
     * Android 11+ 不许别的 App 读（已实测 EACCES），所以 UI 进程只能<b>问</b>，
     * 由 hook 进程读出来用广播回传。这是 iOS 版 {@code [ClearStore] cleared N keys}
     * 那行日志的「有状态版」。</p>
     */
    public static final String ACTION_QUERY_STORE_STATE =
            "com.github.zeehospeedhunter.QUERY_STORE_STATE";

    /** hook → UI：账本状态回传（只有 hook 进程能发，UI 用 setPackage 定向接收）。 */
    public static final String ACTION_STORE_STATE =
            "com.github.zeehospeedhunter.STORE_STATE";

    /** {@link #ACTION_STORE_STATE} 的 extra：hook 侧已记账的天数。 */
    public static final String EXTRA_DAYS_ON_FILE = "days_on_file";
    /** {@link #ACTION_STORE_STATE} 的 extra：待重灌的天数。 */
    public static final String EXTRA_DAYS_PENDING = "days_pending";
    /** {@link #ACTION_STORE_STATE} 的 extra：待重灌的月份，逗号分隔（{@code 2026.07,2026.08}）。 */
    public static final String EXTRA_MONTHS_PENDING = "months_pending";

    /**
     * UI → hook：索要压弯判定过程的日志（对应 iOS 侧 {@code C.DEBUG} 打出来的那批 {@code BENDS} 行）。
     *
     * <p>为什么要专门要一次：判定过程是 <b>hook 进程</b>（com.cfmoto）算出来的，
     * 落在它自己的 {@code /sdcard/Android/data/com.cfmoto/files/zeeho_hook.log} 里；
     * Android 11+ 不许本模块进程读那个目录（已实测 EACCES），而本模块自己那份
     * {@code zeeho_hook.log} 只记本进程的东西、根本没有 {@code BENDS}。
     * ⇒ 只能由 hook 进程读出来再广播回来。</p>
     */
    public static final String ACTION_QUERY_BEND_LOG =
            "com.github.zeehospeedhunter.QUERY_BEND_LOG";

    /** hook → UI：判定过程日志回传。extra 见 {@link #EXTRA_BEND_LOG}。 */
    public static final String ACTION_BEND_LOG = "com.github.zeehospeedhunter.BEND_LOG";

    /** {@link #ACTION_BEND_LOG} 的 extra：最近若干行 {@code BENDS}，用 {@code \n} 拼。 */
    public static final String EXTRA_BEND_LOG = "bend_log";

    /**
     * UI → hook：启动仪表投屏（EasyConnect 镜像）。
     *
     * <p>为什么需要这个广播：投屏的私有镜像编解码在 {@code libECSDK.so} 里，
     * 只在 {@code com.cfmoto} 进程里加载；模块 UI 进程没有这套库，无法自己推镜像帧。
     * ⇒ 模块 UI 只负责<b>手机侧网络层</b>（加入车机 AP，见 {@code ota.CarWifi}），
     * 真正「拉起投屏」由本广播交给 hook（同 UID，能启动 app 内部未 exported 的投屏 Activity）。</p>
     */
    public static final String ACTION_MIRROR_START =
            "com.github.zeehospeedhunter.MIRROR_START";

    /** UI → hook：断开投屏（释放车机 AP 的 specifier 请求由 UI 侧 CarWifi.release 做）。 */
    public static final String ACTION_MIRROR_STOP =
            "com.github.zeehospeedhunter.MIRROR_STOP";

    /** {@link #ACTION_MIRROR_START} 的 extra：车机 AP 的 SSID。 */
    public static final String EXTRA_MIRROR_SSID = "mirror_ssid";
    /** {@link #ACTION_MIRROR_START} 的 extra：车机 AP 的密码（开放网络可空）。 */
    public static final String EXTRA_MIRROR_PWD = "mirror_pwd";

    /** hook 进程持久化配置的文件名（在其自身外部 files 目录下）。 */
    public static final String CONFIG_FILE = "zeeho_config.json";

    // ---- 四个开关 ----
    /** 主开关：网络层能力位改写（= iOS Shadowrocket 脚本做的事）。 */
    public static final String KEY_WEB_LAYER = "web_layer";
    /** G1：vehicleKinds = 2（骑行分析入口 + 极速）。 */
    public static final String KEY_GATE_ANALYSE = "gate_analyse";
    /** G2：cyclingEventStatisticFlag = true（急加速 / 急刹 / 压弯）。 */
    public static final String KEY_GATE_EVENT = "gate_event";
    /** 兜底：旧视图层四通道（把控件沿祖先链点亮）。 */
    public static final String KEY_VIEW_LAYER = "view_layer";
    /** 规避 MobTech SDK 在 Android 16 上的启动崩溃。 */
    public static final String KEY_MOB_GUARD = "mob_guard";
    /**
     * 骑行数据回填（急刹 / 压弯）—— 从 {@code ridetrack_v2} 的轨迹还原
     * {@code brakesTimes} / {@code bendingTimes}，默认<b>开</b>。
     */
    public static final String KEY_RIDE_FILL = "ride_fill";
    /**
     * 回填时顺带放大 {@code ridetrack_v2} 的 {@code pageSize}（默认开）——
     * 「历史轨迹」按月发请求且没有上拉加载，不放大就只剩每月最近 20 趟。
     */
    public static final String KEY_RIDE_FULLSCAN = "ride_fullscan";

    // ---- 压弯 / 急刹标定参数（「压弯标定」页滑块，运行时可调）----
    // 全部以字符串形式落在 zeeho_config.json 里；缺失时 RideConfig 用 DEF_* 默认值。
    /** 压弯速度下限 km/h（默认 20，与 iOS 定稿一致；文档 v1.0 给的 10 实测召回过高）。 */
    public static final String KEY_BEND_V_MIN = "bend_v_min";
    /** 单步转角阈值 度（默认 5）。 */
    public static final String KEY_BEND_SEED = "bend_seed";
    /** 最小累计转角 度（默认 15，与 iOS v16 定稿一致）。 */
    public static final String KEY_BEND_CUM_MIN = "bend_cum_min";
    /** 累计转角上限 度（默认 90）。 */
    public static final String KEY_BEND_CUM_CAP = "bend_cum_cap";
    /** 压弯判定系数（默认 1.0，整体缩放）。 */
    public static final String KEY_BEND_COEF = "bend_coef";
    /** 压弯速度上限 km/h（默认 80）。 */
    public static final String KEY_BEND_V_MAX = "bend_v_max";
    /** 急刹标定系数（默认 2.5）。 */
    public static final String KEY_BRAKE_CALIB = "brake_calib";
    /** 最短行程门槛 km（默认 1.0；短于此里程的行程整趟记 0 次压弯）。 */
    public static final String KEY_BEND_MIN_KM = "bend_min_km";
    /** 是否把原始轨迹样本导出到文件（离线扫参数用，默认关）。 */
    public static final String KEY_TUNE_EXPORT = "tune_export";
    /**
     * 是否逐 run 打印压弯判定过程（默认关）。
     *
     * <p>对应 iOS 侧 {@code ZeehoRideFill.js} 的 {@code C.DEBUG} + {@code debugPrint()}：
     * 打印每个候选 run 的 cum / 方向 / 段内最高速，以及被拒的原因。</p>
     */
    public static final String KEY_DEBUG_BEND = "debug_bend";

    /**
     * 手机端日志悬浮窗开关。
     *
     * <p>保留这个键只为<b>状态展示</b>：悬浮窗只在 {@code ui.OtaActivity}「最后一公里操作台」
     * 里挂（进页面 attachHud、退出 onDestroy 收起），模块主页与 {@code UiApp} 启动都不再挂。
     * 键本身仍可被 hook 进程读到，将来做「环境体检 → 直接上屏」那条路时能复用。</p>
     */
    public static final String KEY_HUD = "hud_enabled";

    private Keys() {
    }
}
