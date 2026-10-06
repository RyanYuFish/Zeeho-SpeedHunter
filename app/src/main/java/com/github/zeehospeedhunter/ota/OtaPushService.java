package com.github.zeehospeedhunter.ota;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.github.zeehospeedhunter.ui.OtaActivity;

/**
 * 推送期间的前台服务。
 *
 * <h3>为什么必须前台</h3>
 * <p>{@code socket_l} 推送是 <b>106 MB / 约 26000 帧</b>，在 Wi-Fi 上要跑几十秒到几分钟。
 * Android 8.0 起后台进程随时会被回收，一旦进程死了 socket 断开，
 * 车机侧 {@code this+0x138}（已收）与 {@code this+0x90}（总长）永远不相等，
 * {@code endOta()} 不触发、文件停在半截 —— 这是<b>不可回退</b>的状态。</p>
 *
 * <p>前台服务 + 常驻通知是唯一能保证长传输不被杀的方式（也是 Android 14+ 的硬要求：
 * {@code dataSync} 类型必须声明 {@code FOREGROUND_SERVICE_DATA_SYNC}）。</p>
 */
public final class OtaPushService extends android.app.Service {

    private static final String CHANNEL_ID = "ota_push";
    private static final int NOTIF_ID = 0x0A7A;

    public static final String ACTION_START = "com.github.zeehospeedhunter.ota.PUSH_START";
    public static final String ACTION_STOP = "com.github.zeehospeedhunter.ota.PUSH_STOP";

    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_STATUS = "status";

    /** 进度回报给界面的回调（Activity 注册，Service 销毁时清空）。 */
    public interface Progress {
        void onPushLog(String line);

        void onPushProgress(int percent, long sent, long total, String note);

        void onPushDone(boolean ok, String summary);
    }

    private static volatile Progress sink;

    private volatile boolean running;

    public static void setSink(Progress p) {
        sink = p;
    }

    /** 启动前台服务并开始推送。 */
    public static void start(Context ctx, String filePath) {
        Intent i = new Intent(ctx, OtaPushService.class);
        i.setAction(ACTION_START);
        i.putExtra("file", filePath);
        ctx.startForegroundService(i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, OtaPushService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        makeChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            running = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        final String path = intent == null ? null : intent.getStringExtra("file");
        startForeground(NOTIF_ID, notify(0, 0, "准备中…"));

        if (path == null) {
            done(false, "没有指定包");
            return START_NOT_STICKY;
        }
        final java.io.File f = new java.io.File(path);
        if (!f.isFile()) {
            done(false, "找不到包：" + path);
            return START_NOT_STICKY;
        }

        running = true;
        startPush(f);
        return START_NOT_STICKY;
    }

    private void startPush(final java.io.File f) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                OtaSocketPusher.push(f, false, new OtaSocketPusher.Callback() {
                    @Override
                    public void onLog(final String line) {
                        updateNotif(-1, -1, line);
                        Progress p = sink;
                        if (p != null) {
                            p.onPushLog(line);
                        }
                    }

                    @Override
                    public void onProgress(final int percent, final long sent, final long total) {
                        updateNotif(percent, sent, "传输中…");
                        Progress p = sink;
                        if (p != null) {
                            p.onPushProgress(percent, sent, total, "传输中…");
                        }
                    }

                    @Override
                    public void onDone(final boolean ok, final String summary) {
                        done(ok, summary);
                    }
                });
            }
        }, "ota-push-svc").start();
    }

    private void done(boolean ok, String summary) {
        running = false;
        Progress p = sink;
        if (p != null) {
            p.onPushDone(ok, summary);
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ 通知

    private void makeChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "OTA 固件推送",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("向车机 10950 端口推送固件包");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification notify(int pct, long sent, String text) {
        Intent open = new Intent(this, OtaActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String t = text;
        if (pct >= 0) {
            t = pct + "%  " + sent / 1048576 + "/" + (sent + (100 - pct) * 1048576L) / 1048576
                    + " MB  " + text;
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("正在推送固件到车机")
                .setContentText(t)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotif(int pct, long sent, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIF_ID, notify(pct, sent, text));
            } catch (Throwable ignored) {
            }
        }
    }
}
