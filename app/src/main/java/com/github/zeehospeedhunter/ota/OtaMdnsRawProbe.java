package com.github.zeehospeedhunter.ota;

import com.github.zeehospeedhunter.core.HookLog;

import android.content.Context;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 直接用 UDP 组播发标准 mDNS 查询，发现车机 {@code _EasyConn._tcp.local.}（**纯只读**）。
 *
 * <p><b>为什么不用 {@code NsdManager}</b>：2026-10-05 实测在目标进程里
 * {@code discoverServices} 抛 {@code SecurityException}（Android 16 / API 36 对 mDNS
 * 加了额外限制，{@code NEARBY_WIFI_DEVICES} 不足以放行）。而 mDNS 协议本身很简单，
 * 自己发查询更稳，也更容易把请求/响应都打进日志。</p>
 *
 * <p>查询报文（RFC 6762 §18）：</p>
 * <pre>
 *   Header : 12B  (id=0, flags=0, QDCOUNT=1, AN/NS/AR=0)
 *   Question: "4 EasyConn" "_tcp" "local" 0x0001 IN
 * </pre>
 * 只读收包并解析 PTR/SRV/TXT 里的端口与 TXT 键值，<b>不连接、不发任何 OTA 数据</b>。
 */
public final class OtaMdnsRawProbe {

    private static final String TAG = HookLog.OTA;

    /** 服务类型。 */
    public static final String SERVICE_TYPE = "_EasyConn._tcp.local.";

    /** 实例名。 */
    public static final String SERVICE_NAME = "EasyConn";

    private static final String MDNS_ADDR = "224.0.0.251";
    private static final int MDNS_PORT = 5353;

    private OtaMdnsRawProbe() {
    }

    /** 启动一次查询（后台线程，只发标准 mDNS 查询）。 */
    public static void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    query();
                } catch (Throwable e) {
                    HookLog.log(TAG + " mdns-raw | error ("
                            + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
                }
            }
        }, "zeeho-mdns-raw");
        t.setDaemon(true);
        t.start();
    }

    private static void query() {
        byte[] q = buildQuery();
        HookLog.log(TAG + " mdns-raw | query " + q.length + "B for "
                + SERVICE_NAME + "." + SERVICE_TYPE);

        // 收 2 轮，覆盖组播延迟
        for (int round = 1; round <= 2; round++) {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                sock.setReuseAddress(true);
                sock.setBroadcast(true);
                sock.setSoTimeout(2500);

                InetAddress addr = InetAddress.getByName(MDNS_ADDR);
                sock.send(new DatagramPacket(q, q.length, addr, MDNS_PORT));
                HookLog.log(TAG + " mdns-raw | round " + round + " sent to "
                        + MDNS_ADDR + ":" + MDNS_PORT);

                long deadline = System.currentTimeMillis() + 2500;
                byte[] buf = new byte[4096];
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(in);
                    } catch (java.net.SocketTimeoutException ignored) {
                        break;
                    }
                    if (in.getLength() > 12) {
                        handleResponse(in);
                    }
                }
            } catch (Throwable e) {
                HookLog.log(TAG + " mdns-raw | round " + round + " failed ("
                        + e.getClass().getSimpleName() + ")");
            } finally {
                if (sock != null) {
                    try {
                        sock.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        HookLog.log(TAG + " mdns-raw | done");
    }

    /** 构造标准 mDNS 查询：问 A 记录 + SRV + TXT。 */
    private static byte[] buildQuery() {
        String[] labels = {SERVICE_NAME, "_tcp", "local"};
        int qLen = 12;
        for (String l : labels) {
            qLen += 1 + l.length();
        }
        qLen += 1                            // name 结束符 0x00
             + 2 + 2                         // TYPE=A(1)  CLASS=IN(1)
             + 11;                           // OPT 伪记录 = name(1)+type(2)+class(2)+ttl(4)+rdlen(2)
        // ⚠️ 2026-10-05 修正：初版把 OPT 记成 4 字节，实际 11 字节，
        // 少算 7 字节导致 ArrayIndexOutOfBounds: length=43 index=43。

        byte[] buf = new byte[qLen];
        int p = 0;
        // Header: id=0, flags=0, QDCOUNT=1, 其余 0
        p += 2;                                   // id
        p += 2;                                   // flags
        buf[p++] = 0;
        buf[p++] = 1;                             // QDCOUNT = 1
        // ANCOUNT / NSCOUNT / ARCOUNT = 0
        p += 6;
        for (String l : labels) {
            buf[p++] = (byte) l.length();
            for (int i = 0; i < l.length(); i++) {
                buf[p++] = (byte) l.charAt(i);
            }
        }
        buf[p++] = 0;
        buf[p++] = 0;                             // TYPE  = A (1)
        buf[p++] = 0;
        buf[p++] = 1;                             // CLASS = IN (1)

        // OPT: root name(1) + type(2) + class(2) + ttl(4) + rdlen(2) = 11 bytes
        buf[p++] = 0;
        buf[p++] = 0;
        buf[p++] = 41;
        buf[p++] = 0x10;
        buf[p++] = 0x00;
        buf[p++] = 0;
        buf[p++] = 0;
        buf[p++] = 0;
        buf[p++] = 0;
        buf[p++] = 0;
        buf[p++] = 0;
        return buf;
    }

    /** 解析响应：把 SRV 里的端口和 TXT 键值打出来。 */
    private static void handleResponse(DatagramPacket pkt) {
        byte[] d = pkt.getData();
        int len = pkt.getLength();
        String from = pkt.getAddress() == null ? "?" : pkt.getAddress().getHostAddress();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < 512; i++) {
            int c = d[i] & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
        }
        HookLog.log(TAG + " ★ mdns-raw | resp from " + from + " len=" + len
                + " : " + sb);

        // 粗解析：找 SRV(33) 记录里的端口（大端 2B），以及可打印 TXT
        for (int i = 0; i + 10 < len; i++) {
            if ((d[i] & 0xFF) == 0 && (d[i + 1] & 0xFF) == 33) {
                int rdlen = ((d[i + 8] & 0xFF) << 8) | (d[i + 9] & 0xFF);
                int rdStart = i + 10;
                // SRV: priority(2) weight(2) port(2) target
                if (rdStart + 6 < len && rdlen >= 6) {
                    int port = ((d[rdStart + 4] & 0xFF) << 8) | (d[rdStart + 5] & 0xFF);
                    if (port > 0 && port < 65536) {
                        HookLog.log(TAG + " ★★ SRV port = " + port
                                + "  ← P2C 监听端口（" + from + "）");
                    }
                }
            }
        }
        // 打印可读串（覆盖 TXT 里的 flavor= / port= / huid= 等）
        StringBuilder txt = new StringBuilder();
        int run = 0;
        for (int i = 0; i < len; i++) {
            int c = d[i] & 0xFF;
            if (c >= 0x20 && c < 0x7F) {
                txt.append((char) c);
                run++;
            } else {
                if (run >= 4) {
                    HookLog.log(TAG + "   str: " + txt);
                }
                txt.setLength(0);
                run = 0;
            }
        }
    }

    /** 列出本机所有 IPv4 接口，便于确认确实在车机 AP 上。 */
    public static void logInterfaces() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof InetAddress && a.getAddress().length == 4) {
                        HookLog.log(TAG + " mdns-raw | iface " + ni.getName()
                                + " → " + a.getHostAddress());
                    }
                }
            }
        } catch (Throwable t) {
            HookLog.log(TAG + " mdns-raw | iface list failed");
        }
    }
}
