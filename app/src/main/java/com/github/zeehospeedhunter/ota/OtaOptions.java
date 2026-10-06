package com.github.zeehospeedhunter.ota;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * OTA 区块的开关与常量表 —— 调整行为只改这里，不用碰 hook 代码。
 *
 * <p>背景：ZEEHO 3.x 的业务代码被爱加密（ijiami）整体加密在 {@code assets/ijiami.dat}，
 * 反编译拿不到类名。所以这里只用三类「加固后依然稳定」的锚点：</p>
 * <ol>
 *   <li>AndroidManifest.xml 里的 OTA 组件类名；</li>
 *   <li>layout 里控件资源名（对照 {@code res/values/ids.xml} 逐个核对过）；</li>
 *   <li>公开的 HTTP 客户端类名（okhttp / okdownload，被重定位时按候选逐个尝试）。</li>
 * </ol>
 *
 * <p><b>红线</b>：本区块默认只做「界面解锁 + 流量记录」，不伪造服务端数据。
 * 升级包是否存在由服务端决定 —— 红点接口返回的 {@code fileInfoList} 为 {@code null}
 * 就是没有，构造一个假的只会把来路不明的固件推给车辆。</p>
 *
 * <p><b>2026-10-03 例外</b>：为观察「App 走完整升级流程时到底发出什么指令」，
 * 新增了 {@link #INJECT_OTA} 一条注入（默认<b>关</b>）。它只改「有没有升级任务」，
 * 且 {@code sign} 填目标包真实 MD5 由 App 自校验，填错只会下载失败。
 * 用完请把它关回 {@code false}。</p>
 */
public final class OtaOptions {

    // ==================== 开关 ====================

    /** 强制显示升级入口与「立即升级」按钮，并隐藏「已是最新版本」分支。 */
    public static final boolean UNLOCK_UI = true;

    /** 记录所有 okhttp 请求行（方法 + URL）。 */
    public static final boolean LOG_HTTP = true;

    /** 记录响应体：OTA 接口族无条件打，其余按 {@link #BODY_KEYWORDS} 过滤。 */
    public static final boolean LOG_RESPONSE = true;

    /** 记录大体积响应与 OSS / CDN 请求 —— 用来把固件包下载从一堆小请求里摘出来。 */
    public static final boolean LOG_DOWNLOAD = true;

    /** 记录 POST/PUT 请求体（车控与设置指令的明文参数，是键→名字映射的关键）。 */
    public static final boolean LOG_REQUEST_BODY = true;

    /** 请求体不记录的 URL 片段（高频打点，刷屏无意义）。 */
    public static final String[] REQUEST_BODY_SKIP = {"dbpoint/send"};

    /** 请求体最大捕获字节数。 */
    public static final long MAX_REQUEST_BODY_BYTES = 65536L;

    /** 记录 WebView 加载的地址（OTA 说明页 = detail 接口返回的 versionDesc）。 */
    public static final boolean LOG_WEBVIEW = true;

    /** 记录 OTA 服务的 Intent 参数。 */
    public static final boolean LOG_SERVICE = true;

    /** 诊断：打印 okhttp / okdownload / ota 相关类名，用来发现被重定位的真实类名。 */
    public static final boolean DISCOVER_CLASSES = false;

    // ==================== OTA 包注入（配合本地 HTTP 服务）====================

    /**
     * 总开关：把「红点」接口的「无更新」响应改写成有更新（见 {@link OtaInject}）。
     *
     * <p>⚠️ 这一条<b>推翻了本类原先「不伪造服务端数据」的红线</b>，是刻意为之：
     * 目标机已是最新版本，不改网络层就没有任何升级流程可观察。
     * 注入的 {@code sign} 是目标 zip 的真实 MD5，App 自己会校验 ——
     * 填错只会下载失败，不会有来路不明的固件被推给车辆。</p>
     */
    public static final boolean INJECT_OTA = true;

    /** 注入的包文件名（要带 .zip，App 按扩展名走解压流程）。 */
    public static final String INJECT_FILE_NAME = "1787815351970919959.zip";

    /**
     * 注入的下载地址。
     *
     * <p>★ 2026-10-05 改成 {@code http://10.104.164.105:18080/…}（本机热点网关实测地址）。
     * 原来写的是 {@code 127.0.0.1:18080} + {@code adb reverse} 回 Mac，那是「Mac 当喂包机」的方案；
     * 但车机走 wlan0 STA 连的是**手机热点**，车机解析不了手机的 127.0.0.1 ⇒ 必须填手机热点网关 IP。
     * 用 adb shell 实测 {@code curl http://<热点IP>:18080/…} 返回 200 / 106225120 B。</p>
     *
     * <p>⚠️ 热点网关 IP 会变（Android 常用 192.168.43.1 / 10.0.2.1 / 10.104.x.1 等）。
     * 换网络后先跑 {@code adb shell ip addr show wlan1}（或 {@code ip -4 addr}）拿真实 IP 再改这里。</p>
     */
    public static final String INJECT_FILE_URL =
            "http://10.104.164.105:18080/1787815351970919959.zip";

    /**
     * 注入地址里用于「认得是自己注入的」标记（避免重复改写、也便于日志识别）。
     * 改 {@link #INJECT_FILE_URL} 的 host/端口时同步改这里。
     */
    public static final String INJECT_HOST_MARK = "10.104.164.105:18080";

    /**
     * 目标 zip 的真实 MD5（App 会自行校验，对不上直接失败）。
     *
     * <p>= v2 包（同步过 ISP 脚本内 3 处硬编码分块 MD5）：
     * {@code dist/flashpack/OTA_zip_PATCHED_185_v2.zip}，见 docs/34。</p>
     */
    public static final String INJECT_FILE_MD5 = "4dee18b6bb4cc661e8f64cd08ef0cfa1";

    /** 目标 zip 的真实字节数。 */
    public static final long INJECT_FILE_SIZE = 106225120L;

    /** 任意 firmwareId；沿用原厂那次真实值，避免服务端/App 侧另有校验。 */
    public static final String INJECT_FIRMWARE_ID = "5653";

    /** 桶名：原厂是私有桶 `wlb-cfmoto-private`，这里只是占位，实际下载走 fileUrl。 */
    public static final String INJECT_FILE_BUCKET = "wlb-cfmoto-private";

    /** 注入的 {@code status}（11 = 「发现新的OTA升级」，取自 docs/05 的真实抓包）。 */
    public static final int INJECT_STATUS = 11;

    public static final String INJECT_TITLE = "发现新的OTA升级";

    public static final String INJECT_CONTENT = "车辆仪表升级";

    /**
     * 把「立即更新」按钮强制置为可点。
     *
     * <p>实测（2026-10-03）：注入 red-point 后按钮被 {@link OtaPageUnlock} 强制显示，
     * 但 {@code clickable=false} —— App 内部仍判定「无更新」，点不动。</p>
     */
    public static final boolean FORCE_UPDATE_CLICK = true;

    /**
     * 注入 {@code ota/mmi/info/v2}（真正的升级任务单）。
     *
     * <p>只注入 red-point 时 App 会显示「发现新的OTA升级」，但 mmi/info 返回
     * {@code upgradeStatus:-1} 后它就停住了 —— 不下载也不推送。所以这条必须一起开。</p>
     *
     * <p>注入内容不是写死在代码里的，而是读外置文件
     * {@code /sdcard/Android/data/com.cfmoto/files/zeeho_mmi_override.json}，
     * 见 {@link OtaMmiInject} —— 这样试不同 {@code upgradeStatus} 不用重新编译。</p>
     */
    public static final boolean INJECT_MMI = true;

    // ==================== 手机侧 HTTP 服务（喂给车机）====================

    /** 总开关：在 App 进程里起一个只读 HTTP 服务，把 {@link #SERVE_FILE} 发出去。 */
    public static final boolean SERVE_PATCH = true;

    /**
     * 要发出的文件（patched zip）—— 按序挑第一个「存在且可读」的。
     *
     * <p>⚠️ 为什么不是直接写 {@code /sdcard/Download}：那一份属主是别的 uid（{@code u0_a286}），
     * 权限 {@code 0660}，{@code com.cfmoto} 读不到（实测 EACCES）。
     * App 自己的外部目录 {@code /sdcard/Android/data/com.cfmoto/files/} 才稳。</p>
     */
    public static final String[] SERVE_FILES = {
            "/sdcard/Android/data/com.cfmoto/files/oss/1787815351970919959.zip",
            "/sdcard/Android/data/com.cfmoto/files/1787815351970919959.zip",
            "/sdcard/Download/1787815351970919959.zip",
            "/data/local/tmp/1787815351970919959.zip",
    };

    /** 监听端口。车机的 url 要填 {@code http://<手机热点网关IP>:18080/<文件名>}。 */
    public static final int SERVE_PORT = 18080;

    // ==================== 最后一公里：Wi-Fi Direct + ADB 推送 ====================

    /**
     * 持久组 {@code DIRECT-y2-Android_H1dg} 的 PSK（手机 {@code p2p_supplicant.conf} 里读出来的）。
     *
     * <p>两边曾经组过网，所以先试复用；复用不上再 {@code createGroup()} 走兜底。
     * 车机是 Group Owner，正常路径用不上这个 passphrase（PBC 配对的另一半是它自己）。</p>
     */
    public static final String P2P_PASSPHRASE = "VQD9Hb1J";

    /** P2P 建连总超时（发现 + 连接 + 兜底建组）。 */
    public static final long P2P_TIMEOUT_MS = 60000L;

    /** 写进 {@code upgrade_json_file.txt} 的 version（车机版本白名单看的是仪表版本，不是这个）。 */
    public static final String PUSH_VERSION = "1.0.1";

    /**
     * 扣扳机的命令序列，按顺序在车机上执行（刷写真正开始的地方）。
     *
     * <p>前两条来自 {@code otamax.png}：第三方「极核仪表 OTA 工具」日志里的
     * {@code touch /datacache/ota/local_upgrade_flag.txt} 与 {@code ioctl1(/dev/cfmoto_cfcp)}，
     * 它的成功判据是「VerifyChecksum SUCCESS / ioctl1 success」。
     * 第三条 {@code setprop persist.sys.upgrade} 是 docs/32 里记的车机侧触发方式之一，
     * 留作 ② 被拒时的并列的通路。</p>
     */
    public static final String[] TRIGGER_COMMANDS = {
            "touch /datacache/ota/local_upgrade_flag.txt && echo FLAG_OK",
            "echo 1 > /dev/cfmoto_cfcp 2>&1 || echo IOCTL_DENIED",
            "setprop persist.sys.upgrade 1 2>&1 || echo SETPROP_FAIL",
            "sleep 3; tail -n 200 /log/messages 2>/dev/null | grep -i -E 'upgrade|checksum|ioctl|isp' | tail -n 20",
    };

    // ==================== 界面解锁 ====================

    /** 解锁时强制显示：升级入口、升级按钮、进度与结果区。 */
    public static final Set<String> FORCE_VISIBLE = unmodifiable(
            "cl_ota", "cl_ota_update", "cl_ota_detail", "ota_bottom",
            "ota_group_action", "ota_group_parent",
            "action_up", "action_appointment",
            "iv_ota_update", "tv_ota", "tv_ota_red");

    /** 解锁时强制隐藏：「已是最新版本 / 升级完成」分支。 */
    public static final Set<String> FORCE_GONE = unmodifiable(
            "fl_completed_or_no_version", "rl_no_new_version",
            "tv_newversion", "new_version_bt");

    /** 被置灰时要重新启用的按钮。只改 enabled，不碰点击监听。 */
    public static final Set<String> BUTTONS = unmodifiable(
            "action_up", "action_appointment", "tv_button");

    // ==================== 网络锚点 ====================

    public static final String[] OKHTTP_REQUEST_BUILDERS = {
            "okhttp3.Request$Builder",
            "com.cfmoto.okhttp3.Request$Builder",
            "com.cfmoto.shadow.okhttp3.Request$Builder",
    };

    /** 每个响应都由 {@code Response$Builder#build()} 构造，挂这里能同时拿到 URL 与体积。 */
    public static final String[] OKHTTP_RESPONSE_BUILDERS = {
            "okhttp3.Response$Builder",
            "com.cfmoto.okhttp3.Response$Builder",
            "com.cfmoto.shadow.okhttp3.Response$Builder",
    };

    public static final String[] OKHTTP_RESPONSE_BODIES = {
            "okhttp3.ResponseBody",
            "com.cfmoto.okhttp3.ResponseBody",
    };

    /** okdownload 是升级包下载器：Builder 与 DownloadTask 的 setUrl 都挂上。 */
    public static final String[] OKDOWNLOAD_BUILDERS = {
            "com.liulishuo.okdownload.DownloadTask$Builder",
            "com.cfmoto.com.liulishuo.okdownload.DownloadTask$Builder",
            "com.cfmoto.liulishuo.okdownload.DownloadTask$Builder",
    };

    public static final String[] OKDOWNLOAD_TASKS = {
            "com.liulishuo.okdownload.DownloadTask",
            "com.cfmoto.com.liulishuo.okdownload.DownloadTask",
            "com.cfmoto.liulishuo.okdownload.DownloadTask",
    };

    // ==================== 内容过滤 ====================

    /**
     * 命中任意一项就打印响应体。已覆盖抓到的三个 OTA 接口：
     * red-point（fileInfoList / preDownloadAppSet）、detail（versionDesc / lastlyVersion）、
     * record（只有 data 数组，靠响应码兜底）。
     */
    public static final String[] BODY_KEYWORDS = {
            "fileInfoList",                  // red-point：固件清单（含下载直链）
            "multiDownloadUpgradeContent",   // red-point：多包升级
            "preDownloadAppSet",             // red-point：预下载开关
            "versionDesc",                   // detail：更新日志 HTML 地址
            "lastlyVersion",                 // detail：可升级版本
            "nowVersion",                    // detail：当前版本
            "vehiclePicUrl",                 // detail：车型图
            "estimateMinutes",               // detail：预计耗时
            "estimatedTimeDescribe",         // detail：耗时描述
            "vehicleType",                   // detail：车型代号
    };

    /** 命中即无条件打印响应体的 URL 片段（OTA 接口族数量很少，不会刷屏）。 */
    public static final String[] BODY_URL_HINTS = {
            "/ota/", "red-point", "mmi/info", "pre/download", "versions/newest",
    };

    /** 判定「疑似固件包下载」的域名片段。 */
    public static final String[] SUSPECT_HOST_PARTS = {
            "aliyuncs.com", ".oss.", "oss-", "myqcloud.com", "qiniucdn",
            "cloudfront", "cdn", "dls.", "download", "firmware",
    };

    /** 判定「疑似固件包下载」的路径后缀。 */
    public static final String[] SUSPECT_EXTENSIONS = {
            ".bin", ".zip", ".img", ".pkg", ".ota", ".upd", ".swu",
            ".hex", ".tar", ".gz", ".dat", ".apk",
    };

    // ==================== 阈值 ====================

    /** 超过这个体积就当「大文件下载」，固件包一般几十 MB 起。 */
    public static final long BIG_RESPONSE_BYTES = 1024L * 1024L;

    /** peekBody 上限：只在响应体构造时拷这一段，不消耗原始流。 */
    public static final long PEEK_BODY_LIMIT = 262144L;

    private OtaOptions() {
    }

    private static Set<String> unmodifiable(String... names) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(names)));
    }
}
