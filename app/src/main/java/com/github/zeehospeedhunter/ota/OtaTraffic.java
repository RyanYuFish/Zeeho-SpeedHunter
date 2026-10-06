package com.github.zeehospeedhunter.ota;

import android.content.Intent;
import android.webkit.WebView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import com.github.zeehospeedhunter.core.HookKit;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.core.Targets;

/**
 * OTA 流量记录：请求行、响应体、固件包线索、WebView 地址、服务 Intent。
 *
 * <p>只读不写 —— 全部是 after/before 打点，不修改任何请求或响应内容。</p>
 */
public final class OtaTraffic {

    private static final Set<String> LOGGED_CLASSES =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> LOGGED_PROBE =
            Collections.synchronizedSet(new HashSet<String>());

    private OtaTraffic() {
    }

    // ==================== 请求行 ====================

    static void hookRequests() {
        for (final String className : OtaOptions.OKHTTP_REQUEST_BUILDERS) {
            HookKit.hookIfExists(HookLog.OTA, className, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    logRequest(className, param.getResult());
                }
            });
        }
        // okdownload 是升级包下载器：Builder 与 DownloadTask 的 setUrl 都挂上
        for (final String className : OtaOptions.OKDOWNLOAD_BUILDERS) {
            HookKit.hookIfExists(HookLog.OTA, className, "setUrl",
                    downloadUrlLogger(), String.class);
        }
        for (final String className : OtaOptions.OKDOWNLOAD_TASKS) {
            HookKit.hookIfExists(HookLog.OTA, className, "setUrl",
                    downloadUrlLogger(), String.class);
        }
    }

    private static XC_MethodHook downloadUrlLogger() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                HookLog.log(HookLog.OTA + " okdownload url = " + param.args[0]);
            }
        };
    }

    private static void logRequest(String source, Object request) {
        if (request == null) return;
        try {
            Object method = XposedHelpers.callMethod(request, "method");
            Object url = XposedHelpers.callMethod(request, "url");
            String urlText = url == null ? "null" : url.toString();
            String marker = isSuspectDownload(urlText) ? "★ " : "";
            HookLog.log(HookLog.HTTP + " " + marker + method + " " + urlText
                    + "  <" + source + ">");
            // ★★ 车机热点地址：App 把包传给车机时走这里，是本项目最关键的未知流量
            if (isInstrumentHotspot(urlText)) {
                HookLog.log(HookLog.OTA + " ★★★ 车机上传请求 " + method + " " + urlText
                        + "   ← 抓到了！这就是推送协议");
            }
            if (OtaOptions.LOG_REQUEST_BODY) {
                logRequestBody(method, urlText, request);
            }
        } catch (Throwable t) {
            HookLog.log(HookLog.HTTP + " log failed: " + t);
        }
    }

    /**
     * 是否指向车机热点。
     *
     * <p>官方流程：手机开热点（网关 {@code 192.168.43.1}），车机连上来，
     * 然后 App 用「网络协议」把升级包 HTTP 传给车机。抓到这个请求就等于
     * 拿到了推送协议本身（路径 / 鉴权头 / 是整包还是分片）。</p>
     *
     * <p>扫的网段：{@code 192.168.43.x}（Android 热点默认）、
     * {@code 192.168.4x.x}、以及 {@code 10.0.0.x}/{@code 10.0.1.x}（车机 AP 常见）。</p>
     */
    private static boolean isInstrumentHotspot(String urlText) {
        if (urlText == null) return false;
        return urlText.contains("192.168.43.")      // Android 热点默认网关
                || urlText.contains("192.168.4")    // 车机 AP 常见段
                || urlText.contains("/upload")
                || urlText.contains("/ota")
                || urlText.contains("192.168.31.")   // 部分车机用这个段
                || urlText.contains("192.168.42.");
    }

    /** 记录 POST/PUT/PATCH 的请求体（okio Buffer 走一遍，不消费原始流）。 */
    private static void logRequestBody(Object method, String urlText, Object request) {
        try {
            String m = String.valueOf(method);
            if (!"POST".equals(m) && !"PUT".equals(m) && !"PATCH".equals(m)) {
                return;
            }
            for (String skip : OtaOptions.REQUEST_BODY_SKIP) {
                if (urlText.contains(skip)) {
                    return;
                }
            }
            Object reqBody = XposedHelpers.callMethod(request, "body");
            if (reqBody == null) {
                return;
            }
            long len = -1L;
            try {
                len = (Long) XposedHelpers.callMethod(reqBody, "contentLength");
            } catch (Throwable ignore) {
            }
            if (len > OtaOptions.MAX_REQUEST_BODY_BYTES) {
                HookLog.log(HookLog.HTTP + " req-body " + urlText
                        + "  (too big: " + len + " B, skipped)");
                return;
            }
            ClassLoader cl = HookKit.classLoader();
            if (cl == null) {
                return;
            }
            Object buf = XposedHelpers.newInstance(XposedHelpers.findClass("okio.Buffer", cl));
            XposedHelpers.callMethod(reqBody, "writeTo", buf);
            String content = (String) XposedHelpers.callMethod(buf, "readUtf8");
            if (content != null && !content.isEmpty()) {
                if (content.length() > 4096) {
                    content = content.substring(0, 4096) + "…(" + content.length() + ")";
                }
                HookLog.log(HookLog.HTTP + " req-body " + urlText + "  << " + content);
            }
        } catch (Throwable t) {
            HookLog.log(HookLog.HTTP + " req-body failed: " + t);
        }
    }

    // ==================== 固件包探针 ====================

    /**
     * 固件包探针：挂 {@code Response$Builder#build()}，一次同时拿到 URL 与体积。
     * 只打印「URL 可疑」或「体积 ≥ 1 MB」的响应，避免刷屏。
     */
    static void hookDownloadProbe() {
        for (final String className : OtaOptions.OKHTTP_RESPONSE_BUILDERS) {
            HookKit.hookIfExists(HookLog.OTA, className, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    probeResponse(param.getResult());
                }
            });
        }
    }

    private static void probeResponse(Object response) {
        if (response == null) return;
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            Object url = request == null ? null : XposedHelpers.callMethod(request, "url");
            String urlText = url == null ? "" : url.toString();

            Object body = XposedHelpers.callMethod(response, "body");
            long length = -1L;
            String contentType = "?";
            if (body != null) {
                Object rawLength = XposedHelpers.callMethod(body, "contentLength");
                if (rawLength instanceof Number) length = ((Number) rawLength).longValue();
                Object rawType = XposedHelpers.callMethod(body, "contentType");
                if (rawType != null) contentType = rawType.toString();
            }

            boolean big = length >= OtaOptions.BIG_RESPONSE_BYTES;
            boolean otaUrl = containsAny(urlText, OtaOptions.BODY_URL_HINTS);
            boolean suspect = isSuspectDownload(urlText);

            // OTA 接口族单独列一行，方便在日志里一眼定位
            if (otaUrl && !big) {
                logOnce(LOGGED_PROBE, HookLog.OTA + " ★ " + formatBytes(length)
                        + " " + contentType + " " + urlText);
            } else if (big || suspect) {
                logOnce(LOGGED_PROBE, HookLog.OTA + " ★ " + (big ? "大体积" : "疑似固件")
                        + " " + formatBytes(length) + " " + contentType + " " + urlText);
            }

            captureBody(urlText, contentType, length, response, otaUrl);
        } catch (Throwable ignored) {
            // 探针绝不能影响正常请求
        }
    }

    private static void logOnce(Set<String> seen, String line) {
        if (seen.add(line)) {
            HookLog.log(line);
        }
    }

    /**
     * 非破坏性响应体捕获。
     *
     * <p>这 App 用 Retrofit + Gson 解析，读的是 {@code charStream()} 而不是 {@code string()}，
     * 所以挂在 {@code ResponseBody#string()} 上一条都抓不到。改用 okhttp 的
     * {@code Response#peekBody()} —— 它把 body 拷一份出来，原始流不受影响。</p>
     */
    private static void captureBody(String urlText, String contentType, long length,
                                    Object response, boolean otaUrl) {
        if (length > OtaOptions.PEEK_BODY_LIMIT && length > 0) return;   // 已知是固件等大文件，不读
        if (!otaUrl && !isTextual(contentType)) return;
        try {
            Object peeked = XposedHelpers.callMethod(response, "peekBody",
                    OtaOptions.PEEK_BODY_LIMIT);
            if (peeked == null) return;
            Object text = XposedHelpers.callMethod(peeked, "string");
            if (!(text instanceof String)) return;
            String body = (String) text;
            if (body.isEmpty()) return;
            if (!otaUrl && !isInterestingBody(body)) return;
            HookLog.log(HookLog.OTA + " body " + urlText + "  ->  " + abbreviate(body, 20000));
        } catch (Throwable ignored) {
            // peekBody 在部分 okhttp 版本 / 流已消耗时会抛，忽略即可
        }
    }

    // ==================== 响应体（string() 通道） ====================

    /** 兜底通道：有些 okhttp 调用点仍走 {@code ResponseBody#string()}。 */
    static void hookResponseBodies() {
        for (final String className : OtaOptions.OKHTTP_RESPONSE_BODIES) {
            HookKit.hookIfExists(HookLog.OTA, className, "string", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof String)) return;
                    String body = (String) result;
                    if (!isInterestingBody(body)) return;
                    HookLog.log(HookLog.OTA + " body = " + abbreviate(body, 4000));
                }
            });
        }
    }

    private static boolean isInterestingBody(String body) {
        if (body == null || body.isEmpty()) return false;
        for (String keyword : OtaOptions.BODY_KEYWORDS) {
            if (body.contains(keyword)) return true;
        }
        // 兜底：统一响应格式里的成功码，且体积很小（避免把大 JSON 全打出来刷屏）
        return body.contains("\"code\":\"10000\"") && body.length() <= 2000;
    }

    // ==================== WebView 与服务 ====================

    /** OTA 说明页就是 detail 接口返回的 versionDesc，用 WebView 渲染。 */
    static void hookWebView() {
        XC_MethodHook logger = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || param.args[0] == null) return;
                HookLog.log(HookLog.WEBVIEW + " " + param.args[0]);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(WebView.class, "loadUrl", String.class, logger);
        } catch (Throwable t) {
            HookLog.log(HookLog.WEBVIEW + " loadUrl(String) failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(WebView.class, "loadUrl",
                    String.class, Map.class, logger);
        } catch (Throwable t) {
            HookLog.log(HookLog.WEBVIEW + " loadUrl(String,Map) failed: " + t);
        }
    }

    static void hookServices() {
        for (final String className : Targets.OTA_SERVICES) {
            HookKit.hookIfExists(HookLog.OTA, className, "onStartCommand", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    HookLog.log(HookLog.OTA + " " + className
                            + " <- " + describeIntent(param.args[0]));
                }
            }, Intent.class, int.class, int.class);
        }
    }

    private static String describeIntent(Object intent) {
        if (intent == null) return "null";
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("action=").append(XposedHelpers.callMethod(intent, "getAction"));
            Object data = XposedHelpers.callMethod(intent, "getDataString");
            if (data != null) sb.append(" data=").append(data);
            Object extras = XposedHelpers.callMethod(intent, "getExtras");
            if (extras != null) sb.append(" extras=").append(extras);
        } catch (Throwable t) {
            sb.append("<").append(t).append(">");
        }
        return sb.toString();
    }

    // ==================== 诊断 ====================

    /** 打印加载到的 okhttp / okdownload / ota 类名，用于确认重定位后的真实包名。 */
    static void hookClassDiscovery() {
        HookKit.hookIfExists(HookLog.OTA, "java.lang.ClassLoader", "loadClass",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object raw = param.args[0];
                        if (!(raw instanceof String)) return;
                        String name = (String) raw;
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (!lower.contains("okhttp") && !lower.contains("okdownload")
                                && !lower.contains("ota") && !lower.contains("upgrade")) {
                            return;
                        }
                        if (LOGGED_CLASSES.add(name)) {
                            HookLog.log(HookLog.CLASS + " " + name);
                        }
                    }
                }, String.class, boolean.class);
    }

    // ==================== 小工具 ====================

    private static boolean isTextual(String contentType) {
        if (contentType == null) return false;
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.contains("json") || type.contains("text")
                || type.contains("xml") || type.contains("html");
    }

    private static boolean containsAny(String value, String[] needles) {
        if (value == null) return false;
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static boolean isSuspectDownload(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (containsAny(lower, OtaOptions.SUSPECT_HOST_PARTS)) return true;
        int query = lower.indexOf('?');
        String path = query >= 0 ? lower.substring(0, query) : lower;
        for (String ext : OtaOptions.SUSPECT_EXTENSIONS) {
            if (path.endsWith(ext)) return true;
        }
        return false;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "size=?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static String abbreviate(String value, int max) {
        if (value == null) return "null";
        return value.length() <= max
                ? value
                : value.substring(0, max) + "...(共 " + value.length() + " 字符)";
    }
}
