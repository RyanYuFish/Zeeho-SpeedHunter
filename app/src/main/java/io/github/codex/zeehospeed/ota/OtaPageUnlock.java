package io.github.codex.zeehospeed.ota;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import io.github.codex.zeehospeed.core.ActivityTracker;
import io.github.codex.zeehospeed.core.HookLog;
import io.github.codex.zeehospeed.core.Targets;
import io.github.codex.zeehospeed.core.ViewKit;

/**
 * OTA 界面解锁：把「已是最新版本」那一屏还原成可升级的状态。
 *
 * <p>只动控件的可见性与 enabled，<b>不碰点击监听</b>，因此不会破坏 App 原有逻辑；
 * 也正因为如此，服务端没有下发升级包时点下去依然是「无可用更新」——这是刻意的。</p>
 */
public final class OtaPageUnlock {

    private static final int SCAN_WINDOW_MS = 12000;
    private static final int SCAN_STEP_MS = 500;

    private static final Set<String> LOGGED_TEXT =
            Collections.synchronizedSet(new HashSet<String>());

    private OtaPageUnlock() {
    }

    static void install() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!Targets.isOta(activity)) return;

                ActivityTracker.setOta(activity);
                View root = activity.getWindow() == null
                        ? null : activity.getWindow().getDecorView();
                if (root == null) return;

                HookLog.log(HookLog.OTA + " onResume " + activity.getClass().getName());
                for (int delay = 0; delay <= SCAN_WINDOW_MS; delay += SCAN_STEP_MS) {
                    final View capturedRoot = root;
                    final Activity act = activity;
                    root.postDelayed(() -> unlockPage(act, capturedRoot), delay);
                }
            }
        });
    }

    /** 遍历 OTA 页面：恢复入口与按钮，并记录页面上出现过的文案（便于对齐版本）。 */
    private static void unlockPage(Activity activity, View view) {
        if (view == null || activity == null || activity.isFinishing()) return;

        String name = ViewKit.resourceName(view);
        if (name != null) {
            if (OtaOptions.FORCE_VISIBLE.contains(name)) {
                ViewKit.reveal(view);
            } else if (OtaOptions.FORCE_GONE.contains(name)) {
                view.setVisibility(View.GONE);
            } else if (OtaOptions.BUTTONS.contains(name) && !view.isEnabled()) {
                view.setEnabled(true);
            }
        }

        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                logText(name, text.toString());
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                unlockPage(activity, group.getChildAt(i));
            }
        }
    }

    private static void logText(String name, String text) {
        String line = (name == null ? "<no-id>" : name) + " = " + text;
        if (LOGGED_TEXT.add(line)) {
            HookLog.log(HookLog.OTA + " view " + line);
        }
    }

    /** 返回应当被强制设置的可见性；{@code null} 表示这个控件不归 OTA 解锁管。 */
    public static Integer forcedVisibility(View view) {
        String name = ViewKit.resourceName(view);
        if (name == null) return null;
        if (OtaOptions.FORCE_VISIBLE.contains(name)) return View.VISIBLE;
        if (OtaOptions.FORCE_GONE.contains(name)) return View.GONE;
        return null;
    }

    /** 升级按钮被置灰时重新启用 —— 只改 enabled，不碰点击监听。 */
    public static void enableButton(View view) {
        if (view == null || !OtaOptions.UNLOCK_UI) return;
        String name = ViewKit.resourceName(view);
        if (name == null || !OtaOptions.BUTTONS.contains(name)) return;
        if (!view.isEnabled()) view.setEnabled(true);
    }

    /** View 是否位于 OTA 页面（拿不到宿主 Activity 时用最近一次兜底）。 */
    public static boolean isOnOtaPage(View view) {
        Activity activity = ViewKit.findActivity(view.getContext());
        if (activity == null) activity = ActivityTracker.getOta();
        return Targets.isOta(activity);
    }
}
