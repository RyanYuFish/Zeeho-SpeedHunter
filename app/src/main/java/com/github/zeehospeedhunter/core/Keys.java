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

    private Keys() {
    }
}
