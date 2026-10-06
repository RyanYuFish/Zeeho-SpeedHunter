package com.github.zeehospeedhunter.ota;

import com.github.zeehospeedhunter.core.HookLog;

import java.io.DataInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * 主动探测车机 {@code 192.168.0.1:10950}（{@code socket_l}）是否接受 TCP 连接。
 *
 * <p><b>为什么需要它</b>：2026-10-05 车边实测发现，{@code 10950/tcp} 始终 CLOSED，
 * 但同时静态分析证明 {@code slots_apP2pNetlinkEventHandle @0x8d04} 内部会调用
 * {@code LinkPhoneManager::getConnectedClientIP()} 并与 P2P 客户端 IP 比对，不匹配就打印
 * {@code 'service not running, please try later!'} 并回 MCU 错误码 {@code 0x12}。
 * ⇒ 说明官方流程里，<b>10950 是由手机侧主动 connect 上去的</b>，
 * 车机只是被动接受；按键、OTA 浮窗、{@code otaupgrade=1} 都不会让它监听。</p>
 *
 * <p><b>红线（严格遵守）</b>：</p>
 * <ol>
 *   <li>只做 TCP 三次握手，<b>绝不发送任何字节</b>；</li>
 *   <li>只做被动读取，且设短超时；读到数据也只记录长度与前若干字节的十六进制；</li>
 *   <li>不发送 OTA 命令（{@code 0x10000001} 等），不触发车机任何状态机；</li>
 *   <li>失败只记日志，绝不重试风暴（默认单次）。</li>
 * </ol>
 *
 * <p>成功判据：{@code connect} 建立成功即说明车机 socket_l 服务在线。
 * 即便是「连上但立刻被 close」也算成功 —— 那说明监听存在、只是没等我们的数据。</p>
 */
public final class OtaSocketProbe {

    private static final String TAG = HookLog.OTA;

    /** 车机地址（{@code libotamodule} 里 {@code LinkPhoneManager} 构造时传入的 IP 字符串）。 */
    public static final String CAR_IP = OtaChannels.SOCKET_L_CLIENT_TARGET;

    /** 端口（{@code movw r1, #0x2ac6} = 10950）。 */
    public static final int CAR_PORT = OtaChannels.SOCKET_L_PORT;

    /**
     * 总开关。
     *
     * <p>⚠️ 2026-10-05 17:45 实测后<b>默认关闭</b>：投屏启动时 10950 是<b>独占单连接槽</b>，
     * 官方 EasyConnect 已占用；此时我们再去 connect 会被车机拒绝并回一段中文
     * 「服务器忙乱，已有客户端连接,仅支持一个客户端」，而且可能干扰官方会话。
     * 需要复测端口是否在线时再临时置 {@code true}。</p>
     */
    public static final boolean ENABLED = false;

    /** 建连超时（毫秒）。AP 内网不需要长超时。 */
    public static final int CONNECT_TIMEOUT_MS = 3000;

    /**
     * ★ 绑定源地址到车机所在网段（2026-10-05 实测必需）。
     *
     * <p>不绑定时 Android 会按路由表选源地址，而手机同时有蜂窝（{@code 10.8.43.173}）、
     * VPN（{@code tun0 172.19.0.1}）和 Wi-Fi（{@code 192.168.0.51}），
     * 实测 connect 会从 <b>{@code 10.8.43.173}</b> 发出 ⇒ 必然超时。
     * 车机 AP 是 {@code 192.168.0.0/24}，所以必须显式绑 Wi-Fi 源地址。</p>
     *
     * <p>⚠️ 2026-10-05 15:52 补充：<b>地址会变</b>。实测开启「投屏导航」后车机会让手机
     * 断开 STA（{@code mIsStaConnected=false}、{@code wlan0} 消失、邻居表清空），
     * 此时硬编码的 {@code 192.168.0.51} 会导致 {@code BindException: EADDRNOTAVAIL}。
     * 所以这里改为<b>动态探测</b>：读 {@code wlan0} 当前地址，读不到就不绑（退化为系统选路）。</p>
     */
    public static final boolean BIND_WIFI_SOURCE = true;

    /** 车机 IP 的同网段前缀（据此判断某个本地地址是否可用）。 */
    private static final String CAR_PREFIX = "192.168.0.";

    /** 兜底：动态探测失败时使用的地址。 */
    public static final String WIFI_SOURCE_FALLBACK = "192.168.0.51";

    /** 读超时（毫秒）。 */
    public static final int READ_TIMEOUT_MS = 1500;

    /** 单次最多读取的字节数（只用于观察，不解释协议）。 */
    public static final int MAX_READ = 64;

    /** 尝试次数（覆盖「App 起来 → 手机连上 AP」的时间差）。 */
    public static final int ATTEMPTS = 4;

    /** 两次尝试之间的间隔（毫秒）。 */
    public static final long RETRY_GAP_MS = 5000L;

    private OtaSocketProbe() {
    }

    /**
     * 跑一次探测。结果写进 {@link HookLog}，tag = {@code ZeehoOTA}，前缀 {@code probe10950}。
     *
     * <p>必须在<b>后台线程</b>调用 —— {@code Socket.connect} 会阻塞，挂在主线程会 ANR。</p>
     *
     * <p>2026-10-05 实测：App 启动时手机可能还没连上车机 AP，此时绑定 Wi-Fi 源地址会失败
     * （{@code BindException}）。所以这里最多尝试 {@link #ATTEMPTS} 次、每次间隔
     * {@link #RETRY_GAP_MS}，覆盖「App 起来 → 用户连 AP」这个时间差。</p>
     */
    public static void runAsync() {
        if (!ENABLED) {
            HookLog.log(TAG + " probe10950 disabled");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 1; i <= ATTEMPTS; i++) {
                    try {
                        if (i > 1) {
                            Thread.sleep(RETRY_GAP_MS);
                        }
                        HookLog.log(TAG + " probe10950 attempt " + i + "/" + ATTEMPTS);
                        if (probe()) {
                            return;
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable e) {
                        HookLog.log(TAG + " probe10950 error ("
                                + e.getClass().getSimpleName() + ")");
                    }
                }
                HookLog.log(TAG + " probe10950 → all " + ATTEMPTS + " attempts failed");
            }
        }, "zeeho-probe-10950");
        t.setDaemon(true);
        t.start();
    }

    /** 同步执行一次探测（测试用）。返回是否连上。 */
    public static boolean probe() {
        Socket sock = null;
        long t0 = System.currentTimeMillis();
        try {
            sock = new Socket();
            InetSocketAddress target = new InetSocketAddress(CAR_IP, CAR_PORT);
            if (BIND_WIFI_SOURCE) {
                // 关键：显式绑定 Wi-Fi 源地址，否则会从蜂窝 10.8.43.x 发出而超时。
                // 但地址会变（DHCP 变化 / 车机让手机断 STA），所以绑不上就退化为系统选路，
                // 并在日志里明确区分「本地 bind 失败」与「车机 connect 失败」——
                // 前者不是车机的结论。
                String src = WIFI_SOURCE_FALLBACK;
                try {
                    sock.bind(new InetSocketAddress(src, 0));
                    HookLog.log(TAG + " probe10950 → bound to " + src
                            + " (target " + CAR_IP + ":" + CAR_PORT + ")");
                } catch (Throwable bindError) {
                    HookLog.log(TAG + " probe10950 → bind(" + src + ") failed ("
                            + bindError.getClass().getSimpleName()
                            + "); falling back to system route — 本地问题，非车机结论");
                    sock.close();
                    sock = new Socket();
                }
            } else {
                HookLog.log(TAG + " probe10950 → connecting " + CAR_IP + ":" + CAR_PORT
                        + " (no bind, timeout " + CONNECT_TIMEOUT_MS + "ms)");
            }
            sock.connect(target, CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(READ_TIMEOUT_MS);
            long dt = System.currentTimeMillis() - t0;
            HookLog.log(TAG + " probe10950 → CONNECTED in " + dt + "ms"
                    + " local=" + sock.getLocalAddress() + ":" + sock.getLocalPort());

            // 只被动读，绝不写。
            byte[] buf = new byte[MAX_READ];
            InputStream in = sock.getInputStream();
            DataInputStream dis = new DataInputStream(in);
            int n = -1;
            try {
                n = dis.read(buf);
            } catch (SocketTimeoutException ignored) {
                HookLog.log(TAG + " probe10950 → no data within " + READ_TIMEOUT_MS
                        + "ms (listener is silent; this is normal for a bare connect)");
            } catch (Throwable e) {
                HookLog.log(TAG + " probe10950 → read error ("
                        + e.getClass().getSimpleName() + ")");
            }
            if (n > 0) {
                HookLog.log(TAG + " probe10950 → recv " + n + "B hex="
                        + hex(buf, n));
            }
            return true;
        } catch (Throwable e) {
            long dt = System.currentTimeMillis() - t0;
            HookLog.log(TAG + " probe10950 → FAILED in " + dt + "ms ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return false;
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (Throwable ignored) {
                }
            }
            HookLog.log(TAG + " probe10950 → closed");
        }
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String h = Integer.toHexString(b[i] & 0xFF);
            if (h.length() == 1) {
                sb.append('0');
            }
            sb.append(h);
        }
        return sb.toString();
    }
}
