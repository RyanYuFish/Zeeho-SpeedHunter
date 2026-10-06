package com.github.zeehospeedhunter.core;

import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 统一日志出口。
 *
 * <p>为什么要落盘：这台设备的 LSPosed（IT 分支，寄生管理器，跑在 {@code com.android.shell}
 * 进程里）<b>不会</b>把 {@link XposedBridge#log} 写进 logcat，也不会写进
 * {@code /data/adb/lspd/log/modules_*.log}。所以每条日志除了交给框架，还同时写进目标 App
 * 自己的目录，用 adb 直接读即可。</p>
 *
 * <p>读日志：</p>
 * <pre>
 * LOG=/sdcard/Android/data/com.cfmoto/files/zeeho_hook.log   # 不需要 root
 * adb shell cat $LOG | grep ZeehoHTTP
 * </pre>
 */
public final class HookLog {

    /** 模块名，出现在每次注入的首行日志里。 */
    public static final String MODULE = "ZeehoSpeedHunter";

    /** 日志 tag —— 用 grep 定位某一类输出，改动前先看 README 里的排查命令。 */
    public static final String SPEED = "[ZeehoSpeed]";
    public static final String OTA = "[ZeehoOTA]";
    public static final String HTTP = "[ZeehoHTTP]";
    public static final String NET = "[ZeehoNet]";
    public static final String CFG = "[ZeehoCfg]";
    public static final String WEBVIEW = "[ZeehoWebView]";
    public static final String CLASS = "[ZeehoClass]";
    public static final String CONTROL = "[ZeehoControl]";
    /** 骑行数据回填（急刹 / 压弯，算法与 {@code ZeehoRideFill.js} v15 对齐）。 */
    public static final String RIDE = "[ZeehoRide]";
    /** 仪表投屏（EasyConnect 镜像，绕过官方 App 手动入口）。 */
    public static final String MIRROR = "[ZeehoMirror]";

    private static final String FILE_NAME = "zeeho_hook.log";
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File internalFile;
    private static File externalFile;

    private HookLog() {
    }

    /** 目标进程加载时调用一次：确定两个落盘位置，任何一个可用就够了。 */
    public static void init(String packageName, String appDataDir) {
        if (appDataDir != null) {
            internalFile = prepare(new File(appDataDir, "files"));
        }
        try {
            File dir = new File(Environment.getExternalStorageDirectory(),
                    "Android/data/" + packageName + "/files");
            externalFile = prepare(dir);
        } catch (Throwable ignored) {
        }
    }

    private static File prepare(File dir) {
        try {
            if (dir.exists() || dir.mkdirs()) {
                return new File(dir, FILE_NAME);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 供首行日志打印 —— 出问题时能一眼看出日志到底写去哪了。 */
    public static String describeWriters() {
        return "internal=" + internalFile + " external=" + externalFile;
    }

    /**
     * 唯一日志出口：logcat + 两个落盘位置（各自独立失败，互不影响）。
     *
     * <p>2026-10-04 加 {@code android.util.Log}：手机上随便装个 logcat 阅读器按 tag
     * {@code ZeehoHook} 过滤就能看，不必为了读日志非得插数据线。
     * 落盘仍然是主通道 —— LSPosed（IT 分支，寄生管理器）自己不把
     * {@link XposedBridge#log} 写进 logcat。</p>
     */
    public static void log(String message) {
        try {
            android.util.Log.d(LOGCAT_TAG, message);
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.log(message);
        } catch (Throwable ignored) {
        }
        synchronized (LOCK) {
            append(internalFile, message);
            append(externalFile, message);
        }
    }

    /** logcat 过滤 tag —— 手机上用 logcat 阅读器 grep 这个。 */
    public static final String LOGCAT_TAG = "ZeehoHook";

    private static void append(File file, String message) {
        if (file == null) return;
        try {
            if (file.length() > MAX_BYTES) {
                File rotated = new File(file.getAbsolutePath() + ".old");
                if (rotated.exists()) {
                    rotated.delete();
                }
                file.renameTo(rotated);
            }
            FileWriter writer = new FileWriter(file, true);
            try {
                writer.write(TIME.format(new Date()) + "  " + message + "\n");
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
            // 日志绝不能影响被 hook 的 App
        }
    }
}
