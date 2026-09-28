package io.github.codex.zeehospeed.ota;

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
 * <p><b>红线</b>：本区块只做「界面解锁 + 流量记录」，不伪造、不替换任何服务端数据。
 * 升级包是否存在由服务端决定 —— 红点接口返回的 {@code fileInfoList} 为 {@code null}
 * 就是没有，构造一个假的只会把来路不明的固件推给车辆。</p>
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

    /** 记录 WebView 加载的地址（OTA 说明页 = detail 接口返回的 versionDesc）。 */
    public static final boolean LOG_WEBVIEW = true;

    /** 记录 OTA 服务的 Intent 参数。 */
    public static final boolean LOG_SERVICE = true;

    /** 诊断：打印 okhttp / okdownload / ota 相关类名，用来发现被重定位的真实类名。 */
    public static final boolean DISCOVER_CLASSES = false;

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
    public static final long PEEK_BODY_LIMIT = 65536L;

    private OtaOptions() {
    }

    private static Set<String> unmodifiable(String... names) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(names)));
    }
}
