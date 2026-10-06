package com.github.zeehospeedhunter.ota;

import com.github.zeehospeedhunter.core.HookLog;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.content.Context;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用 Android {@link NsdManager} 发现车机的 EasyConnect mDNS 服务（**纯只读**）。
 *
 * <p><b>来源</b>：2026-10-05 从 {@code firmware/spsdk-root/lib/libECSDK.so} 的
 * {@code .rodata} 读到 {@code openWifiService} 附近的连续字符串，得到完整服务声明：</p>
 * <pre>
 *   实例名 : EasyConn
 *   服务类型: _EasyConn._tcp.local.
 *   TXT 记录: flavor=  huname=  port=  channel=  packagename=  huid=  ec_name=
 *   日志串  : '[WifiManager]openWifiService: publish mdns success'
 *             '[WifiService]createWifiSocket:bind socket failed, retry to max'
 * </pre>
 *
 * <p>所以车机在同一 AP 内会广播一个 mDNS 服务，{@code port=} 就是 P2C 监听端口
 * （<b>动态端口</b>，这也是我们静态找不到端口常量、32 端口全扫只中 53 的原因）。</p>
 *
 * <p><b>红线</b>：只做服务发现与读取 TXT，<b>不连接、不发送任何字节</b>。
 * 端口号拿到后，交由 {@link OtaSocketProbe} 做「只握手不发送」的可达性验证。</p>
 */
public final class OtaMdnsProbe {

    private static final String TAG = HookLog.OTA;

    /** 固件里硬编码的服务类型。 */
    public static final String SERVICE_TYPE = "_EasyConn._tcp.local.";

    /** 固件里硬编码的实例名。 */
    public static final String SERVICE_NAME = "EasyConn";

    /** 关注的 TXT 键。 */
    private static final String[] TXT_KEYS = {
            "port", "huid", "ec_name", "flavor", "channel", "packagename", "huname",
    };

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private OtaMdnsProbe() {
    }

    /** 在 {@code Context} 可用时调用（需在 Application/Activity attach 之后）。 */
    public static void install(Context context) {
        Context ctx = context != null ? context : appContext();
        if (ctx == null) {
            HookLog.log(TAG + " mdns | no context, skip");
            return;
        }
        try {
            NsdManager mgr = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (mgr == null) {
                HookLog.log(TAG + " mdns | NsdManager unavailable");
                return;
            }
            logWifi(ctx);
            // 必须持有组播锁，否则收不到 mDNS 响应（Android 默认会过滤）。
            acquireMulticastLock(ctx);
            HookLog.log(TAG + " mdns | discovering " + SERVICE_TYPE);
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD,
                    new NsdManager.DiscoveryListener() {
                        @Override
                        public void onStartDiscoveryFailed(String type, int err) {
                            HookLog.log(TAG + " mdns | start failed type=" + type + " err=" + err);
                        }

                        @Override
                        public void onStopDiscoveryFailed(String type, int err) {
                            HookLog.log(TAG + " mdns | stop failed err=" + err);
                        }

                        @Override
                        public void onDiscoveryStarted(String type) {
                            HookLog.log(TAG + " mdns | started " + type);
                        }

                        @Override
                        public void onDiscoveryStopped(String type) {
                            HookLog.log(TAG + " mdns | stopped " + type);
                        }

                        @Override
                        public void onServiceFound(NsdServiceInfo info) {
                            HookLog.log(TAG + " ★ mdns found: name=" + info.getServiceName()
                                    + " type=" + info.getServiceType()
                                    + " host=" + hostStr(info.getHost())
                                    + " port=" + info.getPort());
                            resolve(info);
                        }

                        @Override
                        public void onServiceLost(NsdServiceInfo info) {
                            HookLog.log(TAG + " mdns lost: " + info.getServiceName());
                        }
                    });
        } catch (Throwable t) {
            HookLog.log(TAG + " mdns | discover error (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void resolve(final NsdServiceInfo info) {
        android.content.Context ctx = appContext();
        NsdManager mgr = null;
        if (ctx != null) {
            try {
                mgr = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            } catch (Throwable ignored) {
            }
        }
        if (mgr == null) {
            dumpTxt(info);
            return;
        }
        final int id = SEQ.getAndIncrement();
        mgr.resolveService(info, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo i, int err) {
                HookLog.log(TAG + " mdns | resolve#" + id + " failed err=" + err
                        + " (port hint=" + i.getPort() + ")");
                dumpTxt(i);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo i) {
                HookLog.log(TAG + " ★★ mdns resolved: host=" + hostStr(i.getHost())
                        + " port=" + i.getPort() + " name=" + i.getServiceName());
                dumpTxt(i);
            }
        });
    }

    private static void dumpTxt(NsdServiceInfo info) {
        try {
            @SuppressWarnings("deprecation")
            java.util.Map<String, byte[]> attrs = info.getAttributes();
            if (attrs == null || attrs.isEmpty()) {
                HookLog.log(TAG + " mdns | no TXT records");
                return;
            }
            for (String key : TXT_KEYS) {
                byte[] v = attrs.get(key);
                if (v == null) {
                    continue;
                }
                HookLog.log(TAG + " ★ TXT " + key + " = " + new String(v));
            }
        } catch (Throwable t) {
            HookLog.log(TAG + " mdns | dumpTxt error (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static String hostStr(java.net.InetAddress a) {
        if (a == null) {
            return "(null)";
        }
        String h = a.getHostAddress();
        // Android 上 getHostAddress 常回 IPv6 字面量，取其中的 IPv4
        int i = h.indexOf('%');
        if (i > 0) {
            h = h.substring(0, i);
        }
        return h;
    }

    /**
     * 持有组播锁。
     *
     * <p>Android 默认会丢弃非系统应用收发的组播包，{@code NsdManager} 因此收不到响应。
     * 锁必须<b>整个发现期间保持</b>，所以存在静态字段里不释放。</p>
     */
    private static WifiManager.MulticastLock multicastLock;

    private static void acquireMulticastLock(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return;
            }
            multicastLock = wm.createMulticastLock("zeeho-mdns");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
            HookLog.log(TAG + " mdns | multicast lock acquired=" + multicastLock.isHeld());
        } catch (Throwable t) {
            HookLog.log(TAG + " mdns | multicast lock failed ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * 拿目标 App 的 Application Context。
     *
     * <p>{@code MainHook} 拿不到 {@code Context}（只有 {@code XC_LoadPackage.LoadPackageParam}），
     * 而 {@code NsdManager} 必须有 Context。这里反射 {@code ActivityThread} 的静态方法 ——
     * 与 LSPosed 寄生管理器环境下兼容，且只读取不修改。</p>
     */
    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            for (String name : new String[]{"currentApplication", "currentActivityThread"}) {
                try {
                    Object o = at.getDeclaredMethod(name).invoke(null);
                    if (o instanceof android.app.Application) {
                        return ((android.app.Application) o).getApplicationContext();
                    }
                    if (o != null) {
                        java.lang.reflect.Method m = o.getClass()
                                .getDeclaredMethod("getSystemContext");
                        m.setAccessible(true);
                        Object c = m.invoke(o);
                        if (c instanceof Context) {
                            return (Context) c;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 拿当前 Wi-Fi 状态（只读，用于确认确实在车机 AP 上）。 */
    public static void logWifi(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return;
            }
            @SuppressWarnings("deprecation")
            String ssid = wm.getConnectionInfo() == null
                    ? null : wm.getConnectionInfo().getSSID();
            HookLog.log(TAG + " mdns | wifi ssid=" + ssid
                    + " ip=" + wm.getConnectionInfo().getIpAddress());
        } catch (Throwable t) {
            HookLog.log(TAG + " mdns | wifi log failed");
        }
    }
}
