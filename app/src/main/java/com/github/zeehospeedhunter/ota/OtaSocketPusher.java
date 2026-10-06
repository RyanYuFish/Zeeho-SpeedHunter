package com.github.zeehospeedhunter.ota;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import com.github.zeehospeedhunter.core.HookLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.json.JSONObject;

/**
 * 把 patched 固件推给车机 —— {@code socket_l} / {@code 192.168.0.1:10950} 的 Java 实现。
 *
 * <p>Python 原型：{@code tools/ota/push-ota-socket.py}（协议证据见 docs/42、docs/44）。</p>
 *
 * <h3>帧格式</h3>
 * <pre>
 *   00 00 | u32BE cmd | u32BE len | payload        （10 字节头，全大端）
 *   0x10000011 版本协商     JSON versionCode / SP_CONFIG_MODEL_GEMINI_PROJECT_CODE
 *   0x10000001 OTA 启动     JSON fileName / fileSize / vehicleType / version / md5
 *   0x10000002 准备传输     JSON recvOtaMsgFlag            （可选，默认不发）
 *   0x10000005 传输数据     原始字节块（追加写、无序号、无块内校验）
 *   0x10000003 结束传输     JSON s_code / code
 * </pre>
 *
 * <h3>设计取向：宽松模式</h3>
 * <p>车机的 OTA Update 是<b>隐藏模式</b>（隐藏组合键才能进），厂商用「隐藏」挡普通用户，
 * 不是用复杂协议挡人 —— 所以<b>进得去就推得动</b>，不必把流程想复杂：</p>
 * <ul>
 *   <li>源IP、MD5、落盘名、块长全部自动推断；</li>
 *   <li><b>任何一步没回包都不中断</b>，照常往下走；</li>
 *   <li>{@code otaStart} 没回包自动重试一次。</li>
 * </ul>
 *
 * <p><b>红线</b>：只走 {@code 192.168.0.1:10950}，不碰其它端口；不发送任何非本协议帧。
 * 传输一旦被打断<b>不续传</b>（长度不符车机会拒收尾）。</p>
 */
public final class OtaSocketPusher {

    private static final String TAG = HookLog.OTA;

    public static final String CAR_IP = "192.168.0.1";
    public static final int CAR_PORT = 10950;

    /** 车机侧缓冲上限未知，4096 稳妥（约 26k 帧 / 100MB）。 */
    private static final int CHUNK = 4096;

    private static final int CMD_START = 0x10000001;
    private static final int CMD_PREPARE = 0x10000002;
    private static final int CMD_END = 0x10000003;
    private static final int CMD_DATA = 0x10000005;
    private static final int CMD_VERSION = 0x10000011;

    /**
     * ★★ 车机 → 手机的「校验请求」与手机 → 车机的「校验确认」（2026-10-06 固件实证）。
     *
     * <p>约定：<b>{@code 0x1xxxxxxx} = 手机发，{@code 0x8xxxxxxx} = 车机应答/主动下发</b>。
     * 命令号在 {@code libapis.so.1.0.0} 里逐个坐实：</p>
     * <pre>
     *   versionMessage       里出现 0x80000011   （车机应答版本）
     *   otaStartMessage      里出现 0x80000001
     *   otaPrepareTransfer   里出现 0x80000002
     *   otaEndMessage        里出现 0x80000003
     *   otaVerifyMessage     里出现 0x10000004   （★ 车机主动下发，等手机确认）
     *   CmdObserver::rectData 比较表里有 0x80000004（★ 手机回的确认）
     * </pre>
     *
     * <p>不回这个 ack 的后果：{@code slots_recvVerifyAckTimeout} 只会一遍遍重发
     * 「cannot recv verify message ack , retry send otaVerifyMessage to app again」，
     * 永远走不到 {@code recvVerifyAckFlag,start upgradePack} —— 也就是<b>传完不刷</b>。</p>
     */
    private static final int CMD_OTA_VERIFY = 0x10000004;      // 车机 → 手机
    private static final int CMD_OTA_VERIFY_ACK = 0x80000004;  // 手机 → 车机

    /** 探测到的车型（车机回包里解析出来的），非空时优先用于 otaStart。 */
    private static volatile String sDetectedVehicleType = null;

    /** 车机候选地址：车机 AP 一般是 192.168.0.1；本机当 P2P GO 时车机是 192.168.49.1。 */
    private static final String[] CAR_HOSTS = {"192.168.0.1", "192.168.49.1"};

    /** 车辆探测结果。 */
    public static final class VehicleInfo {
        public boolean ok;
        public String host = CAR_IP;
        public String localIp;      // 本机在车机网段的地址
        public boolean tcpOk;       // 10950 是否可连
        public String versionCode;  // 如 SW.1.01.15AE5I
        public String vin;
        public String vehicleType;  // 从 versionCode 推出的车型，如 AE5I
        public String code;         // 车机回包的 code
        public String error;
        public String hint;

        public String summary() {
            if (!ok) {
                return "✖ " + error + "\n" + hint;
            }
            return "✔ 已连上 " + host + ":" + CAR_PORT
                    + "（本机 " + localIp + "）\n"
                    + "车型 : " + nz(vehicleType) + "\n"
                    + "版本 : " + nz(versionCode) + "\n"
                    + "VIN  : " + nz(vin);
        }

        private static String nz(String s) {
            return (s == null || s.isEmpty()) ? "—" : s;
        }
    }

    /** 车辆探测回调。 */
    public interface VehicleProbeListener {
        void onStage(String s);

        void onResult(VehicleInfo info);
    }

    /**
     * <b>探测车辆</b>：一次点清「手机在不在车机网段 → 10950 通不通 → 车机到底是谁」。
     *
     * <p>车机回包（实测）：{@code ← cmd=0x80000011
     * {"versionCode":"SW.1.01.15AE5I","vin":"358122500143678","code":"1"}}。</p>
     *
     * <p>探测到的车型会记下来，{@code otaStart} 优先用它 —— 车型不符时车机
     * {@code checkUpgradeRequirements()} 返回 2，仪表就收不到进度信号。</p>
     */
    public static void probeVehicle(final VehicleProbeListener l) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final VehicleInfo info = new VehicleInfo();
                l.onStage("① 找本机在车机网段的地址 …");
                NetInfo ni = localNetOnCarNet();
                info.localIp = ni != null ? ni.ip : null;
                if (info.localIp == null) {
                    info.error = "手机不在车机网段（没看到 192.168.0.x）";
                    info.hint = "去「车机 Wi-Fi 设置」填 SSID/密码后点「连接车辆」，"
                            + "或直接手动连 ZEEHO-xxxx（手机热点必须关）";
                    l.onResult(info);
                    return;
                }
                l.onStage("   本机网卡 " + (ni != null ? ni.iface : "?") + " = " + info.localIp);
                l.onStage("② 本机 " + info.localIp + "，试连车机 …");
                for (String h : CAR_HOSTS) {
                    l.onStage("   → " + h + ":" + CAR_PORT);
                    Socket s = null;
                    try {
                        s = new Socket();
                        s.bind(new InetSocketAddress(info.localIp, 0));
                        s.connect(new InetSocketAddress(h, CAR_PORT), 6000);
                        info.host = h;
                        info.tcpOk = true;
                        l.onStage("   ✔ 通道通了，问车机是谁 …");
                        byte[] payload = json(new JSONObject()
                                .put("versionCode", 1)
                                .put("SP_CONFIG_MODEL_GEMINI_PROJECT_CODE", h));
                        // 版本帧是控制帧 → head[1] = 1
                        s.getOutputStream().write(frame(CMD_VERSION, payload));
                        s.getOutputStream().flush();
                        List<int[]> got = drain(s, 3500);
                        for (int i = 0; i < got.size(); i++) {
                            int[] g = got.get(i);
                            byte[] p = i < lastPayloads.size() ? lastPayloads.get(i) : new byte[0];
                            l.onStage("   ← cmd=0x" + Integer.toHexString(g[0]).toUpperCase()
                                    + " len=" + g[1] + " " + new String(p));
                            if (p.length > 0 && p[0] == '{') {
                                try {
                                    JSONObject o = new JSONObject(new String(p));
                                    info.versionCode = o.optString("versionCode", "");
                                    info.vin = o.optString("vin", "");
                                    info.code = o.optString("code", "");
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        break;
                    } catch (Exception e) {
                        l.onStage("   ✖ " + h + " 连不上（" + e + "）");
                    } finally {
                        close(s);
                    }
                }
                if (!info.tcpOk) {
                    info.error = "10950 没监听 —— 车机 link 服务（socket_l）没起来";
                    info.hint = "10950 由车机 OTA 模块在开机/仪表上电时拉起，被「断网事件」拆除，"
                            + "重连 AP 不会重建。对策：\n"
                            + "① 推送前【不要】用官方 Zeeho App 投屏（投屏把车机切到 P2P，"
                            + "会拆掉 10950 监听）；\n"
                            + "② 若已经投过屏，给仪表断电/重启让它重跑 initial 把 link 重新拉起；\n"
                            + "③ 确认手机热点已关、手机已连车机 AP（ZEEHO-xxxx）。";
                    l.onResult(info);
                    return;
                }
                if (info.versionCode == null || info.versionCode.isEmpty()) {
                    info.ok = true;   // 通道是通的，只是车机没回版本
                    info.hint = "通道通了但车机没回版本帧 —— 可以试着直接推送";
                    l.onResult(info);
                    return;
                }
                info.vehicleType = guessVehicleType(info.versionCode);
                if (info.vehicleType != null) {
                    sDetectedVehicleType = info.vehicleType;
                }
                info.ok = true;
                l.onResult(info);
            }
        }, "vehicle-probe").start();
    }

    /** 从 versionCode 推车型：{@code SW.1.01.15AE5I} → {@code AE5I}。 */
    private static String guessVehicleType(String versionCode) {
        if (versionCode == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([A-Za-z]{2}[0-9][A-Za-z0-9]*)$").matcher(versionCode);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    /** otaStart 用的车型：探测到的优先，兜底 AE5I。 */
    private static String vehicleType() {
        return sDetectedVehicleType != null ? sDetectedVehicleType : VEHICLE_TYPE;
    }

    /**
     * 车型。★ 2026-10-06 车机回包实测：
     * {@code ← cmd=0x80000011 {"versionCode":"SW.1.01.15AE5I",...}}
     * ⇒ 这台车是 <b>AE5I（全大写）</b>，之前写的 "AE5i" 与车机
     * {@code SP_CONFIG_MODEL_GEMINI_PROJECT_CODE} 不符，会让
     * {@code checkUpgradeRequirements()} 返回 2 ⇒ 仪表收不到进度信号。
     */
    private static final String VEHICLE_TYPE = "AE5I";
    private static final String VERSION = "1.0.1";

    /** 回调：日志实时输出 + 进度 + 结束时汇总。 */
    public interface Callback {
        void onLog(String line);

        /** 传输进度。{@code percent<0} 表示阶段日志（不是百分比）。 */
        default void onProgress(int percent, long sent, long total) {
        }

        void onDone(boolean ok, String summary);
    }

    private OtaSocketPusher() {
    }

    // ------------------------------------------------------------ 协议基础

    /**
     * 帧头第 2 字节 = <b>通道</b>（2026-10-06 从 {@code CmdObserver::rectData} 的分派表坐实）：
     * <pre>
     *   head[1] == 0 → 只认 0x10000005(数据) / 0x10000006(进度) / 0x10000044
     *   head[1] != 0 → 认 0x10000001 start / 02 prepare / 03 end / 07 cancel
     *                   0x10000011 version / 0x80000004 校验ack / 0x10000043/45 壁纸
     * </pre>
     * 也就是说：<b>控制帧必须 head[1]=1，数据帧必须 head[1]=0</b>。
     * 我们一直全填 0 ⇒ start/end/version 全被丢进「不认」的分支，
     * FILE* 没开、门控没开 ⇒ 106MB 数据被 wirteOtaData 静默丢弃 —— 车机从头到尾零反应。
     */
    private static byte[] frame(int cmd, byte[] payload) {
        return frame(cmd, payload, cmd == CMD_DATA ? 0 : 1);
    }

    private static byte[] frame(int cmd, byte[] payload, int head1) {
        byte[] f = new byte[10 + payload.length];
        f[0] = 0;
        f[1] = (byte) head1;
        f[2] = (byte) (cmd >>> 24);
        f[3] = (byte) (cmd >>> 16);
        f[4] = (byte) (cmd >>> 8);
        f[5] = (byte) cmd;
        f[6] = (byte) (payload.length >>> 24);
        f[7] = (byte) (payload.length >>> 16);
        f[8] = (byte) (payload.length >>> 8);
        f[9] = (byte) payload.length;
        System.arraycopy(payload, 0, f, 10, payload.length);
        return f;
    }

    /** 收一会儿，把车机主动推的帧解析出来。 */
    private static List<int[]> drain(Socket s, int waitMs) {
        List<int[]> out = new ArrayList<>();
        List<byte[]> pl = new ArrayList<>();
        byte[] buf = new byte[65536];
        long end = System.currentTimeMillis() + waitMs;
        try {
            s.setSoTimeout(400);
        } catch (Exception ignored) {
        }
        while (System.currentTimeMillis() < end) {
            int n;
            try {
                n = s.getInputStream().read(buf);
            } catch (Exception e) {
                continue;
            }
            if (n <= 0) {
                break;
            }
            // 累积到 pts 里解析
            int off = 0;
            while (off + 10 <= n) {
                int cmd = ((buf[off + 2] & 0xFF) << 24) | ((buf[off + 3] & 0xFF) << 16)
                        | ((buf[off + 4] & 0xFF) << 8) | (buf[off + 5] & 0xFF);
                int len = ((buf[off + 6] & 0xFF) << 24) | ((buf[off + 7] & 0xFF) << 16)
                        | ((buf[off + 8] & 0xFF) << 8) | (buf[off + 9] & 0xFF);
                if (off + 10 + len > n) {
                    break;
                }
                byte[] p = new byte[len];
                System.arraycopy(buf, off + 10, p, 0, len);
                out.add(new int[]{cmd, len});
                pl.add(p);
                off += 10 + len;
            }
        }
        // 把 payload 文本挂到 int[]{cmd,len} 的第三个元素不现实，改用平行列表返回
        lastPayloads.clear();
        lastPayloads.addAll(pl);
        return out;
    }

    private static final List<byte[]> lastPayloads = Collections.synchronizedList(new ArrayList<byte[]>());

    /** 发一帧、收回包。<b>没回包不中断</b>（宽松模式）。 */
    private static boolean step(Socket s, int cmd, byte[] payload, String label,
                               int waitMs, Callback cb, int tries) {
        for (int attempt = 1; attempt <= Math.max(1, tries); attempt++) {
            String extra = tries > 1 ? "（第 " + attempt + " 次）" : "";
            cb.onLog(String.format("→ %s%s  cmd=0x%08X  %d B", label, extra, cmd, payload.length));
            if (payload.length > 0 && payload[0] == '{') {
                cb.onLog("  " + new String(payload));
            }
            try {
                s.getOutputStream().write(frame(cmd, payload));
                s.getOutputStream().flush();
            } catch (Exception e) {
                cb.onLog("   ! 发送失败：" + e);
                return false;
            }
            List<int[]> got = drain(s, waitMs);
            if (!got.isEmpty()) {
                for (int i = 0; i < got.size(); i++) {
                    int[] g = got.get(i);
                    byte[] p = i < lastPayloads.size() ? lastPayloads.get(i) : new byte[0];
                    String t;
                    if (p.length > 0 && p[0] == '{') {
                        t = "  " + new String(p);
                    } else if (p.length > 0) {
                        // 非 JSON（比如车机那句中文「服务器忙碌…」）也打出来，
                        // 否则现场只能看到 len=NN 却不知道车机说了什么
                        t = "  [" + new String(p).replaceAll("[^\\x20-\\x7e]", ".") + "]";
                    } else {
                        t = "";
                    }
                    cb.onLog(String.format("   ← cmd=0x%08X len=%d%s", g[0], g[1], t));
                }
                return true;
            }
            cb.onLog("   （无回包，继续）");
        }
        return false;
    }

    // ------------------------------------------------------------ 工具

    /** 本机在车机网段的一张网卡：接口名 + IP。 */
    public static final class NetInfo {
        public String iface;   // 如 wlan0 / p2p0 / wlan1
        public String ip;      // 192.168.0.x
    }

    /** 挑一张 192.168.0.0/24 上的本机网卡（投屏时是 p2p0，连 SoftAP 时是 wlan0）。 */
    public static NetInfo localNetOnCarNet() {
        NetInfo info = new NetInfo();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    String ip = addrs.nextElement().getHostAddress();
                    if (ip != null && ip.startsWith("192.168.0.")) {
                        info.iface = ni.getName();
                        info.ip = ip;
                        return info;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 兼容旧调用：只返回 IP。 */
    public static String localIpOnCarNet() {
        NetInfo n = localNetOnCarNet();
        return n != null ? n.ip : null;
    }

    /**
     * 探一下 10950 是否真的在监听（纯 TCP connect，不发任何帧）。
     *
     * <p>用来区分两种「不通」：① 手机根本不在车机网段（没 192.168.0.x 地址）；
     * ② 在车机网段、但 {@code connect} 被拒/超时 —— 说明车机 link 服务没起来。
     * 后者才是固件层面的坑（见 {@link #probeVehicle} 的 hint）。</p>
     */
    public static boolean probeLink() {
        String ip = localIpOnCarNet();
        if (ip == null) {
            return false;
        }
        Socket s = null;
        try {
            s = new Socket();
            s.bind(new InetSocketAddress(ip, 0));
            s.connect(new InetSocketAddress(CAR_IP, CAR_PORT), 2500);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            close(s);
        }
    }

    private static String md5Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static Socket connect(Callback cb) throws Exception {
        String ip = localIpOnCarNet();
        if (ip != null) {
            cb.onLog("源地址绑 " + ip);
        } else {
            cb.onLog("⚠ 未找到 192.168.0.x 的本机地址，用系统默认路由");
        }
        Socket s = new Socket();
        if (ip != null) {
            s.bind(new InetSocketAddress(ip, 0));
        }
        s.connect(new InetSocketAddress(CAR_IP, CAR_PORT), 8000);
        cb.onLog("已连接 " + s.getLocalSocketAddress() + " → "
                + s.getRemoteSocketAddress());
        return s;
    }

    private static byte[] json(JSONObject o) {
        return o.toString().getBytes();
    }

    /**
     * 打日志，但<b>绝不让日志本身把协议流程打断</b>。
     *
     * <p>★ 2026-10-06 车边实测：{@code String.format("%.0fs", long)} 抛
     * {@code IllegalFormatConversionException}，把 100% 之后本该发出的
     * 「结束传输」帧一并带没了 —— 数据其实发完了，日志却报失败。
     * 格式化属于旁枝，出任何问题只能降级成原文，不能中断主流程。</p>
     */
    private static void safeLog(Callback cb, String fmt, Object... args) {
        try {
            cb.onLog(String.format(fmt, args));
        } catch (Exception e) {
            try {
                cb.onLog(fmt + "（日志格式化出错：" + e + "）");
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 对外

    /** 只发版本协商，探一下通不通。 */
    public static void probe(final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                cb.onLog("=== 探测 " + CAR_IP + ":" + CAR_PORT + " ===");
                cb.onLog("本机网卡：" + localIpOnCarNet());
                Socket s = null;
                try {
                    s = connect(cb);
                    step(s, CMD_VERSION, json(new JSONObject()
                                    .put("versionCode", 1)
                                    .put("SP_CONFIG_MODEL_GEMINI_PROJECT_CODE", CAR_IP)),
                            "版本协商", 3000, cb, 1);
                } catch (Exception e) {
                    cb.onLog("连不上 —— " + e);
                    cb.onLog("  · 超时/拒绝 ⇒ 车机 EasyConnect 没起来"
                            + "（先投屏，或用隐藏组合键进 OTA Update）");
                    cb.onLog("  · 连上后收到中文「服务器忙碌…」⇒ 被官方 App 占用，先断官方投屏");
                } finally {
                    close(s);
                }
                cb.onDone(true, "探测结束");
            }
        }, "ota-probe").start();
    }

    /**
     * 结束帧的负载。
     *
     * <p>★ 2026-10-06 第二个坑：车机是从<b>这个 JSON</b> 里读
     * {@code recvOtaFileFinishedFlag} 的（固件串：
     * {@code recvOtaFileFinishedFlag false,should not verify file}）。
     * 之前只发 {@code {"s_code":0,"code":0}}，缺这个键 ⇒ 车机判 false ⇒
     * 直接返回，后面的 MD5 校验 / 解压 / 校验请求<b>一步都不会跑</b>。</p>
     */
    private static byte[] endPayload() throws Exception {
        return json(new JSONObject()
                .put("recvOtaFileFinishedFlag", 1)
                .put("s_code", 0)
                .put("code", 0));
    }

    /**
     * 收尾监听：车机任何下发帧都打出来；遇到 {@code 0x10000004}（otaVerifyMessage）
     * 立刻回 {@code 0x80000004}，因为<b>只有收到这个 ack 车机才会 start upgradePack</b>。
     *
     * <p>车机侧日志串佐证：{@code recvVerifyAckFlag,start upgradePack}（来自
     * {@code slots_recvVerifyAckTimeout}）。整个收尾最长等 {@code timeoutMs}。</p>
     *
     * @return 发出的 ack 次数
     */
    private static int waitVerifyAndAck(Socket s, Callback cb, long timeoutMs) {
        int acks = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        cb.onLog("→ 等车机校验请求（最多 " + timeoutMs / 1000 + "s），收到就回 ack …");
        while (System.currentTimeMillis() < deadline) {
            if (s.isClosed() || s.isInputShutdown()) {
                cb.onLog("   ! 连接已断，停止等待");
                break;
            }
            List<int[]> got = drain(s, 2000);
            for (int i = 0; i < got.size(); i++) {
                int[] g = got.get(i);
                byte[] p = i < lastPayloads.size() ? lastPayloads.get(i) : new byte[0];
                String txt = (p.length > 0 && p[0] == '{') ? "  " + new String(p) : "";
                safeLog(cb, "   ← cmd=0x%08X len=%d%s", g[0], g[1], txt);
                if (g[0] == CMD_OTA_VERIFY) {
                    acks++;
                    cb.onLog("   ★ 收到校验请求，回 ack（车机靠这个才 start upgradePack）");
                    try {
                        s.getOutputStream().write(frame(CMD_OTA_VERIFY_ACK,
                                json(new JSONObject().put("recvVerifyAckFlag", 1))));
                        s.getOutputStream().flush();
                        cb.onLog("   → ack 已发  cmd=0x80000004");
                    } catch (Exception e) {
                        cb.onLog("   ! ack 发送失败：" + e);
                    }
                }
            }
        }
        if (acks == 0) {
            cb.onLog("   ⚠ 没等到车机的校验请求 —— 检查车机是否停在 OTA Update、电量 ≥20%");
        }
        return acks;
    }

    /**
     * <b>纯监听 + 自动 ack</b>：连上之后<b>一个字节都不主动发</b>，只听车机下发。
     *
     * <p>用途：车机可能还在慢慢算 MD5 / 解压 106MB，等它缓过来才发
     * {@code 0x10000004}；此时新开一条连接<b>什么都不发</b>、只等它开口，
     * 收到立刻回 ack —— 比再推一遍 4.5 分钟划算得多。</p>
     */
    public static void listenOnly(final Callback cb, final long ms) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                cb.onLog("=== 纯监听 " + (ms / 1000) + "s（不发任何帧，只等车机要确认）===");
                Socket s = null;
                try {
                    s = connect(cb);
                    waitVerifyAndAck(s, cb, ms);
                } catch (Exception e) {
                    cb.onLog("连不上 —— " + e);
                } finally {
                    close(s);
                }
                cb.onDone(true, "监听结束");
            }
        }, "ota-listen").start();
    }

    /**
     * <b>只补发「结束传输」帧</b>（{@code 0x10000003}）—— 数据已发完、车机却没动静时的补救动作。
     *
     * <p>★ 为什么安全：不发 {@code otaStart}，车机就不会再
     * {@code fopen64("/media/flash/userdata/update.zip","wb")}，
     * 已写满的那个文件<b>不会被截断、不会被覆盖</b>。
     * 只发结束帧；如果这个会话的状态还在，车机就会走校验 / 刷写。</p>
     */
    public static void endOnly(final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                cb.onLog("=== 补发结束帧 ===");
                Socket s = null;
                try {
                    s = connect(cb);
                    step(s, CMD_VERSION, json(new JSONObject()
                                    .put("versionCode", 1)
                                    .put("SP_CONFIG_MODEL_GEMINI_PROJECT_CODE", CAR_IP)),
                            "版本协商", 1500, cb, 1);
                    step(s, CMD_END, endPayload(),
                            "结束传输", 25000, cb, 1);
                    // 注意：换过连接后车机的 recvOtaFileFinishedFlag 已经是 false，
                    // 大概率收不到校验请求；真要刷写还是得整包重推（一次连接走完）。
                    waitVerifyAndAck(s, cb, 60000);
                } catch (Exception e) {
                    cb.onLog("连不上 —— " + e);
                } finally {
                    close(s);
                }
                cb.onDone(true, "结束帧已发，看车机屏幕");
            }
        }, "ota-end").start();
    }

    /** 推送指定包。 */
    public static void push(final File file, final boolean withPrepare, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Socket s = null;
                long sent = 0;
                try {
                    long size = file.length();
                    // ★ 落盘路径由车机写死：startOta() 里
                    //   fopen64("/media/flash/userdata/update.zip", "wb")
                    //   （libapis.so.1.0.0 @0x3b840 实证），
                    //   所以 fileName 只是车机侧账本字段，按官方流程填 update.zip。
                    String name = "update.zip";
                    String digest = md5Of(file);
                    cb.onLog("=== 待推送 ===");
                    cb.onLog("  包     : " + file.getAbsolutePath());
                    cb.onLog("  大小   : " + size + " 字节");
                    cb.onLog("  落盘名 : " + name);
                    cb.onLog("  MD5    : " + digest);
                    cb.onLog("  块长   : " + CHUNK + "（共 " + (-(-size / CHUNK)) + " 帧）");

                    s = connect(cb);

                    step(s, CMD_VERSION, json(new JSONObject()
                                    .put("versionCode", 1)
                                    .put("SP_CONFIG_MODEL_GEMINI_PROJECT_CODE", CAR_IP)),
                            "版本协商", 2000, cb, 1);

                    // OTA 启动：车机据此打开 /media/flash/userdata/<name>
                    // 没回包自动重试一次，然后照样往下走
                    step(s, CMD_START, json(new JSONObject()
                                    .put("fileName", name)
                                    .put("fileSize", size)
                                    .put("vehicleType", vehicleType())
                                    .put("version", VERSION)
                                    .put("md5", digest)),
                            "OTA 启动", 6000, cb, 2);

                    if (withPrepare) {
                        step(s, CMD_PREPARE, json(new JSONObject()
                                        .put("recvOtaMsgFlag", 1)),
                                "准备传输", 4000, cb, 1);
                    }

                    cb.onLog("→ 传输数据 …");
                    InputStream in = new FileInputStream(file);
                    try {
                        byte[] buf = new byte[CHUNK];
                        int last = 0;
                        long t0 = System.currentTimeMillis();
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            byte[] payload = new byte[n];
                            System.arraycopy(buf, 0, payload, 0, n);
                            s.getOutputStream().write(frame(CMD_DATA, payload));
                            sent += n;
                            int pct = (int) (sent * 100 / size);
                            if (pct >= last + 10) {
                                last = pct;
                                double dt = Math.max(System.currentTimeMillis() - t0, 1) / 1000.0;
                                cb.onProgress(pct, sent, size);
                                safeLog(cb, "   %3d%%  %d/%d  %.1f MB/s",
                                        pct, sent, size, sent / dt / 1048576.0);
                            }
                        }
                        s.getOutputStream().flush();
                        // ★ 2026-10-06 车边实测踩坑：这里原本写
                        //     String.format("... 用时 %.0fs", (end - t0) / 1000)
                        //   —— 传进去的是 long，%.0f 要浮点，直接抛
                        //     IllegalFormatConversionException: f != java.lang.Long。
                        //   异常把 100% 之后的「结束传输」帧也带没了（见下方 safety）。
                        //   现在：① 除的是 1000.0（double）② 整句套 safeLog，日志再出错也不影响协议。
                        safeLog(cb, "   100%%  %d/%d  用时 %.0fs", sent, size,
                                (System.currentTimeMillis() - t0) / 1000.0);
                    } finally {
                        in.close();
                    }

                    // 结束：车机收到后走 md5 校验 → 解压 → 校验 pack → 再向手机要确认
                    step(s, CMD_END, endPayload(),
                            "结束传输", 20000, cb, 1);

                    // ★★ 关键：等车机的 otaVerifyMessage(0x10000004)，回 ack(0x80000004)。
                    //    缺这一步，车机只会反复重发校验请求，不会真正开始刷写。
                    // 车机要先 md5(106MB) → 解压 → 校验 pack，慢机器上可能好几分钟；
                    // 窗口给足 10 分钟，宁可多等也别提前关门（门一关 ack 就没处回了）。
                    int acks = waitVerifyAndAck(s, cb, 600000);

                    cb.onDone(true, String.format("已发出 %d 字节 / %d 帧；回校验确认 %d 次。"
                                    + "看车机屏幕：MD5 校验 → 解压 → 校验 pack → 升级。",
                            sent, -(-size / CHUNK), acks));
                } catch (Exception e) {
                    cb.onDone(false, String.format("中断在 %d 字节：%s\n"
                            + "不要续传（长度不符车机会拒收尾），重推一次更省事。", sent, e));
                } finally {
                    close(s);
                }
            }
        }, "ota-push").start();
    }

    private static void close(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------ 包定位

    private static final String[] CANDIDATES = {
            "/sdcard/Download/OTA_zip_PATCHED_185_v3.zip",
            "/sdcard/Download/OTA_zip_PATCHED_185_v2.zip",
            "/sdcard/Download/1787815351970919959.zip",
            "/sdcard/Download/OTA_zip_PATCHED_185.zip",
            "/storage/emulated/0/Download/OTA_zip_PATCHED_185_v3.zip",
    };

    /** 在常见位置找 patched 包；找不到返回 null。 */
    public static File locatePackage() {
        for (String p : CANDIDATES) {
            File f = new File(p);
            if (f.isFile() && f.length() > 0) {
                return f;
            }
        }
        return null;
    }

    /**
     * 在常见位置 + <b>应用私有外部目录</b> 找 patched 包。
     *
     * <p>★ 私有目录（{@code /sdcard/Android/data/<pkg>/files}）是 Android 11+ 唯一
     * 不需要任何存储权限就能直读的位置，adb push 过去即可用：</p>
     * <pre>
     *   adb push OTA_zip_PATCHED_185_v3.zip \
     *     /sdcard/Android/data/com.github.zeehospeedhunter/files/
     * </pre>
     */
    public static File locatePackage(Context ctx) {
        File f = locatePackage();
        if (f != null) {
            return f;
        }
        if (ctx == null) {
            return null;
        }
        File[] dirs = {
                ctx.getExternalFilesDir(null),
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                ctx.getExternalCacheDir(),
        };
        for (File d : dirs) {
            if (d == null) {
                continue;
            }
            File g = new File(d, "OTA_zip_PATCHED_185_v3.zip");
            if (g.isFile() && g.length() > 0) {
                return g;
            }
            File[] all = d.listFiles();
            if (all != null) {
                for (File c : all) {
                    String n = c.getName().toLowerCase();
                    if (c.isFile() && c.length() > 0 && n.endsWith(".zip")) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 把用户在系统文件选择器里选的 content:// URI 拷到私有缓存，返回一个能直读的 File。
     *
     * <p>Android 11+（实测 Android 16 / targetSdk 34）用文件路径读
     * {@code /sdcard/Download/xxx.zip} 会抛
     * {@code EACCES (Permission denied)} —— 非媒体文件不走 MediaStore，
     * 只能走 SAF 或「所有文件访问」。SAF 这条路不需要任何权限。</p>
     */
    public static File copyFromUri(Context ctx, Uri uri) throws Exception {
        File out = new File(ctx.getCacheDir(), "push_pkg.zip");
        if (out.exists()) {
            out.delete();
        }
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new java.io.FileNotFoundException("打不开 " + uri);
        }
        OutputStream os = new FileOutputStream(out);
        try {
            byte[] buf = new byte[1 << 20];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
            }
            if (total == 0) {
                throw new java.io.IOException("选到的文件是空的");
            }
        } finally {
            os.close();
            in.close();
        }
        return out;
    }
}
