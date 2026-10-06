package com.github.zeehospeedhunter.ota;

import com.github.zeehospeedhunter.core.HookKit;
import com.github.zeehospeedhunter.core.HookLog;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 抓取 EasyConnect SDK 的 FTP / P2C 配置参数（**纯只读**）。
 *
 * <p><b>来源</b>：2026-10-05 对 {@code firmware/spsdk-root/lib/libECSDK.so}（3.4 MB）的静态逆向。
 * 关键发现（均已由固件字符串与调用图互证）：</p>
 * <ul>
 *   <li>{@code OTAManager} 是完整 OTA 管理层：{@code startOTAUpdate} / {@code checkOTAUpdate} /
 *       {@code loadOTAFileLists} / {@code compressList} / {@code createBinResults} /
 *       {@code uploadFile} / {@code openFtpService} / {@code openFtpCtrlConnection} /
 *       {@code openFtpDataConnection} / {@code loginFtp} / {@code getOTAPort} /
 *       {@code recvFtpCommandReply} / {@code notifyFtpDownloadProgress}。</li>
 *   <li>FTP 凭据来自配置键 {@code carbit_ota_user} / {@code carbit_ota_pwd}，
 *       由 {@code ECContext::configSEParam(key, value)}（@0x10c084，内部是 jsoncpp）注入，
 *       键名硬编码在 {@code .rodata}（0x2a3cf0 / 0x2a3d00），<b>值由 App 从云端取</b>。</li>
 *   <li>FTP 会话序列（{@code .rodata} 0x2a4864 起，另有 curl 内置默认 0x3231aa）：
 *       {@code USER } → {@code PASS } → {@code TYPE I} → {@code SIZE } → {@code PASV}
 *       → {@code REST %lu} → {@code RETR }。</li>
 *   <li>包体其实走 P2C 长连接：{@code C2PService::sendOTAFile(name, data, len)} →
 *       {@code PXCService::sendCMD} → {@code PXCService::recvCMD}（@0xf440c）。</li>
 * </ul>
 *
 * <p><b>为什么需要这个 hook</b>：静态只能拿到<b>键名</b>，拿不到<b>值</b>。
 * 值由 App 在运行时注入，是打通链路的最后一块拼图。</p>
 *
 * <p><b>红线</b>：只读打印参数，<b>不改、不重放、不伪造</b>任何值。</p>
 */
public final class EcSdkConfigProbe {

    private static final String TAG = HookLog.OTA;

    /** SDK 主类（可能带包名前缀，故按候选逐个试）。 */
    private static final String[] SDK_IMPL_CLASSES = {
            "com.carbitec.easyconnect.ECSDKImp",
            "com.carbitec.ecsdk.ECSDKImp",
            "com.cfmoto.easyconnect.ECSDKImp",
            "ECSDKImp",
    };

    private EcSdkConfigProbe() {
    }

    /** 安装探针。需在 {@code HookKit.setClassLoader} 之后调用。 */
    public static void install() {
        hookConfigSEParam();
        hookFtpLifecycle();
        HookLog.log(TAG + " ecprobe | known FTP cmds: USER/PASS/TYPE I/SIZE/PASV/REST/RETR");
        HookLog.log(TAG + " ecprobe | default creds in libECSDK.so: " + DEFAULT_FTP_USER
                + ":" + DEFAULT_FTP_PWD + "  (curl built-in, 0x3231aa)");
    }

    // ============================================================
    // ① configSEParam(key, value) —— 抓 carbit_ota_user / carbit_ota_pwd 的真实值
    // ============================================================

    private static void hookConfigSEParam() {
        for (String cls : SDK_IMPL_CLASSES) {
            final Class<?> c = HookKit.findClassIfExists(cls);
            if (c == null) {
                continue;
            }
            try {
                for (final Method m : c.getDeclaredMethods()) {
                    if (!"configSEParam".equals(m.getName()) || m.getParameterTypes().length != 2) {
                        continue;
                    }
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void beforeHookedMethod(Method hookMethod, Object[] args) {
                            String k = str(args[0]);
                            String v = str(args[1]);
                            if (isInterestingKey(k)) {
                                HookLog.log(TAG + " ★ configSEParam " + k + " = " + v);
                            }
                        }
                    });
                    HookLog.log(TAG + " ecprobe hooked " + cls + "#configSEParam");
                }
            } catch (Throwable t) {
                HookLog.log(TAG + " ecprobe configSEParam hook failed on " + cls
                        + " (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    private static boolean isInterestingKey(String k) {
        if (k == null) {
            return false;
        }
        return k.contains("ota") || k.contains("OTA") || k.contains("ftp") || k.contains("Ftp")
                || k.contains("carbit") || k.contains("uuid") || k.contains("pwd")
                || k.contains("user") || k.contains("token");
    }

    // ============================================================
    // ② FTP 会话里程碑 —— 确认端口与凭据是否被接受
    // ============================================================

    private static void hookFtpLifecycle() {
        // 这些方法在 libECSDK.so（so 内部类），App 进程里的类名可能是
        //   com.carbitec.easyconnect.OTAManager 之类；按方法名兜底尝试若干候选。
        String[] owners = {
                "com.carbitec.easyconnect.OTAManager",
                "com.carbitec.ecsdk.OTAManager",
                "com.cfmoto.easyconnect.OTAManager",
                "OTAManager",
        };
        String[] methods = {
                "openFtpService", "openFtpCtrlConnection", "openFtpDataConnection",
                "loginFtp", "uploadFile", "getOTAPort", "notifyFtpServiceResult",
                "notifyFtpDownloadProgress", "checkOTAUpdate", "startOTAUpdate",
                "loadOTAFileLists", "compressList", "createBinResults",
                "checkSoftwareIntegrity", "encryptPwdByPubkey",
        };
        for (String owner : owners) {
            Class<?> c = HookKit.findClassIfExists(owner);
            if (c == null) {
                continue;
            }
            for (final String name : methods) {
                try {
                    for (final Method m : c.getDeclaredMethods()) {
                        if (!name.equals(m.getName())) {
                            continue;
                        }
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            protected void beforeHookedMethod(Method hm, Object[] args) {
                                HookLog.log(TAG + " ★ OTAManager#" + name + argsToString(args));
                            }

                            protected void afterHookedMethod(Method hm, Object[] args, Object ret) {
                                if ("getOTAPort".equals(name) && args != null && args.length >= 2) {
                                    HookLog.log(TAG + " ★ getOTAPort → ftp="
                                            + args[0] + " passive=" + args[1]);
                                }
                                if ("loginFtp".equals(name)) {
                                    HookLog.log(TAG + " ★ loginFtp ret=" + ret);
                                }
                            }
                        });
                        HookLog.log(TAG + " ecprobe hooked " + owner + "#" + name);
                    }
                } catch (Throwable ignored) {
                    // 单个方法失败不影响其他
                }
            }
        }
    }

    // ============================================================

    /** 固件里 curl 内置的默认匿名凭据（{@code libECSDK.so @0x3231aa}: {@code anonymous:busybox@}）。 */
    public static final String DEFAULT_FTP_USER = "anonymous";
    public static final String DEFAULT_FTP_PWD = "busybox@";

    private static String str(Object o) {
        return o == null ? "null" : String.valueOf(o);
    }

    private static String argsToString(Object[] args) {
        if (args == null || args.length == 0) {
            return "()";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object a = args[i];
            if (a instanceof String) {
                String s = (String) a;
                // 日志里可能很长（路径/URL），截断避免刷屏
                sb.append('"').append(s.length() > 120 ? s.substring(0, 120) + "…" : s).append('"');
            } else {
                sb.append(a);
            }
        }
        return sb.append(')').toString();
    }
}
