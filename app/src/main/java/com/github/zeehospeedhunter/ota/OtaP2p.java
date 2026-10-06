package com.github.zeehospeedhunter.ota;

import android.content.Context;
import android.net.wifi.WpsInfo;                 // ★ 在 android.net.wifi 下，不在 p2p 包里
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;

import com.github.zeehospeedhunter.core.HookLog;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * ★ Wi-Fi Direct（P2P）建连——「最后一公里」的前半段。
 *
 * <h3>为什么必须走 P2P</h3>
 * <p>实测（2026-10-04）：手机连上车机 SoftAP {@code ZEEHO-204dfd}（车机 {code 192.168.0.1}）
 * 之后<b>扫描 1–20000 端口，只有 53(DNS) 是开的</b> —— ADB 根本不在 AP 网段上。
 * 官方 ZeeCare、第三方「极核仪表 OTA 工具」连的都是 <b>{@code 192.168.49.1:5555}</b>：
 * Wi-Fi Direct Group Owner 的标准网关地址。</p>
 *
 * <h3>车机在 P2P 里的身份</h3>
 * <ul>
 *   <li>P2P 设备名形如 {@code ZEEHO-047c01}；<b>P2P MAC = {@code 6c:12:4a:18:09:f7}</b>
 *       —— 末字节 f7，与 SoftAP BSSID {@code 6c:12:4a:18:09:ff} 不同：同一台设备
 *       在 AP 与 P2P 两个接口上各用一个 MAC。</li>
 *   <li>车机是 <b>Group Owner</b>，手机是 client ⇒ {@code groupOwnerIntent = 0}。</li>
 *   <li>手机上 {@code p2p_supplicant.conf} 存着持久组
 *       {@code DIRECT-y2-Android_H1dg}（bssid 1e:c3:14:6c:87:cc、psk VQD9Hb1J），
 *       两边曾经组过网；所以这里先复用现成 Group，不成再退到 {@code createGroup()}。</li>
 *   <li>{@code dumpsys wifip2p} 的 REINVOKE 记录显示 com.cfmoto 每 ~8s
 *       {@code discoverPeers}/{@code stopDiscovery} 轮一轮，是车机在找手机。</li>
 * </ul>
 *
 * <h3>实现取舍</h3>
 * <p>{@link WifiP2pManager} 的回调都由构造时传入的 {@link Handler} 派发（主线程），
 * 而推包流程跑在后台线程 —— 所以本类把「异步回调」收进 {@code synchronized + notifyAll}，
 * 对对外只暴露 {@link #connectBlocking(long)} 这种同步调用。</p>
 *
 * <p>另外：所有 listener 都<b>以参数形式直接传给 manager</b>
 * （{@code requestPeers} / {@code requestGroupInfo} / {@code requestConnectionInfo}），
 * 不走 {@code Channel#setXxxListener}（后者是 @hide，靠它会编不过）。</p>
 */
public final class OtaP2p {

    private static final String TAG = HookLog.OTA + " P2p";

    /**
     * 车机 P2P device MAC —— 比对 peer 用 MAC 比猜设备名稳。
     *
     * <p>来源不是猜的，是 {@code /data/vendor/wifi/wpa/p2p_supplicant.conf} 里
     * {@code DIRECT-y2-Android_H1dg} 这条持久组的 {@code p2p_client_list}：
     * 本机的 p2p device 固定 MAC 是 {@code 1e:c3:14:6c:87:cc}（同一文件里的
     * {@code p2p_device_persistent_mac_addr}），组里另一个 MAC 就是车机。</p>
     */
    private static final String CAR_MAC = "6a:c8:c0:db:8f:3c";

    /** 车机另一张 P2P 接口的 MAC（末段 f7，与 SoftAP 的 ff 同一块 OUI），作为备选。 */
    private static final String CAR_MAC_ALT = "6c:12:4a:18:09:f7";

    /** {@code DIRECT-y2-Android_H1dg} 在 supplicant 里的 networkId —— resume 持久组要用。 */
    private static final int CAR_NET_ID = 24;

    /** discoverPeers 重试轮数（每轮约 7s，够等 P2P enabled + 一轮 scan）。 */
    private static final int P2P_RETRY = 5;

    /** connect 重试轮数。 */
    private static final int CONNECT_RETRY = 4;

    /** 本机当 GO 时，车机一定落在 +49 段。 */
    private static final String GO_FALLBACK_IP = "192.168.49.1";

    public interface LineSink {
        void onLine(String line);
    }

    private final Context ctx;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler ui;
    private final Object mon = new Object();

    private final List<WifiP2pDevice> peers = new ArrayList<>();
    private final List<String> clientIps = new ArrayList<>();
    private volatile LineSink sink;

    private boolean formed;
    private boolean groupOwner;
    private boolean peersArrived;
    private boolean hasOwnGroup;      // 本机残留着 GO 组（要 delete 后再 create）
    private boolean powerCycle;       // 建连前是否把 Wi-Fi 关一圈（默认否）
    private int p2pUnsupportedHits;   // 连续收到的 reason=0 次数（用来区分「还没启用」和「supplicant 卡死」）
    private String ownGroupStatus;
    private String lastGroupNet;      // 最近一次 recordGroup 看到的组名（判断是 P2P 组还是 SoftAP）
    private String ownerAddress;
    private String status = "未开始";

    private OtaP2p(Context ctx, WifiP2pManager manager, WifiP2pManager.Channel channel,
                   Handler ui) {
        this.ctx = ctx;
        this.manager = manager;
        this.channel = channel;
        this.ui = ui;
    }

    // ==================== 入口 ====================

    /**
     * 建通道，然后阻塞到 Group 成型或超时。
     *
     * @param ctx       主界面 Context（别传 null，{@code Channel} 内部要用）
     * @param ui        主线程 Handler（回调派发用）
     * @param timeoutMs 总超时（发现 + 连接 + 兜底建组）
     */
    public static OtaP2p open(Context ctx, WifiP2pManager manager, Handler ui,
                              LineSink sink, long timeoutMs) {
        OtaP2p self = new OtaP2p(ctx, manager,
                manager.initialize(ctx, ui.getLooper(), null), ui);
        self.sink = sink;
        try {
            self.connectBlocking(timeoutMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return self;
    }

    /** 阻塞到 Group 成型。已成型会直接返回；可以重复调用做重试。 */
    public void connectBlocking(long timeoutMs) throws InterruptedException {
        // ★ 签名是 onPeersAvailable(WifiP2pDeviceList)，不是 List<WifiP2pDevice>
        final WifiP2pManager.PeerListListener peerSink = new WifiP2pManager.PeerListListener() {
            @Override
            public void onPeersAvailable(WifiP2pDeviceList list) {
                recordPeers(list);
            }
        };
        final WifiP2pManager.GroupInfoListener groupSink = new WifiP2pManager.GroupInfoListener() {
            @Override
            public void onGroupInfoAvailable(WifiP2pGroup group) {
                recordGroup(group);
            }
        };
        final WifiP2pManager.ConnectionInfoListener connSink =
                new WifiP2pManager.ConnectionInfoListener() {
                    @Override
                    public void onConnectionInfoAvailable(WifiP2pInfo info) {
                        recordConnInfo(info);
                    }
                };

        synchronized (mon) {
            formed = false;
        }

        // ★★ 最大的坑，以及它是怎么被绕过去的（2026-10-04 实测）：
        //    {@code WifiP2pManager#onFailure} 的 {@code reason=0} 在 AOSP 叫
        //    <b>P2P_UNSUPPORTED</b> —— 意思是「这一瞬间 P2P 压根没启用」。
        //    框架在 {@code P2pDisabledState} 里对 {@code DISCOVER_PEERS} /
        //    {@code CREATE_GROUP} 直接回这个码，连 supplicant 都不碰，
        //    所以表现是「8ms 内就失败」。它跟 {@code BUSY(=1)} 完全两回事。
        //    第一版把这个 0 译成了 BUSY，于是转头去追「mGroup 残留」——纯属追错。
        //    dumpsys 当时打脸：{@code curState=P2pDisabledState}、{@code mGroup null}，
        //    持久组 {@code DIRECT-y2-Android_H1dg} 只是躺在 {@code mGroups} 里。
        //    ⇒ 正解不是 deleteGroup（这台机器 WifiP2pManager 根本没暴露它，
        //      反射也是 NoSuchMethodException；Wi-Fi 重启也清不掉内存里的 mGroup），
        //      而是<b>等 P2P enabled + 重试</b>。
        //    另外：关/开 Wi-Fi 会把手机从车机 SoftAP 上踢下来，代价太大，
        //      默认不做，只有 {@link #setPowerCycle(boolean)} 显式打开才走。
        if (powerCycle) {
            resetWifi();
        }

        note("P2P：先问现成的 Group");
        manager.requestGroupInfo(channel, groupSink);
        manager.requestConnectionInfo(channel, connSink);
        waitUntil(4000, null);

        if (isFormed()) {
            note("P2P：✔ 复用已有 Group，owner=" + ownerAddress + " iAmOwner=" + groupOwner);
            return;
        }
        if (hasOwnGroup) {
            note("P2P：注意，本机有个残留组 " + ownGroupStatus
                    + "（supplicant 侧持久组，框架 mGroup 为 null，通常不挡事）");
        }

        // ① discoverPeers —— 必须重试，而且★不能因为「peer 列表回来了」就 break。
        //    discoverPeers 被 reason=0 拒掉时，紧接着的 requestPeers 会秒回一个
        //    空列表（find 根本没启动），看着像「成功拿到列表了」，其实一个 peer 都没有。
        //    第一版就是栽在这：第 1 轮就 break，于是「重试」从来没真的发生过。
        //    判据改成：peers 非空才算这一轮有收获。
        for (int round = 1; round <= P2P_RETRY; round++) {
            note("P2P：discoverPeers 第 " + round + " 轮"
                    + (round == 1 ? "（reason=0 只是 P2P 没就绪，空列表不算数，会继续重试）" : ""));
            synchronized (mon) {
                peers.clear();
                peersArrived = false;
            }
            manager.discoverPeers(channel, noop("discoverPeers"));
            manager.requestPeers(channel, peerSink);
            waitUntil(6000, new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return peersArrived;
                }
            });
            int n = snapshotPeers().size();
            note("P2P：本轮 peer 数 = " + n + (n == 0 ? "（这一轮白跑，继续）" : ""));
            if (n > 0) {
                if (waitUntil(3000, null)) {
                    note("P2P：等 discovery 的功夫 Group 就成型了");
                    return;
                }
                break;
            }
            if (waitUntil(1500, null)) {
                note("P2P：Group 已成型，不用再扫了");
                return;
            }
            sleepQuietly(2500);
        }
        for (WifiP2pDevice d : snapshotPeers()) {
            String n = nameOf(d);
            String kind = n.startsWith("DIRECT-") ? "P2P-GO"
                    : (n.contains("ZEEHO") || n.contains("CFMOTO") || n.contains("GEMINI")
                        ? "★SoftAP(不是P2P组)" : "?");
            note("P2P：peer " + d.deviceName + " / " + d.deviceAddress
                    + " status=" + d.status
                    + " [" + kind + "]"
                    + (isCar(d.deviceAddress) ? "   ← 车机" : ""));
        }

        // ② connect（车机当 GO，正常路径）—— 同样得重试
        boolean connected = false;
        for (int round = 1; round <= CONNECT_RETRY; round++) {
            if (waitUntil(1500, null)) {
                note("P2P：Group 已成型，跳过 connect");
                connected = true;
                break;
            }
            WifiP2pDevice target = pickPeer();
            Cfg cfg = carConfig(target);
            if (target != null) {
                note("P2P：connect -> " + target.deviceAddress + "（车机当 GO）");
            } else {
                // WifiP2pConfig 的 networkId 是 @hide，SDK 存根里方法/字段都被剔掉了，
                // 只能反射。反射不到就老实承认「没车机可连」，别拿个空 config 去调
                // connect —— 那只会抛 IllegalArgumentException: deviceAddress cannot be empty。
                note("P2P：peer 列表里没车机，试 resume 持久组 networkId=" + CAR_NET_ID
                        + (cfg.netIdSet ? "（已写入）" : "（写不进去，跳过 connect）")
                        + " —— 两边都存着 DIRECT-y2-Android_H1dg，psk 由 supplicant 复用");
                if (!cfg.netIdSet) {
                    sleepQuietly(2000);
                    continue;
                }
            }
            manager.connect(channel, cfg.raw, noop("connect"));
            if (waitUntil(12000, waitFormed)) {
                note("P2P：✔ 已连接 owner=" + ownerAddress + " iAmOwner=" + groupOwner);
                connected = true;
                break;
            }
            note("P2P：connect 第 " + round + " 轮没成型，2s 后重试");
            sleepQuietly(2000);
        }
        if (connected) {
            return;
        }

        // ③ 兜底：本机当 GO，车机（一直在 P2P 发现态）反过来 join
        note("P2P：改走 createGroup（本机当 GO 兜底，车机应在 " + GO_FALLBACK_IP + "）");
        synchronized (mon) {
            formed = false;
            ownerAddress = null;
        }
        for (int round = 1; round <= 2; round++) {
            manager.createGroup(channel, noop("createGroup"));
            if (waitUntil(20000, waitFormed)) {
                note("P2P：✔ createGroup 成型，本机是 GO");
                return;
            }
            note("P2P：createGroup 第 " + round + " 轮没成型，5s 后重试");
            sleepQuietly(5000);
        }

        synchronized (mon) {
            status = "P2P 失败：车机 peer 没发现，兜底建组也没成型。"
                    + " 检查 ① 车已上电 ② 仪表已进 OTA 模式（下键 + SET 长按 10s）"
                    + " ③ 手机 Wi-Fi 开着 ④ 车机 P2P 没被别的手机占用"
                    + " ⑤ 持久组 networkId=" + CAR_NET_ID + " 没被 proxy 服务改坏";
        }
        note(getStatus());
    }

    // ==================== 对外状态 ====================

    public boolean isFormed() {
        synchronized (mon) {
            return formed;
        }
    }

    /**
     * ADB 要连的主机：我们做 client 时是 GO 的 IP。
     *
     * <p>★ 2026-10-05：框架的 {@code groupOwnerAddress} 可能一直不回调（见 {@link #waitFormed}），
     * 此时不能返回 null —— 推包会直接没 host。改成按「本机 p2p0 地址的网段 + .1」兜底，
     * 因为组内地址必然是 {@code x.x.x.y/24}，GO（网关）就是同一个 {@code x.x.x.1}。
     * 实测车机当 GO 时：手机 p2p0={@code 192.168.0.50}、GO={@code 192.168.0.1}。</p>
     */
    public String getOwnerAddress() {
        synchronized (mon) {
            if (ownerAddress != null) {
                return ownerAddress;
            }
            if (!formed) {
                return null;
            }
            if (groupOwner) {
                return GO_FALLBACK_IP;
            }
            String[] rt = procRouteFor("p2p0");
            if (rt != null) {
                return guessGoIp(rt[0]);
            }
            return GO_FALLBACK_IP;
        }
    }

    public boolean isGroupOwner() {
        synchronized (mon) {
            return groupOwner;
        }
    }

    public String getStatus() {
        synchronized (mon) {
            return status;
        }
    }

    public String getFailureHint() {
        String s = getStatus();
        return s.startsWith("P2P 失败") ? s : null;
    }

    // ==================== 主线程回调（由 open() 传进来的 listener 转发） ====================

    private void recordPeers(WifiP2pDeviceList list) {
        synchronized (mon) {
            peers.clear();
            try {
                // ★ WifiP2pDeviceList 的 SDK 存根里只有 get(int)->String，
                //   真正的 List<WifiP2pDevice> 藏在 getDeviceList() 后面（实现里是 Iterable），
                //   所以走反射取，不依赖具体存根长什么样。
                java.lang.reflect.Method m = list.getClass().getMethod("getDeviceList");
                Object got = m.invoke(list);
                if (got instanceof Iterable) {
                    for (Object d : (Iterable<?>) got) {
                        if (d instanceof WifiP2pDevice) {
                            peers.add((WifiP2pDevice) d);
                        }
                    }
                }
            } catch (Throwable t) {
                HookLog.log(TAG + " getDeviceList 反射失败: " + t);
            }
            peersArrived = true;
            mon.notifyAll();
        }
    }

    private void recordConnInfo(WifiP2pInfo info) {
        if (info == null) return;
        // groupOwnerAddress 是 InetAddress，不是 String
        String ip = info.groupOwnerAddress == null
                ? null : info.groupOwnerAddress.getHostAddress();
        note("P2P：connInfo formed=" + info.groupFormed + " isOwner=" + info.isGroupOwner
                + " ownerIp=" + ip);
        synchronized (mon) {
            // ★ 2026-10-05：车机的 SoftAP 组（ZEEHO-xxxx）也会让 connInfo 报 formed=true，
            //  但那个网段上没有 adbd（实测只有 53/DNS）。所以只在「组名是 DIRECT- 开头的
            //  标准 P2P 组」或「组名还没读到（首次）」时才认成型，其余一律不认。
            String net = lastGroupNet;
            boolean p2pGroup = net == null || net.toUpperCase().startsWith("DIRECT-");
            boolean accept = info.groupFormed && p2pGroup;
            if (!info.groupFormed && net != null && !p2pGroup) {
                note("P2P：connInfo 报的是 SoftAP 组 " + net + "，忽略（不是 P2P 组）");
            }
            // 别让迟到的 formed=false 覆盖已经成型的状态（这个回调不是有序的）
            if (accept || !formed) {
                formed = accept;
            }
            groupOwner = info.isGroupOwner;
            if (accept && !info.isGroupOwner) {
                ownerAddress = ip;
            }
            status = formed
                    ? ("已连接 owner=" + ownerAddress + " iAmOwner=" + groupOwner)
                    : (p2pGroup ? "group not formed" : "当前是车机 SoftAP 组，非 P2P 组");
            mon.notifyAll();
        }
    }

    private void recordGroup(WifiP2pGroup group) {
        if (group == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        Collection<WifiP2pDevice> clients = group.getClientList();   // 返回 Collection，不是 List
        if (clients != null) {
            for (WifiP2pDevice d : clients) {
                sb.append(" [").append(d.deviceName).append(' ').append(d.deviceAddress).append(']');
                synchronized (mon) {
                    clientIps.add(d.deviceAddress + " -> " + GO_FALLBACK_IP);
                }
            }
        }
        note("P2P：Group owner=" + group.getOwner().deviceAddress
                + " isGO=" + group.isGroupOwner()
                + " iface=" + group.getInterface()
                + " net=" + group.getNetworkName()
                + " netId=" + group.getNetworkId()
                + " clients=" + sb);
        if (!hasOwnGroup) {
            synchronized (mon) {
                hasOwnGroup = true;
                ownGroupStatus = "netId=" + group.getNetworkId() + " iface=" + group.getInterface();
            }
        }
        synchronized (mon) {
            lastGroupNet = group.getNetworkName();
        }
        synchronized (mon) {
            if (!formed && group.isGroupOwner() && sb.length() == 0) {
                return;   // 自己刚当 GO 的半状态，等车机 join 后 connInfo 会补齐
            }
            // ★ 2026-10-05：车机的 **SoftAP** 组（网络名 ZEEHO-xxxx）不是 P2P 组，
            //  连进去 ping 通但 adbd 不在其网段（只有 53/DNS）。复用它等于白跑，
            //  所以这里拒绝把它算作成型，让流程继续往下走去连真正的 P2P GO。
            String net = group.getNetworkName() == null ? "" : group.getNetworkName();
            if (!formed && net.toUpperCase().startsWith("DIRECT-")) {
                groupOwner = group.isGroupOwner();
                ownerAddress = GO_FALLBACK_IP;
                formed = true;
                status = "复用已有 P2P Group（" + net + "），owner=" + ownerAddress;
            } else if (!formed) {
                note("P2P：当前组 " + net + " 是车机 SoftAP，不是 P2P 组 —— 不算成型，继续找 P2P GO");
            }
            mon.notifyAll();
        }
    }

    // ==================== 内部 ====================

    private boolean isCar(String mac) {
        return mac != null
                && (CAR_MAC.equalsIgnoreCase(mac) || CAR_MAC_ALT.equalsIgnoreCase(mac));
    }

    /** 设备名大写，null 安全。 */
    private static String nameOf(WifiP2pDevice d) {
        String n = d == null || d.deviceName == null ? "" : d.deviceName;
        return n.toUpperCase();
    }

    private WifiP2pDevice pickPeer() {
        synchronized (mon) {
            // ★ 2026-10-05 修正选择顺序。
            // 实测同一时刻能扫到两个 peer：
            //   ① DIRECT-9c4hLmm81 / 3c:cb:01:17:f1  ← 标准 P2P GO 组名（DIRECT- 前缀）
            //   ② ZEEHO-204dfd  / 6c:12:4a:18:09:ff  ← 车机的 **SoftAP**，不是 P2P 组
            // 老顺序按「名字含 ZEEHO」挑中了 ②，结果连进 SoftAP：ping 通、但只有 53/DNS，
            // adbd 根本不在那个网段（与 docs/35 §3.1 记的现象一模一样）。
            // ⇒ DIRECT- 前缀的才是车机的 P2P GO，SoftAP 放到最后一档当兜底。
            for (WifiP2pDevice d : peers) {
                if (CAR_MAC.equalsIgnoreCase(d.deviceAddress)) return d;
            }
            for (WifiP2pDevice d : peers) {
                if (isCar(d.deviceAddress)) return d;
            }
            // 标准 P2P GO 组名固定是 DIRECT-xxxx-xxxx_xx
            for (WifiP2pDevice d : peers) {
                if (nameOf(d).startsWith("DIRECT-")) return d;
            }
            for (WifiP2pDevice d : peers) {
                String n = nameOf(d);
                if (n.contains("ZEEHO") || n.contains("CFMOTO") || n.contains("GEMINI")) return d;
            }
            return null;
        }
    }

    /**
     * {@link WifiP2pConfig} 再套一层：SDK 里 {@code networkId} 是 @hide 的，
     * 我们读不到它的值，只能自己记「到底写进去没有」。
     */
    private static final class Cfg {
        final WifiP2pConfig raw;
        boolean netIdSet;
        Cfg(WifiP2pConfig raw) {
            this.raw = raw;
        }
    }

    /**
     * 组一个「连车机」的 config。
     *
     * <p>正常情况下车机是 GO，所以 {@code groupOwnerIntent = 0}（0 表示「尽量不当 GO」）。
     * 给 {@code null} 表示 peer 列表里没车机 —— 那就按持久组 resume：
     * {@code WifiP2pConfig.networkId >= 0} 时框架走 persistent 分支，
     * supplicant 直接把 {@code DIRECT-y2-Android_H1dg} 拉起来（两边 psk 都是
     * {@code VQD9Hb1J}，不用再过 PBC 握手）。这正是 {@code com.cfmoto} 那 8s 一轮
     * 的 {@code REINVOKE} 在干的事 —— 我们要做的只是把它做成功。</p>
     */
    private Cfg carConfig(WifiP2pDevice target) {
        WifiP2pConfig cfg = new WifiP2pConfig();
        Cfg out = new Cfg(cfg);
        if (target != null) {
            cfg.deviceAddress = target.deviceAddress;
            cfg.groupOwnerIntent = 0;
        } else {
            // networkId 字段/setNetworkId() 都是 @hide，SDK 存根里方法连影子都没有，
            // 字段也未必留着 —— 两个都试，能写进去才算数。
            try {
                java.lang.reflect.Method set = WifiP2pConfig.class
                        .getMethod("setNetworkId", int.class);
                set.invoke(cfg, CAR_NET_ID);
                out.netIdSet = true;
            } catch (Throwable t1) {
                try {
                    java.lang.reflect.Field f = WifiP2pConfig.class.getField("networkId");
                    f.setInt(cfg, CAR_NET_ID);
                    out.netIdSet = true;
                } catch (Throwable t2) {
                    note("P2P：networkId 反射全失败（接着走 PBC）: " + t2);
                }
            }
            if (out.netIdSet) {
                note("P2P：networkId=" + CAR_NET_ID + " 写入成功");
            }
        }
        // WpsInfo 是 WifiP2pConfig 的成员类型，不是它的嵌套类；
        // 只给 setup=PBC：passphrase 字段/方法在这版 SDK 的 WpsInfo 上根本不存在，
        // 而且两边都存着持久组，配对 psk 由 supplicant 自己取。
        WpsInfo wps = new WpsInfo();
        wps.setup = WpsInfo.PBC;
        cfg.wps = wps;
        return out;
    }

    private List<WifiP2pDevice> snapshotPeers() {
        synchronized (mon) {
            return new ArrayList<>(peers);
        }
    }

    private void note(final String s) {
        HookLog.log(TAG + " " + s);
        synchronized (mon) {
            status = s;
            mon.notifyAll();
        }
        final LineSink s2 = sink;
        if (s2 != null) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    s2.onLine(s);
                }
            });
        }
    }

    private WifiP2pManager.ActionListener noop(final String what) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                synchronized (mon) {
                    p2pUnsupportedHits = 0;
                }
                note("P2P：" + what + " onSuccess");
            }

            @Override
            public void onFailure(int reason) {
                note("P2P：" + what + " onFailure reason=" + reason + " = " + reasonName(reason));
                int hits;
                synchronized (mon) {
                    p2pUnsupportedHits = (reason == 0) ? p2pUnsupportedHits + 1 : 0;
                    hits = p2pUnsupportedHits;
                }
                // ★ 2026-10-04 实测：reason=0 连续出现、而且一直拿不到 peer，
                //   那多半已经不是「P2P 没启用」，而是 <b>wpa_supplicant 压根不应答</b>
                //   —— 同一台机器上 `wpa_cli -i wlan0 SCAN_RESULTS` 也会 hang 到超时，
                //   `wlan0` 停在 NO-CARRIER。框架拿不到任何回包，于是统一退化成 P2P_UNSUPPORTED。
                //   这时候重试是白重试，得先把 Wi-Fi 弄活。
                if (hits >= 3) {
                    // ★ 2026-10-05：这段提示的**头号原因改成了权限**。
                    //  Android 13+ 缺 NEARBY_WIFI_DEVICES 时，framework 层直接回 P2P_UNSUPPORTED，
                    //  压根不碰 supplicant —— 症状与「supplicant 卡死」一模一样。
                    //  实测（本项目）：权限补上后同一轮就 onSuccess + 2 个 peer；
                    //  而 adb reboot / svc wifi 重置 / kill supplicant 全部无效。
                    //  另：原判据「wpa_cli SCAN_RESULTS 是否超时」是错的，见 docs/35 §4 修正。
                    note("P2P：连续 3 次 reason=0 且始终没有 peer。请按顺序排查："
                            + "① ★最可能：缺 NEARBY_WIFI_DEVICES 权限（Android 13+ 头号原因）—— "
                            + "查 `dumpsys package <pkg> | grep NEARBY`；没有就加 Manifest 声明"
                            + "（带 usesPermissionFlags=neverForLocation）重装，再 "
                            + "`adb shell pm grant <pkg> android.permission.NEARBY_WIFI_DEVICES`"
                            + "② 手机 Wi-Fi 开着、没被热点/其他 P2P 会话占着"
                            + "③ 车已上电且仪表在 OTA 模式"
                            + "④ 确认手机支持 P2P：`dumpsys wifip2p` 的 curState 不是 DisabledState "
                            + "（若始终 DisabledState 且权限已给，才考虑重启手机）");
                }
            }
        };
    }

    private interface Callable<T> {
        T call();
    }

    /** 等到条件成立；cond 为 null 时只看 formed。 */
    private boolean waitUntil(long timeoutMs, Callable<Boolean> cond)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (mon) {
            while (System.currentTimeMillis() < deadline) {
                boolean ok = cond == null ? formed : cond.call();
                if (ok) return true;
                long left = deadline - System.currentTimeMillis();
                mon.wait(left > 0 ? Math.min(500, left) : 0);
            }
        }
        return cond == null ? formed : cond.call();
    }

    /**
     * 「组已成型」的判据。
     *
     * <p>★ 2026-10-05 两次修正（实测踩坑）：
     * <ol>
     *   <li>只看框架的 {@code onConnectionInfoAvailable} 会漏判：它只在建链最初来过一句
     *       {@code formed=false} 就再也不来（AOSP 里它不是持续通知），
     *       于是 {@code formed} 永远 false，4 轮 connect 全被判「没成型」，
     *       白白把已经成型的组拆了重建，还把 {@code createGroup} 一起带崩。</li>
     *   <li>★ 别用 {@code NetworkInterface.getNetworkInterfaces()} 找 {@code p2p0}：
     *       本模块<b>刻意没声明 INTERNET 权限</b>，Android 对无网络权限的进程
     *       {@code getNetworkInterfaces()} 只会返回 loopback ⇒ 永远读不到 {@code p2p0}。
     *       实测就是因此判据恒 false（APK 内确认有代码，运行时就是不生效）。</li>
     * </ol>
     * 正确做法：读 {@code /proc/net/route} —— 它不需要任何权限，
     * 组一成型内核就会给 {@code p2p0} 加一条路由，机器上就能看到这个 Iface 行。
     * 顺带把 Destination 解出来就是网段（大小端十六进制），{@code getOwnerAddress()} 也能用。</p>
     */
    private final Callable<Boolean> waitFormed = new Callable<Boolean>() {
        @Override
        public Boolean call() {
            if (formed) {
                return true;
            }
            // p2p0 出现在 /proc/net/route 里 == 组已成型（内核已配好直连路由）
            // ★ 但车机的 SoftAP 组也会让 p2p0 出现，且它没有 adbd —— 必须排除掉。
            //   判据：组名（p2p_supplicant.conf 里成型的那条 network 的 ssid）以 DIRECT- 开头。
            //   lastGroupNet 由 recordGroup 维护。
            String[] rt = procRouteFor("p2p0");
            String net;
            synchronized (mon) {
                net = lastGroupNet;
            }
            if (rt != null && net != null && net.toUpperCase().startsWith("DIRECT-")) {
                synchronized (mon) {
                    if (!formed) {
                        formed = true;   // 这一刻起才算，别让迟到的 formed=false 再拉低
                        status = "组已成型（按 /proc/net/route 里有 p2p0 判定）"
                                + " iAmOwner=" + groupOwner
                                + " 网段=" + hexToIp(rt[0]) + "/"
                                + maskToPrefix(rt[1])
                                + " GO候选=" + guessGoIp(rt[0]);
                        mon.notifyAll();
                    }
                }
                return true;
            }
            return false;
        }
    };

    // ==================== 读 /proc/net/route ====================

    /**
     * 从 {@code /proc/net/route} 取某个接口的路由项。
     *
     * <p>格式（制表符分隔）：{@code Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT}。
     * 返回 {@code {destinationHex, maskHex}}；该接口没有行时返回 null。</p>
     *
     * <p>★ 为什么不走 {@code NetworkInterface}：本模块没有 INTERNET 权限，
     * 系统只把 loopback 交给它，见 {@link #waitFormed} 的说明。</p>
     */
    private static String[] procRouteFor(String ifname) {
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream("/proc/net/route"), "UTF-8"));
            String line = br.readLine();               // 表头
            while ((line = br.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 8 && f[0].equals(ifname)) {
                    return new String[]{f[1], f[7]};
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** /proc/net/route 里的地址是**小端**十六进制（0100A8C0 = 192.168.0.1）。 */
    private static String hexToIp(String hex) {
        try {
            long v = Long.parseLong(hex, 16);
            return String.format("%d.%d.%d.%d",
                    v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF);
        } catch (Throwable t) {
            return hex;
        }
    }

    private static int maskToPrefix(String hex) {
        try {
            long m = Long.parseLong(hex, 16) & 0xFFFFFFFFL;
            int n = 0;
            for (int i = 31; i >= 0; i--) {
                if (((m >> i) & 1) == 0) {
                    break;
                }
                n++;
            }
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** P2P 是直连网段（Mask 全 1），网关通常就是 x.x.x.1。 */
    private static String guessGoIp(String destHex) {
        String d = hexToIp(destHex);
        int dot = d.lastIndexOf('.');
        return dot > 0 ? d.substring(0, dot + 1) + "1" : d;
    }

    private final Callable<Boolean> waitPeersArrived = new Callable<Boolean>() {
        @Override
        public Boolean call() {
            return peersArrived;
        }
    };

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 建连前是否把 Wi-Fi 关一圈再开。默认否（会断掉手机到车机 SoftAP 的 STA 连接）。 */
    public void setPowerCycle(boolean on) {
        powerCycle = on;
    }

    /**
     * 在 +49 段里找出真正开着 5555 的那台。
     *
     * <p>本机当 GO 时，车机（client）拿到的是 DHCP 下发的小号 IP，
     * 而 {@link WifiP2pGroup#getClientList()} 只给 MAC 不给 IP，
     * 所以直接扫 {@code 192.168.49.1..4} 的 5555 —— 比猜 GO 地址靠谱。</p>
     *
     * @return 第一个接受 TCP 连接的地址；都没有返回 null
     */
    public String resolveAdbHost(int timeoutMs) {
        // ★ 2026-10-05：GO 网段不一定是 192.168.49.x。
        // 实测「车机当 GO」时组名 ZEEHO-204dfd、GO 地址 192.168.0.1（ping 通、只有 53/DNS），
        // 此时 adbd 根本不在 +49 段。所以先按本机 p2p0 的实际网段推出 GO/对端候选，
        // 再退回历史候选（第三方工具与官方 ZeeCare 用的确实是 192.168.49.1:5555）。
        java.util.LinkedHashSet<String> cands = new java.util.LinkedHashSet<>();
        for (String ip : localGoCandidates()) {
            cands.add(ip);
        }
        cands.add("192.168.49.1");
        cands.add("192.168.49.2");
        cands.add("192.168.49.3");
        cands.add("192.168.49.4");
        for (String ip : cands) {
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(ip, 5555), timeoutMs);
                s.close();
                note("P2P：✔ " + ip + ":5555 开着 adbd");
                return ip;
            } catch (Throwable t) {
                // ★ 必须把原因打出来：没 INTERNET 权限时这里抛的是 SecurityException，
                // 而 5555 没开时抛的是 ConnectException/timeout —— 两者处置完全不同，
                // 以前全被吞掉，只留下「没找到」这种无信息量的结论。
                note("P2P：  " + ip + ":5555 连不上 → " + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage()));
                if (s != null) {
                    try {
                        s.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        note("P2P：候选 " + cands + " 里都没有 5555 开着"
                + "（车机可能还没进 OTA 模式 —— adbd 只在 OTA 模式里起）");
        return null;
    }

    /**
     * 读某个本机接口的 IPv4 点分十进制地址；取不到返回 null。
     *
     * <p>★ 必须先有 {@code android.permission.INTERNET}，否则 Android 只把 loopback
     * 交给本进程，这里永远读不到 {@code p2p0}（2026-10-05 实测，见 AndroidManifest 注释）。
     * 读不到时退回 {@code /proc/net/route} 推网段。</p>
     */
    private static String localIp4(String ifname) {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.getName().equals(ifname) || !ni.isUp()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> add = ni.getInetAddresses();
                while (add.hasMoreElements()) {
                    java.net.InetAddress a = add.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 从本机 {@code p2p0} 的地址反推「对端/GO」候选。
     *
     * <p>P2P 里手机拿到的是组内地址（如 192.168.0.50/24），网关（GO）通常就是 x.x.x.1。
     * 这里把整段 .1~.20 都列上，代价只是几十次 connect 失败，可接受。
     * 优先用 {@code NetworkInterface}（有了 INTERNET 权限才准），退回 {@code /proc/net/route}。</p>
     */
    private java.util.List<String> localGoCandidates() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String pre = null;
        String me = localIp4("p2p0");
        if (me != null) {
            int dot = me.lastIndexOf('.');
            if (dot > 0) {
                pre = me.substring(0, dot + 1);
            }
        }
        if (pre == null) {
            String[] rt = procRouteFor("p2p0");
            if (rt != null) {
                pre = guessGoIp(rt[0]);
            }
        }
        if (pre == null || !pre.endsWith(".")) {
            return out;
        }
        for (int i = 1; i <= 20; i++) {
            out.add(pre + i);
        }
        return out;
    }

    /**
     * 反射调用 {@code WifiP2pManager#deleteGroup}。
     *
     * <p>它在运行时存在，但在这版 SDK 里被标记为 @hide（javap 上看不到公开签名），
     * 直接调用编不过。不清掉这个残留 GO 组，{@code discoverPeers} 和
     * {@code createGroup} 会一起返回 {@code BUSY(reason=0)} —— 这是本次最大的坑。</p>
     */
    /** 关一次再开 Wi-Fi，把 P2P 状态机与残留 mGroup 清干净。 */
    private void resetWifi() {
        try {
            android.net.wifi.WifiManager w =
                    (android.net.wifi.WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (w == null) {
                note("P2P：拿不到 WifiManager，跳过重置");
                return;
            }
            note("P2P：关 Wi-Fi …");
            if (w.isWifiEnabled()) {
                w.setWifiEnabled(false);
                Thread.sleep(4000);
            }
            note("P2P：开 Wi-Fi …");
            w.setWifiEnabled(true);
            Thread.sleep(8000);
            note("P2P：Wi-Fi 已重启（mGroup 应已清空）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            note("P2P：Wi-Fi 重启失败: " + t);
        }
    }

    private void deleteGroupHidden() {
        try {
            java.lang.reflect.Method m = WifiP2pManager.class.getMethod(
                    "deleteGroup", WifiP2pManager.Channel.class,
                    WifiP2pManager.ActionListener.class);
            m.invoke(manager, channel, noop("deleteGroup"));
        } catch (Throwable t) {
            note("P2P：deleteGroup 反射失败（接着试）: " + t);
        }
    }

    /**
     * reason 码翻译 —— 这张表是踩过大坑才定下来的，别再照 AOSP 前的记忆改。
     *
     * <pre>
     * 0 P2P_UNSUPPORTED      ← ★ 最常见：当下 P2P 没启用（dumpsys 里 curState=P2pDisabledState），
     *                           框架在 P2pDisabledState 里连 supplicant 都不碰直接回这个码，
     *                           所以「8ms 内失败」是它的特征。等几秒重试就好，不是 BUSY！
     * 1 BUSY                 ← 真·忙（前面还有操作在跑）
     * 2 ERROR
     * 3 ALREADY_ACTIVE
     * 4 NO_GROUP_INFO_AVAILABLE
     * 5 PEER_NOT_FOUND
     * </pre>
     */
    private static String reasonName(int r) {
        switch (r) {
            case 0: return "P2P 还没启用（P2P_UNSUPPORTED）→ 等几秒重试是对的";
            case 1: return "BUSY（上一个操作还在跑）";
            case 2: return "ERROR";
            case 3: return "ALREADY_ACTIVE";
            case 4: return "NO_GROUP_INFO_AVAILABLE";
            case 5: return "PEER_NOT_FOUND";
            default: return "unknown(" + r + ")";
        }
    }

    /** 展示已备好的 client IP（本机当 GO 时用）。 */
    public List<String> getClientIps() {
        synchronized (mon) {
            return Collections.unmodifiableList(new ArrayList<>(clientIps));
        }
    }
}
