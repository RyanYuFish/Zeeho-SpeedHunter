package com.github.zeehospeedhunter.core;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;
import com.github.zeehospeedhunter.core.HookLog;

/**
 * hook 进程侧的配置读取。
 *
 * <p>实测（Nothing A024 / Android 16 / LSPosed IT v2.2.0）：</p>
 * <ul>
 *   <li>SharedPreferences 会被 LSPosed 重定向到 {@code /data/misc/apexdata/.../prefs/}，
 *       但目标进程用 {@link XSharedPreferences} 读回为空（文件 0660，目标 uid 读不了）；</li>
 *   <li>ContentProvider 被 Android 11+ 包可见性拦住（com.cfmoto 没有声明对本模块的 queries）。</li>
 * </ul>
 * <p>所以实际可用的链路是「广播 + 自落盘」：</p>
 * <ol>
 *   <li>hook 进程懒注册一个动态接收器（{@link #ACTION_CONFIG} 需换成 {@link Keys#ACTION_CONFIG}），
 *       收到 UI 发来的配置 → 内存立即生效（G1/G2 是每个响应实时读的，不用重启）；</li>
 *   <li>同时把配置写成 JSON 落到 <b>com.cfmoto 自己的外部 files 目录</b> —— 本进程写过
 *       （HookLog 就在这），一定可写；</li>
 *   <li>冷重启后从该文件读回，恢复上次配置。</li>
 * </ol>
 * <p>XSharedPreferences / ContentProvider 保留为兜底通道（其他 ROM 上可能反而好使）。</p>
 */
public final class RemoteSettings {

    private static final String TAG = HookLog.CFG;
    private static final long TTL_MS = 5000;

    /** UI → hook 的配置广播 action（= {@link Keys#ACTION_CONFIG}）。 */
    public static final String ACTION_CONFIG = Keys.ACTION_CONFIG;

    private static final Object LOCK = new Object();

    /**
     * 最近一次广播送达的值（内存，即时生效，优先级最高）。
     *
     * <p>★ 值类型统一用 String：这套 key 里既有开关（true/false）也有标定参数（浮点），
     * 用一个 {@code Map<String,Boolean>} 装不下浮点，所以全部按字符串存、
     * {@link #getBool} / {@link #getDouble} 各自解析。</p>
     */
    private static volatile Map<String, String> liveConfig;

    /** 持久化文件解析出的值（冷启动恢复用）。 */
    private static volatile Map<String, String> persisted;

    private static long fetchedAt;
    private static String source = "<init>";
    private static String lastLoggedSource = "";
    private static boolean receiverRegistered;

    private RemoteSettings() {
    }

    public static boolean getBool(String key, boolean defaultValue) {
        return "true".equals(getString(key, String.valueOf(defaultValue)));
    }

    /**
     * 读一个浮点配置（标定参数用）。
     *
     * <p>走同一套链路：广播的 liveConfig 里存的是字符串，持久化文件里也是字符串。
     * 解析失败（NaN / 空 / 非法）一律回退默认值 —— 参数写错不该让回填整体失效。</p>
     */
    public static double getDouble(String key, double defaultValue) {
        String raw = getString(key, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            return (Double.isNaN(v) || Double.isInfinite(v)) ? defaultValue : v;
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    /** 读一个字符串配置；liveConfig 优先，其次持久化文件，都没有则返回 {@code null}。 */
    public static String getString(String key, String defaultValue) {
        Map<String, String> live = liveConfig;
        if (live != null && live.containsKey(key)) {
            String text = live.get(key);
            return text == null || text.isEmpty() ? defaultValue : text;
        }
        Map<String, String> map = fetch();
        String value = map.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return value;
    }

    public static String describeSource() {
        fetch();
        return liveConfig != null ? "broadcast" : source;
    }

    // ==================== 广播接收（主通道） ====================

    /**
     * 主动把配置接收器注册上（在 {@code MainHook} attach 时调用）。
     *
     * <p>默认是懒注册（第一次读配置时才注册），但投屏广播（{@link Keys#ACTION_MIRROR_START}）
     * 可能在 App 还没读过任何配置时就被用户发出来，所以 attach 阶段就先注册好，
     * 保证这类一次性指令不被漏掉。</p>
     */
    public static void ensureReceiver() {
        registerReceiverIfNeeded();
    }

    private static void registerReceiverIfNeeded() {
        if (receiverRegistered) return;
        Application app = currentApplication();
        if (app == null) return;   // attach 早期拿不到，等第一个响应时再试
        synchronized (LOCK) {
            if (receiverRegistered) return;
            try {
                IntentFilter filter = new IntentFilter(ACTION_CONFIG);
                filter.addAction(com.github.zeehospeedhunter.core.Keys.ACTION_CLEAR_DAY_STORE);
                filter.addAction(com.github.zeehospeedhunter.core.Keys.ACTION_QUERY_STORE_STATE);
                filter.addAction(com.github.zeehospeedhunter.core.Keys.ACTION_QUERY_BEND_LOG);
                filter.addAction(com.github.zeehospeedhunter.core.Keys.ACTION_MIRROR_START);
                filter.addAction(com.github.zeehospeedhunter.core.Keys.ACTION_MIRROR_STOP);
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(new ConfigReceiver(), filter,
                            Context.RECEIVER_EXPORTED);
                } else {
                    app.registerReceiver(new ConfigReceiver(), filter);
                }
                receiverRegistered = true;
                HookLog.log(TAG + " config receiver registered");
            } catch (Throwable t) {
                HookLog.log(TAG + " register receiver failed: " + t);
            }
        }
    }

    private static final class ConfigReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (com.github.zeehospeedhunter.core.Keys.ACTION_CLEAR_DAY_STORE.equals(action)) {
                clearDayStore();
                return;
            }
            if (com.github.zeehospeedhunter.core.Keys.ACTION_QUERY_STORE_STATE.equals(action)) {
                replyStoreState(context);
                return;
            }
            if (com.github.zeehospeedhunter.core.Keys.ACTION_QUERY_BEND_LOG.equals(action)) {
                replyBendLog(context);
                return;
            }
            if (com.github.zeehospeedhunter.core.Keys.ACTION_MIRROR_START.equals(action)) {
                String ssid = intent.getStringExtra(
                        com.github.zeehospeedhunter.core.Keys.EXTRA_MIRROR_SSID);
                String pwd = intent.getStringExtra(
                        com.github.zeehospeedhunter.core.Keys.EXTRA_MIRROR_PWD);
                com.github.zeehospeedhunter.ota.MirrorTrigger.start(context, ssid, pwd);
                return;
            }
            if (com.github.zeehospeedhunter.core.Keys.ACTION_MIRROR_STOP.equals(action)) {
                com.github.zeehospeedhunter.ota.MirrorTrigger.stop(context);
                return;
            }
            if (!ACTION_CONFIG.equals(action)) return;
            try {
                Map<String, String> config = new HashMap<>();
                // 开关按 boolean 收，标定参数按 float 收，统一存成字符串
                put(config, intent, Keys.KEY_WEB_LAYER, true);
                put(config, intent, Keys.KEY_GATE_ANALYSE, true);
                put(config, intent, Keys.KEY_GATE_EVENT, true);
                put(config, intent, Keys.KEY_VIEW_LAYER, false);
                put(config, intent, Keys.KEY_RIDE_FILL, true);
                put(config, intent, Keys.KEY_RIDE_FULLSCAN, true);
                put(config, intent, Keys.KEY_TUNE_EXPORT, false);
                put(config, intent, Keys.KEY_DEBUG_BEND, false);
                // 标定参数：extra 里缺失时不覆盖（让 RideConfig 走 DEF_* 默认值）
                putNum(config, intent, Keys.KEY_BEND_V_MIN);
                putNum(config, intent, Keys.KEY_BEND_SEED);
                putNum(config, intent, Keys.KEY_BEND_CUM_MIN);
                putNum(config, intent, Keys.KEY_BEND_CUM_CAP);
                putNum(config, intent, Keys.KEY_BEND_COEF);
                putNum(config, intent, Keys.KEY_BEND_V_MAX);
                putNum(config, intent, Keys.KEY_BEND_MIN_KM);
                putNum(config, intent, Keys.KEY_BRAKE_CALIB);
                liveConfig = config;

                // 落盘到自己的外部 files 目录（本进程一定可写，HookLog 已验证）
                File dir = context.getExternalFilesDir(null);
                if (dir != null) {
                    JSONObject json = new JSONObject();
                    for (Map.Entry<String, String> e : config.entrySet()) {
                        json.put(e.getKey(), e.getValue());
                    }
                    File file = new File(dir, Keys.CONFIG_FILE);
                    FileOutputStream out = new FileOutputStream(file);
                    try {
                        out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                    } finally {
                        out.close();
                    }
                    HookLog.log(TAG + " config via broadcast, saved " + json
                            + " -> " + file.getAbsolutePath());
                } else {
                    HookLog.log(TAG + " config via broadcast (external dir unavailable)");
                }
            } catch (Throwable t) {
                HookLog.log(TAG + " config receive failed: " + t);
            }
        }
    }

    private static void put(Map<String, String> map, Intent intent, String key, boolean def) {
        map.put(key, String.valueOf(intent.getBooleanExtra(key, def)));
    }

    /**
     * 收一个浮点标定参数。
     *
     * <p>★ 缺失就<b>不 put</b>：这样 {@code liveConfig} 里没有这个 key，
     * {@code RideConfig} 会落到 {@code DEF_*} 默认值，而不是被 0 覆盖掉。</p>
     */
    private static void putNum(Map<String, String> map, Intent intent, String key) {
        if (!intent.hasExtra(key)) {
            return;
        }
        map.put(key, String.valueOf(intent.getFloatExtra(key, 0f)));
    }

    /**
     * 清空按天账本 —— 只由「压弯标定」页的按钮触发。
     *
     * <p>为什么必须走广播：day store 在 {@code com.cfmoto} 的 external files 下，
     * Android 11+ 不许别的 App 读那个目录（已实测 EACCES），所以 UI 进程只能发请求，
     * 由本进程（能写进去）自己删。</p>
     */
    private static void clearDayStore() {
        int n = com.github.zeehospeedhunter.ride.RideDayStore.clearAll();
        HookLog.log(TAG + " day store cleared: " + n + " files, pending="
                + com.github.zeehospeedhunter.ride.RideDayStore.pendingDayCount()
                + " days (requested by UI)");
    }

    /**
     * 回传账本状态给 UI 进程。
     *
     * <p>UI 进程读不到 {@code com.cfmoto} 的 external files（Android 11+ 跨 App 限制，
     * 已实测 EACCES），所以只能由本进程读出来再广播回去。
     * {@code setPackage} 指向模块包 —— 反向投递必须显式指定目标包，
     * 否则 Android 8+ 的隐式广播限制会让 UI 收不到（正向已经因为这个踩过坑）。</p>
     */
    private static void replyStoreState(Context context) {
        try {
            int onFile = com.github.zeehospeedhunter.ride.RideDayStore.dayCount();
            int pending = com.github.zeehospeedhunter.ride.RideDayStore.pendingDayCount();
            java.util.Set<String> months =
                    com.github.zeehospeedhunter.ride.RideDayStore.pendingMonths();
            StringBuilder sb = new StringBuilder();
            for (String m : months) {
                if (sb.length() > 0) sb.append(',');
                sb.append(m);
            }
            Intent reply = new Intent(com.github.zeehospeedhunter.core.Keys.ACTION_STORE_STATE);
            reply.putExtra(com.github.zeehospeedhunter.core.Keys.EXTRA_DAYS_ON_FILE, onFile);
            reply.putExtra(com.github.zeehospeedhunter.core.Keys.EXTRA_DAYS_PENDING, pending);
            reply.putExtra(com.github.zeehospeedhunter.core.Keys.EXTRA_MONTHS_PENDING,
                    sb.toString());
            reply.setPackage("com.github.zeehospeedhunter");
            context.sendBroadcast(reply);
            HookLog.log(TAG + " store state replied: onFile=" + onFile
                    + " pending=" + pending + " months=" + sb);
        } catch (Throwable t) {
            HookLog.log(TAG + " store state reply failed: " + t);
        }
    }

    /**
     * 把本进程日志里的 {@code BENDS} 行捞出来回传给「压弯标定」页显示。
     *
     * <p><b>为什么必须由本进程读</b>：{@code BENDS} 是 {@code BendDetector.explain()} 在
     * hook 进程里打的，落在 {@code /sdcard/Android/data/com.cfmoto/files/zeeho_hook.log}。
     * Android 11+ 不许别的 App 读那个目录（已实测 EACCES），所以只能本进程读出来再发回去。</p>
     *
     * <p>只保留尾部若干行：判定过程每趟一行、每行最多 900 字符，一次刷一个月就是几十行。</p>
     */
    private static void replyBendLog(Context context) {
        try {
            Application app = currentApplication();
            if (app == null) {
                return;
            }
            java.io.File dir = app.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            java.util.LinkedList<String> keep = new java.util.LinkedList<>();
            // .old 是轮转后的上一份，也要扫：4MB 轮转很快，BENDS 可能在旧文件里
            for (String name : new String[]{"zeeho_hook.log", "zeeho_hook.log.old"}) {
                java.io.File f = new java.io.File(dir, name);
                if (!f.exists() || f.length() == 0) {
                    continue;
                }
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f),
                                java.nio.charset.StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("] BENDS ")) {
                            if (line.length() > 500) {
                                line = line.substring(0, 500) + "…";
                            }
                            keep.add(line);
                            if (keep.size() > 40) {
                                keep.removeFirst();
                            }
                        }
                    }
                } finally {
                    r.close();
                }
            }
            if (keep.isEmpty()) {
                return;     // 还没产生过：让 UI 保持「还没有判定日志」的占位文案
            }
            Intent reply = new Intent(com.github.zeehospeedhunter.core.Keys.ACTION_BEND_LOG);
            reply.putExtra(com.github.zeehospeedhunter.core.Keys.EXTRA_BEND_LOG,
                    String.join("\n", keep));
            reply.setPackage("com.github.zeehospeedhunter");
            context.sendBroadcast(reply);
            HookLog.log(TAG + " bend log replied: " + keep.size() + " lines");
        } catch (Throwable t) {
            HookLog.log(TAG + " bend log reply failed: " + t);
        }
    }

    // ==================== 持久化文件（冷启动恢复） ====================
    private static Map<String, String> loadViaOwnFile() {
        try {
            Application app = currentApplication();
            if (app == null) return null;
            File dir = app.getExternalFilesDir(null);
            if (dir == null) {
                HookLog.log(TAG + " own file: external dir null");
                return null;
            }
            File file = new File(dir, Keys.CONFIG_FILE);
            if (!file.exists()) {
                HookLog.log(TAG + " own file: not exists " + file.getAbsolutePath());
                return null;
            }
            FileInputStream in = new FileInputStream(file);
            byte[] bytes;
            try {
                bytes = new byte[(int) file.length()];
                int read = in.read(bytes);
                if (read <= 0) {
                    HookLog.log(TAG + " own file: empty read " + read);
                    return null;
                }
            } finally {
                in.close();
            }
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Map<String, String> map = new HashMap<>();
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, String.valueOf(json.opt(key)));
            }
            return map;
        } catch (Throwable t) {
            HookLog.log(TAG + " own file read failed: " + t);
            return null;
        }
    }

    // ==================== 兜底通道 ====================

    private static Map<String, String> loadViaXSharedPreferences() {
        try {
            XSharedPreferences prefs = new XSharedPreferences("com.github.zeehospeedhunter");
            prefs.reload();
            Map<String, ?> all = prefs.getAll();
            if (all == null || all.isEmpty()) return null;
            Map<String, String> map = new HashMap<>();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                Object value = entry.getValue();
                if (value != null) map.put(entry.getKey(), String.valueOf(value));
            }
            return map;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, String> loadViaProvider() {
        try {
            Application app = currentApplication();
            if (app == null) return null;
            ContentResolver resolver = app.getContentResolver();
            Cursor cursor = resolver.query(ConfigProvider.CONTENT_URI,
                    null, null, null, null);
            if (cursor == null) return null;
            Map<String, String> map = new HashMap<>();
            try {
                int keyIdx = cursor.getColumnIndexOrThrow(ConfigProvider.COLUMN_KEY);
                int valIdx = cursor.getColumnIndexOrThrow(ConfigProvider.COLUMN_VALUE);
                while (cursor.moveToNext()) {
                    map.put(cursor.getString(keyIdx), cursor.getString(valIdx));
                }
            } finally {
                cursor.close();
            }
            return map;
        } catch (Throwable t) {
            return null;   // Android 11+ 包可见性会拦住这条通道
        }
    }

    // ==================== 汇总 ====================

    private static Map<String, String> fetch() {
        registerReceiverIfNeeded();
        long now = SystemClock.elapsedRealtime();
        Map<String, String> snapshot = persisted;
        if (snapshot != null && now - fetchedAt < TTL_MS) return snapshot;

        synchronized (LOCK) {
            now = SystemClock.elapsedRealtime();
            snapshot = persisted;
            if (snapshot != null && now - fetchedAt < TTL_MS) return snapshot;

            Map<String, String> fresh = loadViaOwnFile();
            if (fresh != null) {
                source = "file";
            } else {
                fresh = loadViaXSharedPreferences();
                if (fresh != null) {
                    source = "xsharedprefs";
                } else {
                    fresh = loadViaProvider();
                    if (fresh != null) source = "provider";
                }
            }
            if (fresh != null) {
                persisted = fresh;
            } else if (persisted == null) {
                persisted = new HashMap<>();   // 空表：key 缺失时按 defaultValue 走
                source = "default";
            }
            // Application 还没起来（attach 早期）时所有通道都拿不到东西，
            // 只缓存 1 秒 —— 等它就绪后马上重读，别把「未就绪」的结果缓存 5 秒
            boolean appReady = currentApplication() != null;
            fetchedAt = appReady ? now : now - TTL_MS + 1000;
            if (!source.equals(lastLoggedSource)) {
                lastLoggedSource = source;
                HookLog.log(TAG + " config source = " + source
                        + " size=" + persisted.size());
            }
            return persisted;
        }
    }

    /** hook 进程里拿 Application（不需要 Context 参数）。 */
    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
