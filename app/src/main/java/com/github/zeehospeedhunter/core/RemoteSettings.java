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

    /** 最近一次广播送达的值（内存，即时生效，优先级最高）。 */
    private static volatile Map<String, Boolean> liveConfig;

    /** 持久化文件解析出的值（冷启动恢复用）。 */
    private static volatile Map<String, String> persisted;

    private static long fetchedAt;
    private static String source = "<init>";
    private static String lastLoggedSource = "";
    private static boolean receiverRegistered;

    private RemoteSettings() {
    }

    public static boolean getBool(String key, boolean defaultValue) {
        Map<String, Boolean> live = liveConfig;
        if (live != null && live.containsKey(key)) {
            Boolean value = live.get(key);
            return value != null ? value : defaultValue;
        }
        Map<String, String> map = fetch();
        String value = map.get(key);
        if (value == null) return defaultValue;
        return "true".equals(value) || "1".equals(value);
    }

    public static String describeSource() {
        fetch();
        return liveConfig != null ? "broadcast" : source;
    }

    // ==================== 广播接收（主通道） ====================

    private static void registerReceiverIfNeeded() {
        if (receiverRegistered) return;
        Application app = currentApplication();
        if (app == null) return;   // attach 早期拿不到，等第一个响应时再试
        synchronized (LOCK) {
            if (receiverRegistered) return;
            try {
                IntentFilter filter = new IntentFilter(ACTION_CONFIG);
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
            if (!ACTION_CONFIG.equals(intent.getAction())) return;
            try {
                Map<String, Boolean> config = new HashMap<>();
                config.put(Keys.KEY_WEB_LAYER, intent.getBooleanExtra(Keys.KEY_WEB_LAYER, true));
                config.put(Keys.KEY_GATE_ANALYSE, intent.getBooleanExtra(Keys.KEY_GATE_ANALYSE, true));
                config.put(Keys.KEY_GATE_EVENT, intent.getBooleanExtra(Keys.KEY_GATE_EVENT, true));
                config.put(Keys.KEY_VIEW_LAYER, intent.getBooleanExtra(Keys.KEY_VIEW_LAYER, false));
                liveConfig = config;

                // 落盘到自己的外部 files 目录（本进程一定可写，HookLog 已验证）
                File dir = context.getExternalFilesDir(null);
                if (dir != null) {
                    JSONObject json = new JSONObject();
                    for (Map.Entry<String, Boolean> e : config.entrySet()) {
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
