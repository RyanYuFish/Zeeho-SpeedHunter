package com.github.zeehospeedhunter.net;

import com.github.zeehospeedhunter.core.Keys;
import com.github.zeehospeedhunter.core.RemoteSettings;

/**
 * 网络层「能力位改写」的开关与常量 —— 和行为相关的一切都集中在这里。
 *
 * <p><b>为什么改成这样</b>：iOS 端 8 轮消融（见 {@code iOS-rocket/ablation-test/docs/04-iOS消融实验-结果.md}）
 * 已经把隐藏 gate 定位死了 —— 客户端自己读服务端下发的两个能力位：</p>
 *
 * <table>
 *   <tr><td>{@code vehicleKinds = 2}</td><td>→ 「骑行分析」入口 + 极速</td></tr>
 *   <tr><td>{@code cyclingEventStatisticFlag = true}</td><td>→ 加速 / 急刹 / 压弯</td></tr>
 * </table>
 *
 * <p>剩下的 {@code regulationType} / {@code vehType} / {@code vehicleType} 三个字段已证实冗余，
 * 而且伪装 {@code vehicleType} 会让 App 误认车型（AE8），有车辆控制风险 —— 一律不碰。</p>
 *
 * <p>所以 Android 侧不再靠「抓住每个 view 强改 visibility」这条老路（它是把整套 UI 扳到 VISIBLE，
 * 属于猜九宫格：容器一拆就漏），而是<b>在网络层把能力位写对</b>，让 App 自己把 UI 显示出来。
 * 显示出来的数是服务端真值，不是伪造值 —— 这一条与 iOS 方案一致。</p>
 *
 * <p><b>红线</b>：只改「能力位」，不改任何数据字段（maxSpeed / accelerationTimes …也不改）。
 * 骑行数据与 iOS 方案的 {@code analyse/analyse}、{@code myRideInfo} 一样是原样放行的。</p>
 */
public final class NetOptions {

    // ==================== 开关（运行时从模块 UI 读取） ====================

    /**
     * 网络层能力位改写总开关 —— 由模块 App 的界面控制（{@code Keys.KEY_WEB_LAYER}），
     * 经 {@code RemoteSettings} 读取，5 秒缓存。关掉则整个 {@code net} 区块变成 no-op。
     */
    public static boolean patchCapability() {
        return RemoteSettings.getBool(Keys.KEY_WEB_LAYER, true);
    }

    /** G1：写 {@code vehicleKinds} —— 骑行分析入口 + 极速（{@code Keys.KEY_GATE_ANALYSE}）。 */
    public static boolean gateAnalyse() {
        return RemoteSettings.getBool(Keys.KEY_GATE_ANALYSE, true);
    }

    /** G2：写 {@code cyclingEventStatisticFlag} —— 加速 / 急刹 / 压弯（{@code Keys.KEY_GATE_EVENT}）。 */
    public static boolean gateEvent() {
        return RemoteSettings.getBool(Keys.KEY_GATE_EVENT, true);
    }

    // ==================== 匹配 ====================

    /** G1 目标值（服务端给本车的是 {@code 1}；{@code 2} = 支持骑行分析）。 */
    public static final int ANALYSE_ON = 2;

    /**
     * URL 白名单片段。留空 = 不按 URL 过滤，只看响应体里有没有那两个 key
     * —— 这样无论 App 把能力位挂在 vehicle/list、vehicleHomePageV2 还是骑行列表
     * （实测骑行列表的每个 item 里也带这两个 key，值为 null）都能命中。
     */
    public static final String[] URL_HINTS = {};

    public static final String KEY_ANALYSE_GATE = "vehicleKinds";
    public static final String KEY_EVENT_GATE = "cyclingEventStatisticFlag";

    /**
     * 只处理体量小于这个值的响应。
     *
     * <p>骑行列表响应里带轨迹点，动辄几百 KB；给它设个上限，超了就放弃改写
     * —— 宁可不生效，也不拖慢 App 或吃爆内存。</p>
     */
    public static final long MAX_PATCH_BYTES = 2L * 1024 * 1024;

    // ==================== 日志 ====================

    /** 记录被改写的接口、字段原值（同样组合只记一次）。 */
    public static final boolean LOG_PATCH = true;

    /** 记录「见过能力位但无需改写」的接口与最终值 —— 排查 App 换了判定源时打开。 */
    public static final boolean LOG_SEEN_UNCHANGED = false;

    // ==================== 网络锚点 ====================

    /**
     * okhttp Response.Builder —— 每个响应都由它构造，挂 build() 能一次拿到 URL + 完整响应对象。
     * 候选名逐个尝试：目标 App 被爱加密加固后，库可能被重定位；哪个存在就挂哪个。
     */
    public static final String[] RESPONSE_BUILDERS = {
            "okhttp3.Response$Builder",
            "com.cfmoto.okhttp3.Response$Builder",
            "com.cfmoto.shadow.okhttp3.Response$Builder",
    };

    private NetOptions() {
    }
}
