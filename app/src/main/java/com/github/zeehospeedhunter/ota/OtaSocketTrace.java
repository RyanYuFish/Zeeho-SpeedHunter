package com.github.zeehospeedhunter.ota;

import com.github.zeehospeedhunter.core.HookKit;
import com.github.zeehospeedhunter.core.HookLog;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 抓官方 EasyConnect 与车机 {@code socket_l}（{@code 192.168.0.1:10950}）的往来帧（**纯只读**）。
 *
 * <p><b>为什么要这个</b>：2026-10-05 17:45 车边实测，投屏启动后
 * {@code 10950/tcp} <b>变为 OPEN</b>，但裸 connect 收到的第一包是
 * 车机下发的中文文本：</p>
 * <pre>
 *   服务器忙乱，已有客户端连接,仅支持一个客户端      （原文如此，64 字节 UTF-8）
 * </pre>
 * ⇒ <b>该端口是独占的单连接槽</b>：官方 EasyConnect 已占用，我们再连会被拒。
 * ⇒ 所以「从外部另开一条连接推 OTA」这条路<b>走不通</b>；
 *   可行路线是<b>复用官方已建立的那条会话</b>，而那必须发生在 App 进程内。
 *
 * <p><b>抓什么</b>：{@code java.net.Socket} / {@code java.io.DataOutputStream} /
 * {@code DataInputStream} 上所有以 {@code 192.168.0.1} 或 {@code :10950} 为对端的读写。
 * 只打印长度 + 前若干字节十六进制，<b>不修改、不重放、不伪造</b>。</p>
 *
 * <p><b>红线</b>：纯观察。看到 OTA 相关命令码（{@code 0x10000001} 等）也只记录不动手。</p>
 */
public final class OtaSocketTrace {

    private static final String TAG = HookLog.OTA;

    /** 目标对端（车机）。 */
    private static final String CAR_IP = OtaChannels.SOCKET_L_CLIENT_TARGET;

    /** 每条最多记录的字节数。 */
    private static final int MAX_SHOW = 64;

    private OtaSocketTrace() {
    }

    public static void install() {
        hookSocketOutput();
        hookSocketInput();
        HookLog.log(TAG + " trace | watching Socket/DataOutputStream/DataInputStream"
                + " for peer " + CAR_IP + ":" + OtaChannels.SOCKET_L_PORT
                + " (read-only)");
    }

    private static boolean isTarget(Object peer) {
        if (peer == null) {
            return false;
        }
        try {
            java.net.Socket s = (peer instanceof java.net.Socket)
                    ? (java.net.Socket) peer : null;
            if (s == null) {
                return false;
            }
            java.net.InetAddress a = s.getInetAddress();
            return a != null && CAR_IP.equals(a.getHostAddress());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 记录一次读/写的方向与内容。 */
    private static void note(String dir, byte[] data, int off, int len) {
        if (data == null || len <= 0) {
            HookLog.log(TAG + " trace " + dir + " len=0");
            return;
        }
        int n = Math.min(len, MAX_SHOW);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            int v = data[off + i] & 0xFF;
            if (i > 0) {
                sb.append(' ');
            }
            if (v >= 0x20 && v < 0x7F) {
                sb.append((char) v);
            } else {
                sb.append(String.format("%02X", v));
            }
        }
        HookLog.log(TAG + " ★ trace " + dir + " len=" + len + " [" + sb + "]");
        // 若首字节像是 P2C 帧头，顺手解出 cmdType（0x10000001 等 OTA 命令）
        if (len >= 8) {
            int b0 = data[off] & 0xFF;
            int b1 = data[off + 1] & 0xFF;
            if (b0 == 0x00 && b1 == 0x00) {
                int cmd = ((data[off + 4] & 0xFF) << 24)
                        | ((data[off + 5] & 0xFF) << 16)
                        | ((data[off + 6] & 0xFF) << 8)
                        | (data[off + 7] & 0xFF);
                HookLog.log(TAG + " ★ trace " + dir
                        + " looks like PXC frame: cmdType=0x" + Integer.toHexString(cmd));
            }
        }
    }

    private static void hookSocketOutput() {
        try {
            for (final Method m : java.net.Socket.class.getDeclaredMethods()) {
                String n = m.getName();
                if (!n.equals("getOutputStream")) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(Method hm, Object[] args, Object ret) {
                        try {
                            if (isTarget(args[0]) && ret != null) {
                                wrap(ret, "OUT");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            HookLog.log(TAG + " trace | hooked Socket#getOutputStream");
        } catch (Throwable t) {
            HookLog.log(TAG + " trace | getOutputStream hook failed ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    private static void hookSocketInput() {
        try {
            for (final Method m : java.net.Socket.class.getDeclaredMethods()) {
                String n = m.getName();
                if (!n.equals("getInputStream")) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(Method hm, Object[] args, Object ret) {
                        try {
                            if (isTarget(args[0]) && ret != null) {
                                wrap(ret, "IN");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            HookLog.log(TAG + " trace | hooked Socket#getInputStream");
        } catch (Throwable t) {
            HookLog.log(TAG + " trace | getInputStream hook failed ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * 给流对象挂一层「记录读写」的 hook。
     *
     * <p>实现说明：直接 hook {@code DataOutputStream#write(byte[])} 不可靠 ——
     * 该类实际覆盖的是 {@code write(byte[],int,int)}，{@code getMethod} 取到的父类版本
     * 未必是运行期调用点。这里改为 hook 更底层的
     * {@code OutputStream#write(byte[])} / {@code InputStream#read(byte[])}，
     * 按对端 IP 过滤后记录。</p>
     */
    private static void wrap(final Object stream, final String dir) {
        try {
            if (stream instanceof java.io.OutputStream) {
                hookWrite((java.io.OutputStream) stream, dir);
            } else if (stream instanceof java.io.InputStream) {
                hookRead((java.io.InputStream) stream, dir);
            }
        } catch (Throwable t) {
            HookLog.log(TAG + " trace | wrap " + dir + " failed ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    private static void hookWrite(final java.io.OutputStream os, final String dir) {
        try {
            XposedBridge.hookMethod(
                    os.getClass().getMethod("write", byte[].class),
                    new XC_MethodHook() {
                        protected void beforeHookedMethod(Method hm, Object[] args) {
                            if (args != null && args.length == 1 && args[0] instanceof byte[]) {
                                byte[] b = (byte[]) args[0];
                                note(dir, b, 0, b.length);
                            }
                        }
                    });
        } catch (Throwable t) {
            HookLog.log(TAG + " trace | hookWrite " + dir + " unsupported ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    private static void hookRead(final java.io.InputStream is, final String dir) {
        try {
            XposedBridge.hookMethod(
                    is.getClass().getMethod("read", byte[].class),
                    new XC_MethodHook() {
                        protected void afterHookedMethod(Method hm, Object[] args, Object ret) {
                            if (ret instanceof Integer && args != null
                                    && args.length == 1 && args[0] instanceof byte[]) {
                                int n = (Integer) ret;
                                if (n > 0) {
                                    note(dir, (byte[]) args[0], 0, n);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            HookLog.log(TAG + " trace | hookRead " + dir + " unsupported ("
                    + t.getClass().getSimpleName() + ")");
        }
    }
}
