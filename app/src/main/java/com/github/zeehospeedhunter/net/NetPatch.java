package com.github.zeehospeedhunter.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import com.github.zeehospeedhunter.core.HookKit;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.ride.RideFill;
import com.github.zeehospeedhunter.ride.RideOptions;

/**
 * 网络层能力位改写：把 {@code vehicleKinds} / {@code cyclingEventStatisticFlag} 两个
 * 「服务端下发的车型能力位」改成支持值，剩下的交给 App 自己 —— 入口、容器、数值
 * 全由 App 按正常逻辑渲染。
 *
 * <p>做法与 {@code Response#peekBody()} 同源：先把 body 拷一份出来读（不影响原始流），
 * 按 key 递归改写后用 {@code Response#newBuilder()#body(...)} 换一个新 body 塞回结果，
 * 全程不碰 {@code data} 里的任何真实数据字段。</p>
 *
 * <p>为什么不写死某个 URL：Android 抓包里这两个 key 出现在多个位置
 * （车辆首页 / 车辆列表 / 骑行列表 item 里都带，其中骑行列表里是 {@code null}），
 * 所以这里按「响应体里有没有这两个 key」来判断，不依赖接口名，App 换接口也不会失效。</p>
 */
public final class NetPatch {

    private static final String TAG = HookLog.NET;

    /** 同一组合只打一次日志 —— 骑行列表一个响应里可能带几十个 item。 */
    private static final Set<String> LOGGED = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    /** 防止 {@code newBuilder().build()} 递归进自己的 hook。 */
    private static final ThreadLocal<Boolean> REBUILDING = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private NetPatch() {
    }

    public static void installAll() {
        // 不在安装期 gate —— attach 时配置可能还没读到（Application 未就绪），
        // 开关在 tryPatch 里每次实时判断，关了就是 no-op
        for (final String className : NetOptions.RESPONSE_BUILDERS) {
            HookKit.hookIfExists(TAG, className, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(REBUILDING.get())) return;
                    tryPatch(className, param);
                }
            });
        }
        HookLog.log(TAG + " installed: G1=" + shortGate(NetOptions.gateAnalyse(),
                NetOptions.KEY_ANALYSE_GATE, NetOptions.ANALYSE_ON)
                + " G2=" + shortGate(NetOptions.gateEvent(), NetOptions.KEY_EVENT_GATE, true)
                + " web=" + NetOptions.patchCapability());
    }

    private static String shortGate(boolean on, String key, Object value) {
        return (on ? key + "=" + value : key + "=<off>");
    }

    // ==================== 核心 ====================

    private static void tryPatch(String builderName, XC_MethodHook.MethodHookParam param) {
        if (!NetOptions.patchCapability()) return;   // UI 开关实时可关
        Object response = param.getResult();
        if (response == null) return;

        try {
            String url = readUrl(response);
            if (!isCandidate(url)) return;

            Object originalBody = XposedHelpers.callMethod(response, "body");
            if (originalBody == null) return;

            // ridetrack_v2 被 ride.RideFetch 把 pageSize 放大到 200，响应体会到 MB 级，
            // 所以这个 URL 走单独的上限，否则刚放宽的请求又被这里挡掉（回填永远不生效）。
            long maxBytes = url.contains("ridetrack_v2")
                    ? com.github.zeehospeedhunter.ride.RideFetch.pageSizeBytes()
                    : NetOptions.MAX_PATCH_BYTES;

            Object rawLength = XposedHelpers.callMethod(originalBody, "contentLength");
            if (rawLength instanceof Number
                    && ((Number) rawLength).longValue() > maxBytes) {
                return;
            }

            // peekBody 拷一份出来读，原始流留给 App（Retrofit + Gson 读的是 charStream）
            Object peeked = XposedHelpers.callMethod(response, "peekBody", maxBytes);
            if (peeked == null) return;
            Object rawText = XposedHelpers.callMethod(peeked, "string");
            if (!(rawText instanceof String)) return;
            String body = (String) rawText;
            if (body.isEmpty()) return;

            // 先做廉价子串判断，避免给每个响应都做一次 JSON 解析
            boolean hasAnalyseKey = body.contains(NetOptions.KEY_ANALYSE_GATE);
            boolean hasEventKey = body.contains(NetOptions.KEY_EVENT_GATE);

            JSONObject root = new JSONObject(body);
            Tally tally = new Tally();
            com.github.zeehospeedhunter.ride.RideFill.Result fill = null;

            // 骑行回填先跑（ridetrack_v2 / myRideInfo / analyse 三个 handler），
            // 能力位改写后跑 —— 都改同一棵 JSON 树，最后只重建一次响应体。
            //
            // ★ 这里不能再加「没有 gate key 才回填」的守卫：ridetrack_v2 的响应体里
            // 同时带着 vehicleKinds / cyclingEventStatisticFlag（App 拿它决定要不要上传
            // 骑行事件），所以 hasAnalyseKey / hasEventKey 恒为 true。若拿它当门槛，
            // 唯一能提供 trajectory 的接口就被自己挡在门外，回填永远跑不起来
            //（表现：日志只有 analyse/event 命中，没有 [ZeehoRide] ... done）。
            // 真正的分流在 RideFill.mayFill(url, body) 里，它只认三个接口的特征字段。
            if (RideOptions.rideFill() && RideFill.mayFill(url, body)) {
                fill = RideFill.fill(root, url);
            }

            if (hasAnalyseKey || hasEventKey) {
                walk(root, url, tally);
            }

            boolean fillChanged = fill != null && fill.changed;
            if (!tally.changed && !fillChanged) {
                if (NetOptions.LOG_SEEN_UNCHANGED) {
                    logOnce("seen " + shortUrl(url) + " -> already on"
                            + " (analyse=" + tally.analyse + " event=" + tally.event + ")");
                }
                return;
            }

            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            Object newBody = createBody(builderName, originalBody, bytes);
            if (newBody == null) {
                logOnce("create body failed, skipped " + shortUrl(url));
                return;
            }

            REBUILDING.set(Boolean.TRUE);
            Object patched;
            try {
                Object builder = XposedHelpers.callMethod(response, "newBuilder");
                XposedHelpers.callMethod(builder, "body", newBody);
                // 长度变了就把旧的 content-length 纠正；原始 body 可能压缩过，这里已经是明文
                XposedHelpers.callMethod(builder, "removeHeader", "content-encoding");
                XposedHelpers.callMethod(builder, "header", "content-length",
                        String.valueOf(bytes.length));
                patched = XposedHelpers.callMethod(builder, "build");
            } finally {
                REBUILDING.set(Boolean.FALSE);
            }
            param.setResult(patched);
            logOnce("patched " + shortUrl(url)
                    + "  analyse=" + tally.analyse + " event=" + tally.event
                    + (fillChanged ? ("  fill{rides=" + fill.rides + " days=" + fill.days + "}") : "")
                    + "  (" + bytes.length + " B)");
        } catch (Throwable t) {
            // 改写失败必须静默 —— 宁可没效果，也不能让 App 请求崩掉
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            logOnce("patch failed (" + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage() + ")");
        }
    }

    /** 递归改写：不假设字段层级，任何一层出现目标 key 都改。 */
    private static void walk(Object node, String url, Tally tally) throws Throwable {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (NetOptions.KEY_ANALYSE_GATE.equals(key)) {
                    if (NetOptions.gateAnalyse()) {
                        Object old = obj.opt(key);
                        if (!isInt(old, NetOptions.ANALYSE_ON)) {
                            tally.analyse++;
                            tally.changed = true;
                            if (NetOptions.LOG_PATCH) {
                                logOnce("G1 OLD " + key + "=" + old
                                        + " -> " + NetOptions.ANALYSE_ON
                                        + "  @ " + shortUrl(url));
                            }
                            obj.put(key, NetOptions.ANALYSE_ON);
                        }
                    }
                    continue;
                }
                if (NetOptions.KEY_EVENT_GATE.equals(key)) {
                    if (NetOptions.gateEvent()) {
                        Object old = obj.opt(key);
                        if (!Boolean.TRUE.equals(old)) {
                            tally.event++;
                            tally.changed = true;
                            if (NetOptions.LOG_PATCH) {
                                logOnce("G2 OLD " + key + "=" + old + " -> true"
                                        + "  @ " + shortUrl(url));
                            }
                            obj.put(key, true);
                        }
                    }
                    continue;
                }
                Object child = obj.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    walk(child, url, tally);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    walk(child, url, tally);
                }
            }
        }
    }

    // ==================== 工具 ====================

    private static boolean isInt(Object value, int target) {
        // 服务端可能给 null（骑行列表里就是 null），这里一律视为「需要改写」
        return value instanceof Integer && ((Integer) value).intValue() == target;
    }

    private static String readUrl(Object response) {
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            if (request == null) return "";
            Object url = XposedHelpers.callMethod(request, "url");
            return url == null ? "" : url.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isCandidate(String url) {
        if (url == null || url.isEmpty()) return false;
        if (NetOptions.URL_HINTS.length == 0) return true;
        String lower = url.toLowerCase(Locale.ROOT);
        for (String hint : NetOptions.URL_HINTS) {
            if (lower.contains(hint.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** 造一个新 ResponseBody：create(MediaType, byte[]) 优先，create(MediaType, String) 兜底。 */
    private static Object createBody(String builderName, Object originalBody, byte[] bytes) {
        ClassLoader cl = HookKit.classLoader();
        if (cl == null) return null;
        try {
            int dot = builderName.lastIndexOf('.');
            String pkg = dot > 0 ? builderName.substring(0, dot) : builderName;
            Class<?> bodyClass = XposedHelpers.findClass(pkg + ".ResponseBody", cl);
            Object mediaType = XposedHelpers.callMethod(originalBody, "contentType");
            try {
                return XposedHelpers.callStaticMethod(bodyClass, "create", mediaType, bytes);
            } catch (Throwable ignored) {
                return XposedHelpers.callStaticMethod(bodyClass, "create", mediaType,
                        new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            HookLog.log(TAG + " createBody failed: " + t);
            return null;
        }
    }

    private static String shortUrl(String url) {
        if (url == null) return "?";
        int scheme = url.indexOf("://");
        String path = scheme >= 0 ? url.substring(scheme + 3) : url;
        if (path.length() <= 90) return path;
        return path.substring(0, 90) + "...";
    }

    private static void logOnce(String message) {
        if (LOGGED.add(message)) {
            HookLog.log(TAG + " " + message);
        }
    }

    /** 一次改写里改了多少个 key —— 用来区分「响应里根本没有这个 key」和「改了但没生效」。 */
    private static final class Tally {
        int analyse;
        int event;
        boolean changed;
    }
}
