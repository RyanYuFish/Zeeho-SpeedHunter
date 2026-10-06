package com.github.zeehospeedhunter.ota;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import com.github.zeehospeedhunter.core.HookKit;
import com.github.zeehospeedhunter.core.HookLog;
import com.github.zeehospeedhunter.net.NetOptions;

/**
 * OTA 包注入：把「红点」接口的响应改写成指向<b>本机自建 HTTP 服务</b>的升级任务。
 *
 * <p><b>背景</b>（2026-10-03）：车辆已是最新版本，
 * {@code app/ota/v2/red-point/content} 返回
 * {@code status:0, fileInfoList:null}，App 因此不显示升级入口。
 * 参考 iOS 上已验证的做法：不改 App 本体，只在网络层把「无更新」换成「有更新」，
 * 再把 {@code fileUrl} 指向我们自己准备的包。</p>
 *
 * <p><b>为什么 fileUrl 用 http:// 而不是原厂的 https://oss-cfmoto-private…</b>：
 * 原厂桶是私有的（{@code wlb-cfmoto-private}），我们没有上传权限。
 * 改成手机能访问的本机地址后，App 用现成的 okdownload 走完全相同的下载+校验+推送流程。
 * 这一步的意义不在「下载」本身，而在<b>让 App 走完它自己的升级流程</b>，
 * 从而拿到第 22 节文档里缺的那段「OTA 指令」格式。</p>
 *
 * <p><b>红线</b>：本类只改「有没有升级任务」这一个语义，
 * 不改版本号、不改车辆设置、不碰任何车控指令。
 * {@code sign} 必须填目标 zip 的真实 MD5 —— App 会自己校验，对不上会直接失败，
 * 不会把包推给车。</p>
 */
public final class OtaInject {

    private static final String TAG = HookLog.OTA;

    /** 目标接口：红点（决定 App 显不显示升级入口）。 */
    private static final String URL_RED_POINT = "red-point/content";

    /** 同一 URL 只打一次日志（用 Set.from(map) 拿到并发安全的 Set）。 */
    private static final Set<String> LOGGED = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    /** 防止 {@code newBuilder().build()} 递归进自己的 hook。 */
    private static final ThreadLocal<Boolean> REBUILDING = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private OtaInject() {
    }

    public static void installAll() {
        if (!OtaOptions.INJECT_OTA) {
            HookLog.log(TAG + " inject disabled by options");
            return;
        }
        for (final String className : NetOptions.RESPONSE_BUILDERS) {
            HookKit.hookIfExists(TAG, className, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(REBUILDING.get())) return;
                    tryPatch(className, param);
                }
            });
        }
        HookLog.log(TAG + " inject installed (url=" + URL_RED_POINT + ")");
    }

    // ==================== 核心 ====================

    private static void tryPatch(String builderName, XC_MethodHook.MethodHookParam param) {
        Object response = param.getResult();
        if (response == null) return;
        try {
            String url = readUrl(response);
            if (url == null || !url.contains(URL_RED_POINT)) return;

            Object originalBody = XposedHelpers.callMethod(response, "body");
            if (originalBody == null) return;
            // 同一个 Response 可能被多处触发（App 缓存 + 刷新）；第二次读 body 会抛
            // IllegalStateException("Cannot read raw response body of a converted body")，
            // 那是「已经注入过了」的信号，直接跳过而不是当失败。
            Object peeked;
            try {
                peeked = XposedHelpers.callMethod(response, "peekBody", 1 << 20);
            } catch (Throwable alreadyConverted) {
                logOnce("already injected (body converted), skipped");
                return;
            }
            if (peeked == null) return;
            Object rawText = XposedHelpers.callMethod(peeked, "string");
            if (!(rawText instanceof String)) return;
            String body = (String) rawText;
            if (body.isEmpty()) return;

            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                // 有更新但结构不同（实测正常有更新时也是 data 对象），跳过
                logOnce("unexpected shape, skipped");
                return;
            }
            // 已经有真包 ⇒ 不覆盖（避免把客服刚推的真升级任务顶掉）
            JSONArray list = data.optJSONArray("fileInfoList");
            if (list != null && list.length() > 0) {
                String first = list.optJSONObject(0) == null ? null
                        : list.optJSONObject(0).optString("fileUrl", "");
                if (first != null && first.contains(OtaOptions.INJECT_HOST_MARK)) {
                    logOnce("already injected, keep");     // 是我们自己注入的
                } else {
                    logOnce("server already has fileInfoList(" + list.length()
                            + "), keep original");            // 客服推的真任务
                }
                return;
            }

            // 构造假包：字段名/类型照抄原厂下发过的结构（见 docs/05 抓包）
            JSONObject item = new JSONObject();
            item.put("fileName", OtaOptions.INJECT_FILE_NAME);
            item.put("fileUrl", OtaOptions.INJECT_FILE_URL);
            item.put("sign", OtaOptions.INJECT_FILE_MD5);
            item.put("fileSize", String.valueOf(OtaOptions.INJECT_FILE_SIZE));
            item.put("firmwareId", OtaOptions.INJECT_FIRMWARE_ID);
            item.put("fileBucket", OtaOptions.INJECT_FILE_BUCKET);

            JSONArray fake = new JSONArray();
            fake.put(item);

            data.put("status", OtaOptions.INJECT_STATUS);
            data.put("title", OtaOptions.INJECT_TITLE);
            data.put("content", OtaOptions.INJECT_CONTENT);
            data.put("preDownloadAppSet", true);
            data.put("fileInfoList", fake);
            if (data.has("multiDownloadUpgradeContent")) {
                data.put("multiDownloadUpgradeContent", JSONObject.NULL);
            }

            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            Object newBody = createBody(builderName, originalBody, bytes);
            if (newBody == null) {
                logOnce("createBody failed");
                return;
            }
            REBUILDING.set(Boolean.TRUE);
            Object patched;
            try {
                Object builder = XposedHelpers.callMethod(response, "newBuilder");
                XposedHelpers.callMethod(builder, "body", newBody);
                XposedHelpers.callMethod(builder, "removeHeader", "content-encoding");
                XposedHelpers.callMethod(builder, "header", "content-length",
                        String.valueOf(bytes.length));
                patched = XposedHelpers.callMethod(builder, "build");
            } finally {
                REBUILDING.set(Boolean.FALSE);
            }
            param.setResult(patched);
            logOnce("INJECTED " + OtaOptions.INJECT_FILE_NAME
                    + " (" + OtaOptions.INJECT_FILE_SIZE + " B) url=" + OtaOptions.INJECT_FILE_URL);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            logOnce("inject failed (" + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage() + ")");
        }
    }

    // ==================== 工具 ====================

    private static String readUrl(Object response) {
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            if (request == null) return null;
            Object url = XposedHelpers.callMethod(request, "url");
            return url == null ? null : url.toString();
        } catch (Throwable t) {
            return null;
        }
    }

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
            HookLog.log(TAG + " inject createBody failed: " + t);
            return null;
        }
    }

    private static void logOnce(String message) {
        if (LOGGED.add(message)) {
            HookLog.log(TAG + " " + message);
        }
    }
}
