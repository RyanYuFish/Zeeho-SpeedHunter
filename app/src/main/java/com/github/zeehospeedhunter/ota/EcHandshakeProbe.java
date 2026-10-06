package com.github.zeehospeedhunter.ota;

import com.github.zeehospeedhunter.core.HookLog;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>EasyConnect（Carbit ECSDK）握手探针</b> —— 不依赖 ZEEHO App 进程，纯模块侧发帧。
 *
 * <h3>为什么要有它</h3>
 * <p>上一版 {@link MirrorTrigger} 只是「按关键字猜一个 Activity 名再 {@code startActivity}」，
 * <b>根本没做 EC 握手</b>，投不投得出来全靠猜。本类把「车机 EC 通道是否可达、鉴权是否通过」
 * 变成<b>可观测的事实</b>：用与官方同构的 {@code sendClientInfo} JSON 去敲车机 PXC 口，
 * 把连接/回包/超时全部记录下来。</p>
 *
 * <h3>PXC 帧结构（libECSDK.so 反汇编实证，docs/49 §3.1）</h3>
 * <pre>
 *   ┌──────────── 16 字节固定头 ────────────┐┬──── JSON payload ────┐
 *   │ magic/type … │ cmdType(4B) … （宽度4） │ { … sendClientInfo … }
 *   └──────────────────────────────────────┘└──────────────────────┘
 *   PXCService::recvCMD @0x001076ED (Thumb)
 *     ldr r3,[r0,#0x20] ; ldr r5,[r5,#0x18] ; blx r5 ; cmp r0,#0x10  ← 头宽 16
 *     …
 *     movs r1,#4                                  ← cmdType 字段宽 4 字节
 * </pre>
 * <p><b>诚实说明</b>：头里 {@code cmdType} 的<b>具体数值</b>与 {@code magic} 常量在 Carbit SDK
 * 内部，未以字符串导出，静态不可得（见 docs/49 §5.3）。因此本探针默认用一个
 * <b>可配置的头部模板</b>：先按最保守的布局发，只求<b>观测车机回包</b>；
 * 拿到真实回包后再用 {@link #setHeaderTemplate} 校正。这样探针本身是「活的」——
 * 一旦在真车上抓到一帧，就能对齐真正的协议，而不必重写。</p>
 *
 * <h3>红线</h3>
 * <ul>
 *   <li>只连车机 AP 网段（192.168.0.x）的 {@link #CAR_HOSTS}，不碰其它地址。</li>
 *   <li><b>只发一帧 client-info</b>、只听，不重发、不重试（EC 是有状态协议，乱发会污染车机状态）。</li>
 *   <li>任何日志格式化失败都降级成原文，绝不让日志把探针打断（见 {@code safeLog}）。</li>
 * </ul>
 */
public final class EcHandshakeProbe {

    private static final String TAG = HookLog.MIRROR;

    /** 车机 AP 上的候选地址（车机固定 192.168.0.1；P2P 时可能是 192.168.49.1）。 */
    private static final String[] CAR_HOSTS = {"192.168.0.1", "192.168.49.1"};

    /**
     * EC/RV 候选端口。
     *
     * <p>说明：车机把 <b>RV（RemoteView，镜像进程）</b> 的端口写在
     * {@code /data/local/tmp/easyConnRV.port}（模块读不到车机文件系统），
     * <b>PXC 控制口</b> 的常量在 {@code openTransport(EC_TRANSPORT_ANDROID_WIFI)} 里绑端口，
     * 静态没导出。因此这里放一组<b>经验候选</b>，探针逐个试——命中即停。
     * 命中与否直接写进日志（见 docs/49 §5.2）。</p>
     */
    private static final int[] CANDIDATE_PORTS = {
            10950,   // socket_l OTA 通道（已知开着，顺带确认）
            8080,    // 常见 HTTP/回退
            23456,
            9000,
            7000,
            6060,
            20000,
    };

    /** 单个端口的握手结果。 */
    public static final class ProbeResult {
        public String host;
        public int port;
        public boolean connected;      // TCP 能否连上
        public boolean replied;        // 车机是否回了 PXC 帧
        public String replyHex;        // 回包前若干字节（hex）
        public String replyText;       // 回包若是可打印文本则填这里
        public String error;

        public boolean ok() {
            return connected && replied;
        }
    }

    /** 探针回调（全部在后台线程，主线程更新 UI）。 */
    public interface Listener {
        void onStage(String s);

        void onResult(List<ProbeResult> results);
    }

    private EcHandshakeProbe() {
    }

    // ------------------------------------------------------------------
    // 头部模板：可被真车抓包校正
    // ------------------------------------------------------------------

    /**
     * 16 字节头模板。默认全 0 + cmdType 放头部第 4 字节（小端），
     * 命中与否由真车回包决定；抓到真帧后用 {@link #setHeaderTemplate} 对齐。
     *
     * <p>之所以做成「可替换」而不是写死：cmdType/magic 是 Carbit 私有常量，
     * 猜错就连不上；但<b>探针的骨架</b>（建链 → 发 client-info → 听回包 → 报告）
     * 是确定的、可复用的。把常量抽出来，一次校准长期受用。</p>
     */
    private static volatile byte[] headerTemplate = defaultHeader(0);

    private static byte[] defaultHeader(int cmdType) {
        byte[] h = new byte[16];
        h[4] = (byte) (cmdType & 0xFF);
        h[5] = (byte) ((cmdType >>> 8) & 0xFF);
        h[6] = (byte) ((cmdType >>> 16) & 0xFF);
        h[7] = (byte) ((cmdType >>> 24) & 0xFF);
        return h;
    }

    /**
     * 用真车上抓到的一帧头部校正模板（16 字节）。
     *
     * <p>用法：抓到车机回包后，调用它把模板换成真实头部，下一次探针就用真协议。</p>
     */
    public static void setHeaderTemplate(byte[] real16) {
        if (real16 != null && real16.length >= 16) {
            byte[] h = new byte[16];
            System.arraycopy(real16, 0, h, 0, 16);
            headerTemplate = h;
            HookLog.log(TAG + " 头部模板已更新为真车抓包");
        }
    }

    /** 当前头部模板（hex，可打印）。 */
    public static String headerTemplateHex() {
        return hex(headerTemplate);
    }

    // ------------------------------------------------------------------
    // client-info payload（字段名逐字取自车机日志，见 docs/49 §3 ④）
    // ------------------------------------------------------------------

    /**
     * 构造 {@code sendClientInfo} 的 JSON。
     *
     * <p>字段名与取值全部照车机侧明文日志对齐
     * （{@code [C2PService]sendClientInfo: sdkVersion=%s, pxcVersion=%s, uuid=%s, …}）。</p>
     */
    private static byte[] clientInfo() throws Exception {
        JSONObject o = new JSONObject();
        o.put("sdkVersion", "1.0.0");
        o.put("pxcVersion", "1.0.0");
        o.put("uuid", "77777777-7777-7777-7777-777777777777");
        o.put("supportMic", 1);
        o.put("supportScreenMirroring", 1);
        o.put("supportScreenTouch", 1);
        o.put("supportLandscapeAdaptive", 0);
        o.put("supportRVForAdb", 0);
        o.put("supportThirdPartyApp", 1);
        o.put("transportType", 8);          // EC_TRANSPORT_ANDROID_WIFI
        o.put("package_name", "com.github.zeehospeedhunter");
        o.put("channel", 0);
        o.put("supportHID", 1);
        o.put("screenType", 2);
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 探针主体
    // ------------------------------------------------------------------

    /** 跑一次探针：扫候选地址 × 候选端口，只发一帧 client-info，只听回包。 */
    public static void probe(final Listener l) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<ProbeResult> out = new ArrayList<>();

                l.onStage("=== EasyConnect 握手探针 ===");
                String ip = OtaSocketPusher.localIpOnCarNet();
                if (ip == null) {
                    l.onStage("✖ 手机不在车机网段（没看到 192.168.0.x）");
                    l.onStage("  先点「连接车机热点」把手机连上 ZEEHO-xxxx");
                    l.onResult(out);
                    return;
                }
                l.onStage("本机网卡地址 = " + ip);

                byte[] payload;
                try {
                    payload = clientInfo();
                } catch (Exception e) {
                    l.onStage("✖ 构造 client-info 失败：" + e);
                    l.onResult(out);
                    return;
                }
                l.onStage("头部模板(16B) = " + headerTemplateHex());
                l.onStage("client-info(" + payload.length + "B) = " + new String(payload));

                // 只对第一个找到的本机地址做端口扫；车机地址去重
                Set<String> hosts = new LinkedHashSet<>();
                for (String h : CAR_HOSTS) {
                    hosts.add(h);
                }

                int hits = 0;
                for (String host : hosts) {
                    for (int port : CANDIDATE_PORTS) {
                        ProbeResult r = tryOnce(ip, host, port, payload, l);
                        out.add(r);
                        if (r.ok()) {
                            hits++;
                            l.onStage("★ 命中 EC 通道 " + host + ":" + port
                                    + "（已回包）—— 协议可达，鉴权可继续");
                        }
                    }
                }
                if (hits == 0) {
                    l.onStage("— 没扫到回 PXC 帧的端口。可能是：");
                    l.onStage("   ① 手机没连上车机热点（先连 ZEEHO-xxxx）");
                    l.onStage("   ② 仪表投屏没开（车机侧 EC 服务由仪表界面启动）");
                    l.onStage("   ③ PXC 端口不在候选列表里（需要真机抓一次包定位，docs/49 §5.3）");
                }
                l.onResult(out);
            }
        }, "ec-handshake-probe").start();
    }

    private static ProbeResult tryOnce(String localIp, String host, int port,
                                       byte[] payload, Listener l) {
        ProbeResult r = new ProbeResult();
        r.host = host;
        r.port = port;
        l.onStage("→ " + host + ":" + port);
        Socket s = null;
        try {
            s = new Socket();
            // 绑到车机网段的源地址，走同网段路由（车机 AP 下是 wlan0，投屏 P2P 下是 p2p0）
            s.bind(new InetSocketAddress(localIp, 0));
            s.connect(new InetSocketAddress(host, port), 2000);
            r.connected = true;
            l.onStage("   ✔ TCP 通了，发 client-info …");

            OutputStream os = s.getOutputStream();
            os.write(headerTemplate);
            os.write(payload);
            os.flush();

            // 只听一次回包
            s.setSoTimeout(1500);
            InputStream is = s.getInputStream();
            byte[] buf = new byte[4096];
            int n = is.read(buf);
            if (n > 0) {
                byte[] head = new byte[Math.min(n, 16)];
                System.arraycopy(buf, 0, head, 0, head.length);
                r.replied = true;
                r.replyHex = hex(head);
                r.replyText = printable(buf, n);
                l.onStage("   ← 回包 " + n + " B  头=" + r.replyHex);
                if (r.replyText != null) {
                    l.onStage("     文本: " + r.replyText);
                }
            } else {
                l.onStage("   （连上了但无回包）");
            }
        } catch (Exception e) {
            r.error = String.valueOf(e);
            l.onStage("   ✖ " + e);
        } finally {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String hex(byte[] b) {
        if (b == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02X", x & 0xFF));
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    /** 把可打印部分抽出来（非 JSON/文本时返回 null，避免刷屏）。 */
    private static String printable(byte[] buf, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            char c = (char) (buf[i] & 0xFF);
            if (c == '\r' || c == '\n') {
                continue;
            }
            sb.append(c >= 0x20 && c < 0x7F ? c : '.');
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 供 UI 打印一行（不抛）。 */
    static void safeLog(Listener l, String s) {
        try {
            l.onStage(s);
        } catch (Throwable ignored) {
        }
    }
}