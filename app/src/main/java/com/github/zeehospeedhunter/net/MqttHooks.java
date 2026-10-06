package com.github.zeehospeedhunter.net;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import com.github.zeehospeedhunter.core.HookKit;
import com.github.zeehospeedhunter.core.HookLog;

/**
 * MQTT（Paho）连接与报文捕获 —— 找 broker / 凭据 / topic。
 *
 * <p><b>为什么抓这个</b>：2026-10-03 查明 ZEEHO 的 OTA 推送走 <b>MQTT over TLS(8883)</b>，
 * 不是 BLE（车端有 4G 模块，TBOX 直连 broker）。要刷自制固件，就得知道
 * broker 地址、认证凭据、以及「让车端拉某个包」的 topic 格式。
 * App 里的配置在爱加密的 dex 里静态拿不到，但 <b>Paho 是公开库，
 * 类名与方法签名在加固后依然稳定</b>，可以在运行时截获。</p>
 *
 * <p><b>红线</b>：本类<b>只读</b>，只记录 connect/publish/subscribe 的参数，
 * 不修改、不重发、不断开连接。发消息属于车控行为，等协议完全确认前不做。</p>
 *
 * <p><b>锚点</b>（org.eclipse.paho，App 内重写为 {@code com.cfmoto.org.eclipse.paho}）：</p>
 * <ul>
 *   <li>{@code MqttAsyncClient#connect(MqttConnectOptions)} —— broker/凭据都在 opts 里；</li>
 *   <li>{@code MqttAsyncClient#publish(String, MqttMessage)} —— topic + payload；</li>
 *   <li>{@code MqttAsyncClient#subscribe(String, int)} —— 订阅的 topic（= 下行指令格式）。</li>
 * </ul>
 */
public final class MqttHooks {

    private static final String TAG = HookLog.NET;

    /** Paho 可能被重定位到 App 包名下（加固常见做法），两种都试。 */
    private static final String[] PAHO_ROOTS = {
            "com.cfmoto.org.eclipse.paho.client.mqttv3.",
            "org.eclipse.paho.client.mqttv3.",
    };

    private static final String[] CLIENT_CLASSES = {
            "MqttAsyncClient", "MqttClient", "MqttConnectOptions",
    };

    /** 同一 key 只打一次，避免轮询刷屏。 */
    private static final Set<String> SEEN = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private static final int MAX_PAYLOAD = 512;

    public static void installAll() {
        int n = 0;
        n += hookConnect();
        n += hookServiceConnect();     // ★ CONNECT 真正发生处（Paho Android Service）
        n += hookServiceSend();        // ★★ 抓 Service 发出的原始 CONNECT 字节（含真 username）
        n += hookPublish();
        n += hookSubscribe();
        if (n == 0) {
            HookLog.log(TAG + " mqtt: no Paho class found (will retry lazily)");
        } else {
            HookLog.log(TAG + " mqtt: hooks installed (" + n + ")");
        }
    }

    /**
     * 抓 {@code MqttConnection} 发往 socket 的原始字节。
     *
     * <p>为什么必须到这一层：{@code MqttConnectOptions} 里的 {@code userName} 在
     * App 侧看不到（实测为空），而 MQTT CONNECT 报文的 userName 字段是
     * <b>UTF-8 编码后紧跟 2 字节长度前缀</b>，直接从字节流里就能读出来 ——
     * 这比反射字段可靠得多，也不受「字段被混淆/改名」影响。</p>
     */
    private static int hookServiceSend() {
        Class<?> c = paho("android.service.MqttConnection");
        if (c == null) {
            for (String root : new String[]{
                    "com.cfmoto.org.eclipse.paho.android.service.MqttConnection",
                    "org.eclipse.paho.android.service.MqttConnection"}) {
                c = HookKit.findClassIfExists(root);
                if (c != null) {
                    break;
                }
            }
        }
        if (c == null) {
            return 0;
        }
        int n = 0;
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            boolean takesBytes = p.length > 0 && (p[0] == byte[].class
                    || (p.length > 1 && p[1] == byte[].class));
            if (!takesBytes) {
                continue;
            }
            final int idx = (p[0] == byte[].class) ? 0 : 1;
            try {
                de.robv.android.xposed.XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object raw = param.args[idx];
                        if (!(raw instanceof byte[])) {
                            return;
                        }
                        byte[] b = (byte[]) raw;
                        if (b.length < 2) {
                            return;
                        }
                        int type = b[0] & 0xF0;
                        if (type != 0x10) {   // 只看 CONNECT
                            return;
                        }
                        logConnectPacket(b);
                    }
                });
                HookLog.log(TAG + " mqtt hooked MqttConnection#" + m.getName() + " (raw send)");
                n++;
            } catch (Throwable t) {
                HookLog.log(TAG + " mqtt hook raw " + m.getName() + " fail: " + t);
            }
        }

        // ★ 连接成功/失败回调里 dump 完整 options —— 此时 userName 已填充好
        for (String cb : new String[]{"doAfterConnectSuccess", "connectComplete",
                "doAfterConnectFail", "getConnectOptions"}) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(cb)) {
                    continue;
                }
                try {
                    de.robv.android.xposed.XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object opts = param.getResult();
                                if (opts == null) {
                                    Object[] a = param.args;
                                    for (Object o : a) {
                                        if (o != null
                                                && o.getClass().getSimpleName()
                                                .contains("ConnectOptions")) {
                                            opts = o;
                                            break;
                                        }
                                    }
                                }
                                logOptions("AFTER:" + m.getName(), opts);
                                if (opts != null) {
                                    HookLog.log(TAG + " mqtt AFTER " + m.getName()
                                            + " allStrings: " + stringFields(opts));
                                }
                            } catch (Throwable t) {
                                HookLog.log(TAG + " mqtt after log fail: " + t);
                            }
                        }
                    });
                    HookLog.log(TAG + " mqtt hooked(after) " + cb);
                    n++;
                } catch (Throwable t) {
                    HookLog.log(TAG + " mqtt hook after " + cb + " fail: " + t);
                }
            }
        }
        return n;
    }

    /** 解析 MQTT 3.1.1 CONNECT 报文字段（全部按 UTF-8 变长长度）。 */
    private static void logConnectPacket(byte[] b) {
        try {
            int i = 0;
            if (b.length < 2) {
                return;
            }
            i++;                                   // 固定头 0x10
            i += varLen(b, i);                     // remaining length

            int pn = lenOf(b, i);
            i += 2;
            String proto = new String(b, i, pn, "UTF-8");
            i += pn;

            int level = b[i++] & 0xFF;
            int flags = b[i++] & 0xFF;
            int ka = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            i += 2;

            String clientId = readStr(b, i);
            i += 2 + utf8len(clientId);

            String willTopic = null, willMsg = null, user = null, pw = null;
            if ((flags & 0x04) != 0) {             // will flag
                willTopic = readStr(b, i);
                i += 2 + utf8len(willTopic);
                willMsg = readStr(b, i);
                i += 2 + utf8len(willMsg);
            }
            if ((flags & 0x80) != 0) {             // password flag
                user = readStr(b, i);
                i += 2 + utf8len(user);
                pw = readStr(b, i);
            }
            HookLog.log(TAG + " mqtt RAW CONNECT proto=" + proto + " v" + level
                    + " flags=0x" + Integer.toHexString(flags)
                    + " keepalive=" + ka
                    + " clientId=" + clientId
                    + " userName=" + user
                    + " password=" + pw
                    + " willTopic=" + willTopic);
        } catch (Throwable t) {
            HookLog.log(TAG + " mqtt parse CONNECT fail: " + t);
        }
    }

    private static int utf8len(String s) {
        try {
            return s.getBytes("UTF-8").length;
        } catch (Throwable e) {
            return s.length();
        }
    }

    private static int varLen(byte[] b, int i) {
        int mult = 1, val = 0, n = 0;
        while (i + n < b.length && n < 4) {
            int d = b[i + n] & 0xFF;
            val += (d & 0x7F) * mult;
            mult *= 128;
            n++;
            if ((d & 0x80) == 0) {
                break;
            }
        }
        return n;
    }

    private static int lenOf(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    /** 读 2 字节长度前缀 + UTF-8 内容。 */
    private static String readStr(byte[] b, int i) {
        if (i + 2 > b.length) {
            return "";
        }
        int n = lenOf(b, i);
        if (n <= 0 || i + 2 + n > b.length) {
            return "";
        }
        try {
            return new String(b, i + 2, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================== Service 侧 connect：broker/凭据真正在这里 ====================

    /**
     * Paho 的 Android 架构是「App 进程 {@code MqttAsyncClient} → Binder → Service 进程
     * {@code MqttConnection} → 真正的 socket」。所以 {@code MqttAsyncClient#connect}
     * 只看得到 token，<b>broker URI / userName / password 全在 Service 侧</b>。
     *
     * <p>Service 与 App 同 uid 但可能是独立进程，LSPosed 对 {@code :remote} 进程同样生效
     * （模块作用域是包名，不是进程名）。</p>
     */
    private static int hookServiceConnect() {
        int n = 0;
        Class<?> c = paho("android.service.MqttConnection");
        if (c == null) {
            // 也可能在裸包名下
            for (String root : new String[]{
                    "com.cfmoto.org.eclipse.paho.android.service.MqttConnection",
                    "org.eclipse.paho.android.service.MqttConnection"}) {
                c = HookKit.findClassIfExists(root);
                if (c != null) {
                    break;
                }
            }
        }
        if (c == null) {
            HookLog.log(TAG + " mqtt: MqttConnection class not in App process"
                    + " (Service 在独立进程，需 LSPosed 覆盖该进程)");
            return 0;
        }
        // 抓所有名字里带 connect/oauth 的方法，参数里找 MqttConnectOptions
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            String mn = m.getName().toLowerCase();
            // ★ 关键是 setConnectOptions：Paho 的 Service 用它接收 App 传来的完整配置
            //（App 侧那个 connect() 已被 hook 装上，但 Service 侧这层才带 serverURI）
            if (!mn.contains("connect") && !mn.contains("oauth")) {
                continue;
            }
            boolean hasOpts = false;
            for (Class<?> pc : m.getParameterTypes()) {
                if (pc.getSimpleName().contains("ConnectOptions")) {
                    hasOpts = true;
                    break;
                }
            }
            try {
                de.robv.android.xposed.XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            // thisObject = MqttConnection，它持有 host/port/ssl
                            logConnectionFields(param.thisObject, m.getName());
                            Class<?>[] pp = m.getParameterTypes();
                            for (int i = 0; i < pp.length; i++) {
                                if (pp[i].getSimpleName().contains("ConnectOptions")) {
                                    // ★ before：options 刚被设置，userName 一定在里面
                                    logOptions("BEFORE:" + m.getName(), param.args[i]);
                                }
                            }
                        } catch (Throwable t) {
                            HookLog.log(TAG + " mqtt svc log fail: " + t);
                        }
                    }
                });
                HookLog.log(TAG + " mqtt hooked MqttConnection#" + m.getName()
                        + (hasOpts ? " (hasOpts)" : ""));
                n++;
            } catch (Throwable t) {
                HookLog.log(TAG + " mqtt hook MqttConnection#" + m.getName() + " fail: " + t);
            }
        }
        return n;
    }

    /**
     * {@code MqttConnection} 的 broker 地址存在 service specific fields 里
     * （字段名形如 {@code mqttServiceSpecificParams} / {@code host} / {@code port}），
     * 也可能打包在 {@code MqttConnectOptions.serverURI}。两种都试。
     */
    private static void logConnectionFields(Object conn, String where) {
        if (conn == null) {
            return;
        }
        for (String f : new String[]{"host", "serverHost", "hostname", "mHost",
                "port", "serverPort", "mPort"}) {
            Object v = readField(conn, f);
            if (v != null && !(v instanceof String && ((String) v).isEmpty())) {
                HookLog.log(TAG + " mqtt BROKER via " + where + "." + f + " = " + v);
            }
        }
        Object uri = readField(conn, "serverURI", "mServerURI", "uri");
        if (uri != null) {
            HookLog.log(TAG + " mqtt BROKER via " + where + ".serverURI = " + uri);
        }
        // 连接成功后 socket 目标地址最能说明问题
        try {
            java.lang.reflect.Field f = conn.getClass().getDeclaredField("client");
            f.setAccessible(true);
            Object client = f.get(conn);
            if (client != null) {
                Object host = readField(client, "host", "serverHost");
                Object port = readField(client, "port");
                if (host != null) {
                    HookLog.log(TAG + " mqtt BROKER via client " + host + ":" + port);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 找类（加固后可能延迟加载，故每次都试） ====================

    private static Class<?> paho(String simple) {
        for (String root : PAHO_ROOTS) {
            Class<?> c = HookKit.findClassIfExists(root + simple);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    // ==================== connect：broker + 凭据 ====================

    private static int hookConnect() {
        int n = 0;
        for (String simple : new String[]{"MqttAsyncClient", "MqttClient"}) {
            Class<?> c = paho(simple);
            if (c == null) {
                continue;
            }
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!"connect".equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                // connect(MqttConnectOptions) / connect(Object, MqttConnectOptions)
                if (p.length == 0) {
                    continue;
                }
                final Class<?> optType = p[p.length - 1];
                if (!optType.getSimpleName().contains("ConnectOptions")) {
                    continue;
                }
                try {
                    XposedBridgeHook(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object opts = param.args[p.length - 1];
                                logOptions(simple, opts);
                                // ★ thisObject 上的 serverURI 才是真 broker
                                logServerURI(simple, param.thisObject);
                            } catch (Throwable t) {
                                HookLog.log(TAG + " mqtt connect log fail: " + t);
                            }
                        }
                    });
                    n++;
                } catch (Throwable t) {
                    HookLog.log(TAG + " mqtt hook " + simple + "#connect fail: " + t);
                }
            }
        }
        return n;
    }

    /** {@code MqttAsyncClient#getServerURI()} / {@code getServerURIString()} —— broker 地址。 */
    private static void logServerURI(String client, Object clientObj) {
        if (clientObj == null) {
            return;
        }
        for (String g : new String[]{"getServerURI", "getServerURIString"}) {
            try {
                java.lang.reflect.Method m = clientObj.getClass().getMethod(g);
                Object v = m.invoke(clientObj);
                if (v != null) {
                    HookLog.log(TAG + " mqtt SERVER client=" + client + " " + g + "=" + v);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // 退而求其次：直接读字段
        Object v = readField(clientObj, "serverURI", "mServerURI");
        if (v != null) {
            HookLog.log(TAG + " mqtt SERVER client=" + client + " field serverURI=" + v);
        }
    }

    private static void logOptions(String client, Object o) {
        if (o == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(TAG).append(" mqtt CONNECT ").append(client).append(" {");
        appendStr(sb, o, "serverURI", "uri");
        appendStr(sb, o, "userName", "user");
        appendBool(sb, o, "automaticReconnect", "autoReconnect");
        appendBool(sb, o, "cleanSession", "cleanSession");
        appendInt(sb, o, "keepAliveInterval", "keepAlive");
        appendInt(sb, o, "connectionTimeout", "connTimeout");
        appendInt(sb, o, "MqttVersion", "version");
        // password 是 char[]，单独打
        try {
            char[] pw = readCharArray(o, "password");
            if (pw != null && pw.length > 0) {
                sb.append(", password=").append(mask(pw));
            }
        } catch (Throwable ignored) {
        }
        sb.append('}');
        HookLog.log(sb.toString());
        // ★ 无论 userName 是否为空，都把所有 String 字段打出来
        // （实测 userName 读到空，真实值可能在其他字段或由 Service 侧另行设置）
        Object user = readField(o, "userName", "user");
        HookLog.log(TAG + " mqtt CONNECT " + client
                + " userName=" + (user instanceof String ? "'" + user + "'" : String.valueOf(user))
                + " allStrings: " + stringFields(o));
    }

    /** 列出对象上所有非空 String 字段（定位 username 实际藏在哪个字段名下）。 */
    private static String stringFields(Object o) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v instanceof String && !((String) v).isEmpty()
                                && !((String) v).contains("://")) {
                            sb.append(f.getName()).append('=').append(v).append(' ');
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "(none)" : sb.toString().trim();
    }

    // ==================== publish：上行 ====================

    private static int hookPublish() {
        int n = 0;
        Class<?> c = paho("MqttAsyncClient");
        if (c == null) {
            c = paho("MqttClient");
        }
        if (c == null) {
            return 0;
        }
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (!"publish".equals(m.getName())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            // publish(String, MqttMessage) 是最常用签名
            if (p.length < 2 || p[0] != String.class) {
                continue;
            }
            final int msgIdx = p.length - 1;
            try {
                XposedBridgeHook(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String topic = (String) param.args[0];
                            Object msg = param.args[msgIdx];
                            String payload = payloadOf(msg);
                            String key = "pub:" + topic;
                            if (SEEN.add(key)) {
                                HookLog.log(TAG + " mqtt PUB topic=" + topic
                                        + " len=" + (payload == null ? -1 : payload.length())
                                        + " " + preview(payload));
                            } else {
                                HookLog.log(TAG + " mqtt PUB topic=" + topic
                                        + " len=" + (payload == null ? -1 : payload.length()));
                            }
                        } catch (Throwable t) {
                            HookLog.log(TAG + " mqtt publish log fail: " + t);
                        }
                    }
                });
                n++;
            } catch (Throwable t) {
                HookLog.log(TAG + " mqtt hook publish fail: " + t);
            }
        }
        return n;
    }

    // ==================== subscribe：下行（★最重要：OTA 指令从哪来） ====================

    private static int hookSubscribe() {
        int n = 0;
        for (String simple : new String[]{"MqttAsyncClient", "MqttClient"}) {
            Class<?> c = paho(simple);
            if (c == null) {
                continue;
            }
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!"subscribe".equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 1 || p[0] != String.class) {
                    continue;
                }
                try {
                    XposedBridgeHook(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String topic = (String) param.args[0];
                                if (SEEN.add("sub:" + topic)) {
                                    HookLog.log(TAG + " mqtt SUB topic=" + topic
                                            + "  ★下行指令通道");
                                }
                            } catch (Throwable t) {
                                HookLog.log(TAG + " mqtt subscribe log fail: " + t);
                            }
                        }
                    });
                    n++;
                } catch (Throwable t) {
                    HookLog.log(TAG + " mqtt hook subscribe fail: " + t);
                }
            }
        }
        return n;
    }

    // ==================== 反射工具 ====================

    private static void XposedBridgeHook(java.lang.reflect.Method m, XC_MethodHook h) {
        de.robv.android.xposed.XposedBridge.hookMethod(m, h);
    }

    private static Object readField(Object o, String... names) {
        Class<?> c = o.getClass();
        for (String name : names) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) {
            }
            // 沿父类找
            Class<?> k = c.getSuperclass();
            while (k != null) {
                try {
                    Field f = k.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (Throwable ignored) {
                    k = k.getSuperclass();
                }
            }
        }
        return null;
    }

    private static void appendStr(StringBuilder sb, Object o, String label, String key) {
        Object v = readField(o, key);
        if (v instanceof String && !((String) v).isEmpty()) {
            sb.append(", ").append(label).append('=').append(v);
        }
    }

    private static void appendBool(StringBuilder sb, Object o, String key, String label) {
        Object v = readField(o, key);
        if (v instanceof Boolean) {
            sb.append(", ").append(label).append('=').append(v);
        }
    }

    private static void appendInt(StringBuilder sb, Object o, String key, String label) {
        Object v = readField(o, key);
        if (v instanceof Integer) {
            sb.append(", ").append(label).append('=').append(v);
        }
    }

    private static char[] readCharArray(Object o, String key) {
        Object v = readField(o, key);
        return v instanceof char[] ? (char[]) v : null;
    }

    /**
     * 阿里云 IoT 的 password 形如 32 位小写 hex。
     * <p>为了能离线复现连接（broker + 三元组已抓到），这里<b>完整记录</b>而不是打码 ——
     * 它是本项目自有账号下的设备凭据，不涉及第三方系统。
     * 若要接入自己账号的设备，同样只需改这一处。</p>
     */
    private static final boolean REVEAL_SECRET = true;

    private static String mask(char[] pw) {
        if (pw == null) {
            return "null";
        }
        if (REVEAL_SECRET) {
            return new String(pw);
        }
        StringBuilder sb = new StringBuilder("len=").append(pw.length).append(" [");
        for (int i = 0; i < pw.length; i++) {
            sb.append(i == 0 || i == pw.length - 1 ? pw[i] : '*');
        }
        return sb.append(']').toString();
    }

    private static String payloadOf(Object msg) {
        if (msg == null) {
            return null;
        }
        // Paho 1.x：MqttMessage 有 public byte[] payload 字段，也有 getPayload()
        try {
            java.lang.reflect.Method g = msg.getClass().getMethod("getPayload");
            Object p = g.invoke(msg);
            if (p instanceof byte[]) {
                return new String((byte[]) p, "UTF-8");
            }
        } catch (Throwable ignored) {
        }
        for (String name : new String[]{"payload", "mPayload", "data"}) {
            try {
                java.lang.reflect.Field f = msg.getClass().getField(name);
                Object p = f.get(msg);
                if (p instanceof byte[]) {
                    return new String((byte[]) p, "UTF-8");
                }
            } catch (Throwable ignored) {
            }
        }
        return null;   // ★ 拿不到就返回 null，别把 toString() 当 payload 记下来误导
    }

    private static String preview(String s) {
        if (s == null) {
            return "";
        }
        String t = s.length() > MAX_PAYLOAD ? s.substring(0, MAX_PAYLOAD) + "…" : s;
        return t.replace("\n", "\\n").replace("\r", "\\r");
    }

    private MqttHooks() {
    }
}
