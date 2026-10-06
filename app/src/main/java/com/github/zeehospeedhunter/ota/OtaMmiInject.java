package com.github.zeehospeedhunter.ota;

import org.json.JSONObject;

import java.io.File;
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
 * 注入 {@code ota/mmi/info/v2} —— 决定 App 进不进「把包推给车机」那一步的接口。
 *
 * <p><b>为什么还需要它</b>：只注入 red-point 时，App 会显示「发现新的OTA升级」，
 * 但紧接着请求 {@code ota/mmi/info/v2}，服务端返回
 * {@code upgradeStatus:-1, fileInfo:全 null}，App 于是停住 —— 既不下载也不推送。
 * 所以 red-point 只是「入口红点」，mmi/info 才是真正的任务单。</p>
 *
 * <p><b>为什么注入内容放外置 JSON</b>：我们不知道 {@code upgradeStatus} 的有效枚举，
 * 也不确定 {@code fileInfo} 由谁消费（App 自己下载？还是车机来拉？）。
 * 每试一组值就重新编译 + 安装 + 冷启太慢，所以把覆盖字段写到文件里，
 * 改文件 → 强杀 App → 重进即可，一轮 10 秒。</p>
 *
 * <pre>
 * adb shell 'cat &gt; /sdcard/Android/data/com.cfmoto/files/zeeho_mmi_override.json &lt;&lt;EOF
 * {...}
 * EOF'
 * </pre>
 */
public final class OtaMmiInject {

    private static final String TAG = HookLog.OTA;

    /**
     * 目标接口 → 外置覆盖文件名。
     *
     * <p>{@code ota/mmi/info/v2} 是升级任务单；{@code upstatus} 是「车机连没连上」的查询
     * （实测返回 {@code {"status":1,...}}，App 据此弹「车辆连接失败」）。
     * 两个都做成外置注入，才能靠改文件快速试出 App 的分支条件。</p>
     */
    private static final String[][] TARGETS = {
            {"ota/mmi/info/v2",
                    "/sdcard/Android/data/com.cfmoto/files/zeeho_mmi_override.json",
                    "/data/local/tmp/zeeho_mmi_override.json"},
            {"upstatus",
                    "/sdcard/Android/data/com.cfmoto/files/zeeho_upstatus_override.json",
                    "/data/local/tmp/zeeho_upstatus_override.json"},
    };

    private static final Set<String> LOGGED = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    /** 防止 {@code newBuilder().build()} 递归进自己的 hook。 */
    private static final ThreadLocal<Boolean> REBUILDING = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private OtaMmiInject() {
    }

    public static void installAll() {
        if (!OtaOptions.INJECT_MMI) {
            HookLog.log(TAG + " mmi inject disabled by options");
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
        HookLog.log(TAG + " json inject installed (targets=" + TARGETS.length + ")");
    }

    // ==================== 核心 ====================

    private static void tryPatch(String builderName, XC_MethodHook.MethodHookParam param) {
        Object response = param.getResult();
        if (response == null) return;
        String mark = "?";          // 目标名，catch 里也要用
        try {
            String url = readUrl(response);
            if (url == null) return;

            String[] target = null;
            for (String[] t : TARGETS) {
                if (url.contains(t[0])) {
                    target = t;
                    break;
                }
            }
            if (target == null) return;
            mark = target[0];

            Object originalBody = XposedHelpers.callMethod(response, "body");
            if (originalBody == null) return;

            String override = readOverride(target);
            if (override == null) {
                logOnce(mark + " override file missing, keep original");
                return;
            }

            Object peeked;
            try {
                peeked = XposedHelpers.callMethod(response, "peekBody", 1 << 20);
            } catch (Throwable alreadyConverted) {
                logOnce(mark + " already patched (body converted), skipped");
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
                logOnce(mark + " unexpected shape, skipped");
                return;
            }

            // 原始值打一次，用来确认字段名与默认含义
            logOnce(mark + " ORIGINAL data = " + data);

            JSONObject patch = new JSONObject(override);
            java.util.Iterator<String> keys = patch.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                data.put(key, patch.get(key));
            }

            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            Object newBody = createBody(builderName, originalBody, bytes);
            if (newBody == null) {
                logOnce(mark + " createBody failed");
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
            logOnce(mark + " PATCHED data = " + data);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            logOnce(mark + " inject failed (" + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage() + ")");
        }
    }

    // ==================== 工具 ====================

    /** 读外置覆盖 JSON；内容为空视为「不注入」。 */
    private static String readOverride(String[] target) {
        for (int i = 1; i < target.length; i++) {
            String path = target[i];
            try {
                File f = new File(path);
                if (!f.isFile() || !f.canRead()) continue;
                byte[] buf = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                try {
                    int n = in.read(buf);
                    if (n <= 0) continue;
                    String s = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                    return s.isEmpty() ? null : s;
                } finally {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

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
            HookLog.log(TAG + " createBody failed: " + t);
            return null;
        }
    }

    private static void logOnce(String message) {
        if (LOGGED.add(message)) {
            HookLog.log(TAG + " " + message);
        }
    }
}
