package com.github.zeehospeedhunter.ota;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动连车机 Wi-Fi，去掉「手动连 AP」这一步。
 *
 * <h3>为什么需要它</h3>
 * <p>{@code socket_l} 推送要求手机在车机网段（{@code 192.168.0.x}）。车机 AP 是
 * {@code ZEEHO-xxxx}，车机侧固定 {@code 192.168.0.1:10950}。
 * 之前是手动在系统设置里连，属于「不应该这么麻烦」的那一步 —— 这里把它自动化。</p>
 *
 * <h3>两条实现路径</h3>
 * <ul>
 *   <li><b>API 29+（实测 Android 16 走这条）</b>：{@link WifiNetworkSpecifier}。
 *       系统会弹一次「连接到 ZEEHO-xxxx？」确认框，点一下即可；已连着则立刻成功。
 *       注意：这会请求 {@code NEARBY_WIFI_DEVICES}（Android 13+ 运行时权限）。</li>
 *   <li><b>API 24–28</b>：{@link WifiManager#addNetwork} + {@link WifiManager#enableNetwork}，静默加网。</li>
 * </ul>
 *
 * <h3>重要边界（来自固件逆向）</h3>
 * <p>连上 AP 只是网络层。10950 是否监听取决于车机侧 OTA 模块的 link 服务
 * （{@code OTAModule::initial} 在开机/仪表上电时拉起，被「断网事件」拆除，
 * 重连 AP 不会重建）。所以本类只负责「把手机放上 192.168.0.x」，
 * 至于 10950 听不听，由 {@link OtaSocketPusher#probeLink()} / 探测车辆 来判断。</p>
 */
public final class CarWifi {

    /** 车机 AP 的 SSID 前缀。 */
    public static final String SSID_PREFIX = "ZEEHO-";

    /** 默认车机 AP（doc/43 实测：测试车为 ZEEHO-204dfd / 071284a88d）。 */
    public static final String DEFAULT_SSID = "ZEEHO-204dfd";

    public interface ConnectResult {
        void onResult(boolean ok, String msg);
    }

    private static volatile ConnectivityManager.NetworkCallback sPending;

    private CarWifi() {
    }

    /**
     * 列出附近匹配 {@link #SSID_PREFIX} 的 SSID —— 用来确认车机 AP 在范围内、
     * 并自动挑一个，省得用户手填。
     */
    public static List<String> scanCarAp(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) {
                return out;
            }
            try {
                wm.startScan();
            } catch (Exception ignored) {
            }
            List<ScanResult> results = wm.getScanResults();
            if (results != null) {
                for (ScanResult r : results) {
                    if (r.SSID != null && r.SSID.startsWith(SSID_PREFIX)
                            && !out.contains(r.SSID)) {
                        out.add(r.SSID);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 连上车机 AP（指定 SSID，密码可空=开放网络）。
     *
     * @param cb 结果回调（主线程）。成功不代表 10950 一定在监听，只代表手机已连上该 AP。
     */
    public static void connect(final Context ctx, final String ssid, final String password,
                               final ConnectResult cb) {
        if (ssid == null || ssid.isEmpty()) {
            cb.onResult(false, "没填车机 AP 的 SSID（去「车机 Wi-Fi 设置」填）");
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final ConnectivityManager cm = (ConnectivityManager)
                    ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                cb.onResult(false, "ConnectivityManager 不可用");
                return;
            }
            // 清掉上一次没释放的请求，避免叠加
            if (sPending != null) {
                try {
                    cm.unregisterNetworkCallback(sPending);
                } catch (Exception ignored) {
                }
                sPending = null;
            }

            WifiNetworkSpecifier.Builder b = new WifiNetworkSpecifier.Builder().setSsid(ssid);
            if (password != null && !password.isEmpty()) {
                b.setWpa2Passphrase(password);
            }
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(b.build())
                    .build();

            final ConnectivityManager.NetworkCallback nc =
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            // 不在这里 unregister：specifier 网络在请求释放后可能掉线，
                            // 保持注册才能把车机 AP 留住直到推送结束。
                            sPending = this;
                            main.post(() -> cb.onResult(true, "已连上 " + ssid
                                    + "（等几秒拿 IP，再看 10950 通不通）"));
                        }

                        @Override
                        public void onUnavailable() {
                            try {
                                cm.unregisterNetworkCallback(this);
                            } catch (Exception ignored) {
                            }
                            if (sPending == this) {
                                sPending = null;
                            }
                            main.post(() -> cb.onResult(false,
                                    "连不上 " + ssid + "：系统拒绝或超时。"
                                            + "确认 SSID/密码正确，或去系统 Wi-Fi 手动连"));
                        }
                    };
            try {
                cm.requestNetwork(req, nc, 20000); // 20s 超时
            } catch (Exception e) {
                cb.onResult(false, "requestNetwork 抛异常：" + e);
            }
        } else {
            // 旧版本：静默加网（需要 CHANGE_WIFI_STATE，清单已声明）
            final WifiManager wm = (WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                cb.onResult(false, "WifiManager 不可用");
                return;
            }
            WifiConfiguration cfg = new WifiConfiguration();
            cfg.SSID = "\"" + ssid + "\"";
            if (password != null && !password.isEmpty()) {
                cfg.preSharedKey = "\"" + password + "\"";
            } else {
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            }
            int id = wm.addNetwork(cfg);
            if (id == -1) {
                // 可能已存在同名网络，尝试直接 enable
                for (WifiConfiguration c : wm.getConfiguredNetworks()) {
                    if (ssid.equals(c.SSID)) {
                        id = c.networkId;
                        break;
                    }
                }
            }
            if (id == -1) {
                cb.onResult(false, "添加网络失败（可能已存在，去系统 Wi-Fi 里连）");
                return;
            }
            boolean ok = wm.enableNetwork(id, true);
            if (ok) {
                cb.onResult(true, "已发起连接 " + ssid + "（稍等几秒拿 IP）");
            } else {
                cb.onResult(false, "enableNetwork 失败");
            }
        }
    }

    /** 释放仍挂着的 specifier 请求（OTA 完成后调用，避免一直占着车机 AP）。 */
    public static void release(Context ctx) {
        if (sPending == null) {
            return;
        }
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(sPending);
            } catch (Exception ignored) {
            }
        }
        sPending = null;
    }
}
