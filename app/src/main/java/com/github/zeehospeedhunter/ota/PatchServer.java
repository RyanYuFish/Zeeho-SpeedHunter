package com.github.zeehospeedhunter.ota;

import android.os.Process;

import com.github.zeehospeedhunter.core.HookLog;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/**
 * ★ 手机侧微型 HTTP 服务：把 patched zip 喂给车机。
 *
 * <p>为什么需要它（{@code docs/32}）：车机的 OTA 是<b>车机自己拉包</b> ——
 * {@code libapis.so} 里 {@code OTAControl::downloadUpdateFile()} 用
 * {@code aiot_download} 发 HTTP 请求（带 {@code Range: bytes=}）去下载，
 * 落 {@code /media/flash/userdata/update.zip}，然后 unzip → 校验 → ioctl 刷写。
 * 而车机上网走的是 <b>wlan0 STA</b>（它连的是手机热点），
 * 所以下载源必须<b>在手机热点这一侧可达</b> —— 也就是手机上得起一个 HTTP 服务。</p>
 *
 * <p>用法：手机开热点 → 车机连上 → 往
 * {@code /ota/device/upgrade/{pk}/{dn}} 发一条 OTA 任务，{@code url} 填
 * {@code http://<手机热点网关IP>:18080/1787815351970919959.zip} → 车机来拉。</p>
 *
 * <p>只做一件事：把 {@link OtaOptions#SERVE_FILE} 这一个文件以只读方式发出去，
 * 支持 Range。任何请求都会记日志 —— <b>车机来拉的时候日志里能看到</b>，
 * 这就是「投递成功」的直接证据。</p>
 */
public final class PatchServer {

    private static final String TAG = HookLog.OTA + " PatchServer";

    private static volatile boolean started = false;

    private PatchServer() {
    }

    /** 在后台线程启动；重复调用只会启动一次。 */
    public static synchronized void start() {
        if (started || !OtaOptions.SERVE_PATCH) {
            return;
        }
        started = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.currentThread().setName("wb-patch-server");
                    serve();
                } catch (Throwable t) {
                    String m = String.valueOf(t.getMessage());
                    // App 有多个进程，模块会被加载多次 —— 第二个进程绑不上属正常
                    if (m.contains("EADDRINUSE") || m.contains("Address already in use")) {
                        HookLog.log(TAG + " 端口 " + OtaOptions.SERVE_PORT
                                + " 已被占用（另一进程已在服务，正常）");
                    } else {
                        HookLog.log(TAG + " 启动失败: " + t);
                    }
                    started = false;
                }
            }
        }, "wb-patch-server").start();
    }

    private static void serve() throws IOException {
        File f = pickFile();
        if (f == null) {
            HookLog.log(TAG + " 找不到可读的 zip，已停止。候选: "
                    + java.util.Arrays.toString(OtaOptions.SERVE_FILES));
            started = false;
            return;
        }
        final long size = f.length();
        ServerSocket ss = new ServerSocket(OtaOptions.SERVE_PORT);
        ss.setReuseAddress(true);
        HookLog.log(TAG + " ★ 已启动 port=" + OtaOptions.SERVE_PORT
                + " file=" + f.getName() + " size=" + size
                + " pid=" + Process.myPid());
        HookLog.log(TAG + " 本机 IPv4: " + localIps());

        while (true) {
            Socket s = null;
            try {
                s = ss.accept();
                handle(s, f, size);
            } catch (Throwable t) {
                HookLog.log(TAG + " accept/serve 异常: " + t);
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static void handle(Socket s, File f, long size) throws IOException {
        s.setSoTimeout(30000);
        String req = readRequestHead(s);
        if (req == null) {
            return;
        }
        String peer = String.valueOf(s.getInetAddress().getHostAddress());
        String peerName = peerName(peer);
        HookLog.log(TAG + " ★★★ 收到请求 from=" + peer + peerName
                + "  " + firstLine(req));

        long start = 0;
        long end = size - 1;
        String range = header(req, "Range");
        boolean partial = false;
        if (range != null && range.toLowerCase(Locale.US).startsWith("bytes=")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash >= 0) {
                    String a = spec.substring(0, dash).trim();
                    String b = dash + 1 < spec.length() ? spec.substring(dash + 1).trim() : "";
                    if (!a.isEmpty()) {
                        start = Long.parseLong(a);
                    }
                    if (!b.isEmpty()) {
                        end = Long.parseLong(b);
                    }
                    partial = true;
                }
            } catch (NumberFormatException ignored) {
                partial = false;
            }
        }
        if (start < 0 || start >= size) {
            start = 0;
            partial = false;
        }
        if (end < start || end >= size) {
            end = size - 1;
        }
        long len = end - start + 1;

        OutputStream os = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
        writeAscii(os, partial
                ? "HTTP/1.1 206 Partial Content\r\n"
                : "HTTP/1.1 200 OK\r\n");
        writeAscii(os, "Content-Type: application/zip\r\n");
        writeAscii(os, "Content-Length: " + (partial ? len : size) + "\r\n");
        writeAscii(os, "Accept-Ranges: bytes\r\n");
        if (partial) {
            writeAscii(os, "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n");
        }
        writeAscii(os, "Connection: close\r\n\r\n");

        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            long skipped = 0;
            while (skipped < start) {
                long n = in.skip(start - skipped);
                if (n <= 0) {
                    break;
                }
                skipped += n;
            }
            byte[] buf = new byte[64 * 1024];
            long remain = len;
            while (remain > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remain));
                if (n < 0) {
                    break;
                }
                os.write(buf, 0, n);
                remain -= n;
            }
            os.flush();
            HookLog.log(TAG + " 已发送 " + len + " B (" + start + ".." + end + ") → " + peer);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 按 {@link OtaOptions#SERVE_FILES} 顺序挑第一个「存在且可读」的文件。 */
    private static File pickFile() {
        for (String p : OtaOptions.SERVE_FILES) {
            File f = new File(p);
            if (f.isFile() && f.canRead()) {
                return f;
            }
        }
        return null;
    }

    private static String readRequestHead(Socket s) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] one = new byte[1];
        int seen = 0;
        while (true) {
            int n = s.getInputStream().read(one);
            if (n < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            sb.append((char) (one[0] & 0xff));
            if (one[0] == '\n') {
                seen++;
                if (seen >= 2 || sb.length() > 8192) {
                    return sb.toString();
                }
            } else if (one[0] != '\r') {
                seen = 0;
            }
            if (sb.length() > 65536) {
                return sb.toString();
            }
        }
    }

    private static String firstLine(String head) {
        int i = head.indexOf('\n');
        return (i > 0 ? head.substring(0, i) : head).trim();
    }

    private static String header(String head, String name) {
        String lower = head.toLowerCase(Locale.US);
        String key = name.toLowerCase(Locale.US) + ":";
        int i = lower.indexOf("\r\n" + key);
        if (i < 0) {
            i = lower.startsWith(key) ? -key.length() : -1;
        }
        if (i < 0) {
            return null;
        }
        int from = i + 2 + key.length();
        int to = head.indexOf('\r', from);
        if (to < 0) {
            to = head.indexOf('\n', from);
        }
        return to < 0 ? head.substring(from).trim() : head.substring(from, to).trim();
    }

    private static void writeAscii(OutputStream os, String s) throws IOException {
        os.write(s.getBytes("US-ASCII"));
    }

    private static String peerName(String ip) {
        if (ip == null) {
            return "";
        }
        // 车机连手机热点后拿到的是热点网段；标出来便于一眼认出
        if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.4")
                || ip.startsWith("192.168.137.")) {
            return "  ← ★ 疑似车机（热点网段）";
        }
        return "";
    }

    /** 打印本机所有 IPv4，方便知道该把 url 填成什么。 */
    private static String localIps() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> es = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(es)) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (sb.length() > 0) {
                            sb.append(", ");
                        }
                        sb.append(ni.getName()).append('=').append(a.getHostAddress());
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("(err ").append(t).append(')');
        }
        return sb.toString();
    }
}
