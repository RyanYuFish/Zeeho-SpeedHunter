package com.github.zeehospeedhunter.ota;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.github.zeehospeedhunter.core.HookLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 在 hook 进程（com.cfmoto，同 UID）里<b>拉起 ZEEHO App 的投屏入口</b>。
 *
 * <h3>为什么必须放在 hook 进程</h3>
 * <p>投屏的私有镜像编解码在 {@code libECSDK.so} 里，只在 {@code com.cfmoto} 进程加载；
 * 模块 UI 进程没有这套库，且<b>无法</b>启动 app 内部 {@code exported=false} 的投屏 Activity
 * （跨 UID 会被 {@code SecurityException} 拦）。hook 进程与 app 同 UID，可以启动它。</p>
 *
 * <h3>怎么找到投屏入口</h3>
 * <p>app 被爱加密加固，类名反编译不出，但 Manifest 里的 Activity 声明还在。
 * 本类用 {@code PackageManager} 枚举 {@code com.cfmoto} 自己的 Activity，
 * 按关键字打分挑最可能的一个（mirror / project / cast / screen / ec / carbit / easyconnect …），
 * 带 {@code FLAG_ACTIVITY_NEW_TASK} 启动。所有候选 Activity 名都会打到 hook 日志，
 * 方便下次把准的那个写死进 {@link #PREFERRED_CLASS}。</p>
 *
 * <h3>边界</h3>
 * <p>本类<b>只负责启动投屏入口</b>，不实现镜像协议本身——那部分永远在 {@code libECSDK.so} 里。
 * 手机侧网络层（加入车机 AP）由模块 UI 的 {@link CarWifi} 完成，本类不重做。</p>
 */
public final class MirrorTrigger {

    private static final String TAG = HookLog.MIRROR;

    /** 一旦从 hook 日志里确认了正确的投屏 Activity，直接写死它，跳过关键字打分。 */
    private static final String PREFERRED_CLASS = "";

    /** 关键字 → 权重（越大越像投屏入口）。 */
    private static final String[][] KEYWORDS = {
            {"mirror", "6"}, {"project", "6"}, {"projection", "6"},
            {"cast", "5"}, {"screen", "5"}, {"ec", "4"}, {"carbit", "4"},
            {"easyconnect", "4"}, {"interconnect", "4"}, {"phone", "3"},
            {"connect", "2"}, {"wifi", "1"}, {"display", "3"}, {"hud", "2"},
    };

    private MirrorTrigger() {
    }

    /** 入口：hook 收到 {@code ACTION_MIRROR_START} 时调用。 */
    public static void start(Context app, String ssid, String pwd) {
        try {
            HookLog.log(TAG + " start requested (ssid=" + ssid + ", pwd.len="
                    + (pwd == null ? 0 : pwd.length()) + ")");
            if (app == null) {
                HookLog.log(TAG + " app context null —— hook 还没 attach");
                return;
            }
            PackageManager pm = app.getPackageManager();
            PackageInfo pi;
            try {
                pi = pm.getPackageInfo("com.cfmoto", PackageManager.GET_ACTIVITIES);
            } catch (Throwable t) {
                HookLog.log(TAG + " getPackageInfo failed: " + t);
                return;
            }
            if (pi.activities == null || pi.activities.length == 0) {
                HookLog.log(TAG + " no activities declared");
                return;
            }

            // 全部 Activity 名先打出来，便于人工挑准
            StringBuilder all = new StringBuilder("candidates(" + pi.activities.length + "): ");
            for (ActivityInfo a : pi.activities) {
                all.append(a.name).append(" | ");
            }
            HookLog.log(TAG + " " + all);

            String target = resolveTarget(pi.activities);
            if (target == null) {
                HookLog.log(TAG + " 没有命中投屏关键字，改启动 Launcher 主 Activity");
                target = launcherActivity(pi.activities);
            }
            if (target == null) {
                HookLog.log(TAG + " 连 Launcher 都找不到，放弃");
                return;
            }

            final String cls = target;
            Intent it = new Intent();
            it.setClassName("com.cfmoto", cls);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            it.putExtra("mirror_ssid", ssid == null ? "" : ssid);
            it.putExtra("mirror_pwd", pwd == null ? "" : pwd);
            try {
                app.startActivity(it);
                HookLog.log(TAG + " ★ 已启动投屏入口: " + cls
                        + "（请在车机上看是否进入投屏；若没反应，从上面 candidates 里挑准的填进 PREFERRED_CLASS）");
            } catch (Throwable t) {
                HookLog.log(TAG + " 启动 " + cls + " 失败: " + t
                        + " —— 仍从该 Activity 列表里挑投屏项写死 PREFERRED_CLASS");
            }
        } catch (Throwable t) {
            HookLog.log(TAG + " unexpected: " + t);
        }
    }

    /** 停止：目前 EC 投屏随 Activity 退出而结束，这里仅记录；后续可扩展发停止指令。 */
    public static void stop(Context app) {
        HookLog.log(TAG + " stop requested（投屏随 App 退出自动结束；如需强杀可在 PREFERRED_CLASS 上 finish）");
    }

    private static String resolveTarget(ActivityInfo[] acts) {
        if (PREFERRED_CLASS != null && !PREFERRED_CLASS.isEmpty()) {
            return PREFERRED_CLASS;
        }
        List<Score> scored = new ArrayList<>();
        for (ActivityInfo a : acts) {
            String name = a.name.toLowerCase();
            int score = 0;
            for (String[] kv : KEYWORDS) {
                if (name.contains(kv[0])) {
                    score += Integer.parseInt(kv[1]);
                }
            }
            if (score > 0) {
                scored.add(new Score(a.name, score));
            }
        }
        if (scored.isEmpty()) {
            return null;
        }
        scored.sort(new Comparator<Score>() {
            @Override
            public int compare(Score a, Score b) {
                return Integer.compare(b.score, a.score); // 降序
            }
        });
        HookLog.log(TAG + " 命中排序: " + scored);
        return scored.get(0).name;
    }

    private static String launcherActivity(ActivityInfo[] acts) {
        for (ActivityInfo a : acts) {
            // 通过 PackageManager 查 intent-filter 太重，这里用常识：含 .ui. 且最短的当 launcher
            if (a.name.toLowerCase().contains("main")) {
                return a.name;
            }
        }
        return acts.length > 0 ? acts[0].name : null;
    }

    private static final class Score {
        final String name;
        final int score;

        Score(String n, int s) {
            name = n;
            score = s;
        }

        @Override
        public String toString() {
            return name + "(" + score + ")";
        }
    }
}
