package io.github.codex.zeehospeed.ride;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import io.github.codex.zeehospeed.core.ActivityTracker;
import io.github.codex.zeehospeed.core.HookLog;
import io.github.codex.zeehospeed.core.Targets;
import io.github.codex.zeehospeed.core.ViewKit;
import io.github.codex.zeehospeed.ota.OtaOptions;
import io.github.codex.zeehospeed.ota.OtaPageUnlock;

/**
 * 骑行记录相关 hook：恢复被隐藏的速度与骑行统计字段。
 *
 * <p>四条互补的通道，因为「什么时候被藏」不确定：</p>
 * <ol>
 *   <li>onResume 后轮询扫描整棵视图树（每条路径 0～12 秒，覆盖异步填充的数据）；</li>
 *   <li>拦 {@code View#setVisibility}，把恢复目标强制改成 VISIBLE；</li>
 *   <li>拦 {@code View#onAttachedToWindow}，覆盖 RecyclerView 复用出来的 item；</li>
 *   <li>拦 {@code TextView#setText}，数据一到就恢复。</li>
 * </ol>
 */
public final class RideHooks {

    /** 每棵视图树扫描 0～12 秒，500 ms 一次 —— 覆盖接口回包后的异步渲染。 */
    private static final int SCAN_WINDOW_MS = 12000;
    private static final int SCAN_STEP_MS = 500;

    private static final Set<TextView> LOGGED_TEXT =
            Collections.newSetFromMap(new WeakHashMap<TextView, Boolean>());

    private RideHooks() {
    }

    public static void installAll() {
        try {
            hookActivityLifecycle();
            hookVisibility();
            hookAttachedViews();
            hookSpeedTextLogging();
            HookLog.log(HookLog.SPEED + " ride-record hooks installed");
        } catch (Throwable t) {
            HookLog.log(HookLog.SPEED + " install failed: " + t);
        }
    }

    // ==================== 1. 页面生命周期 ====================

    private static void hookActivityLifecycle() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!Targets.isTargetClass(activity.getClass().getName())) return;

                View root = activity.getWindow() == null
                        ? null : activity.getWindow().getDecorView();
                if (root == null) return;

                boolean isAnalyse = Targets.isAnalyse(activity);
                boolean isRideRecord = Targets.isRideRecord(activity);
                boolean isControl = Targets.isControl(activity);

                if (isAnalyse) ActivityTracker.setAnalyse(activity);
                if (isRideRecord) ActivityTracker.setRide(activity);
                if (isControl) ActivityTracker.setControl(activity);

                if (!isAnalyse && !isRideRecord && !isControl) return;

                for (int delay = 0; delay <= SCAN_WINDOW_MS; delay += SCAN_STEP_MS) {
                    final View capturedRoot = root;
                    final Activity act = activity;
                    final boolean analyse = isAnalyse;
                    final boolean control = isControl;
                    if (delay == 0 && Looper.myLooper() == Looper.getMainLooper()) {
                        scanTree(act, capturedRoot, analyse, control);
                    } else {
                        root.postDelayed(() -> scanTree(act, capturedRoot, analyse, control), delay);
                    }
                }
            }
        });
    }

    private static void scanTree(Activity activity, View view, boolean isAnalyse, boolean isControl) {
        if (view == null) return;
        if (activity == null || activity.isFinishing()) return;

        if (isControl) {
            // 孪生仪表页：先打诊断（用于确认控件 ID），再解锁
            if (view instanceof TextView) {
                TextView tv = (TextView) view;
                CharSequence text = tv.getText();
                HookLog.log(HookLog.CONTROL + " id=" + ViewKit.resourceName(tv)
                        + " | text=" + (text == null ? "null" : text.toString())
                        + " | vis=" + tv.getVisibility()
                        + " | alpha=" + tv.getAlpha());
            }
            if (RideViews.isUnsupportedHint(view)) {
                view.setVisibility(View.GONE);
            }
            if (RideViews.isDashboard(view)) {
                ViewKit.reveal(view);
            }
        } else if (isAnalyse) {
            if (RideViews.isAnalyseMetric(view)) {
                ViewKit.reveal(view);
            }
        } else {
            if (RideViews.isSpeed(view) || RideViews.isRideEntry(view)) {
                ViewKit.reveal(view);
            }
            if (Targets.isDetail(activity) && RideViews.isRecordContainer(view)
                    && RideViews.hasMeaningfulRecordValue(activity.getWindow().getDecorView())) {
                view.setVisibility(View.VISIBLE);
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                scanTree(activity, group.getChildAt(i), isAnalyse, isControl);
            }
        }
    }

    // ==================== 2. setVisibility ====================

    private static void hookVisibility() {
        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View view = (View) param.thisObject;
                        int target = (Integer) param.args[0];

                        // 骑行分析入口：任何页面都恢复
                        if (RideViews.isRideEntry(view)) {
                            param.args[0] = View.VISIBLE;
                            return;
                        }

                        // OTA：强制显示升级入口/按钮，隐藏「已是最新版本」分支
                        if (OtaOptions.UNLOCK_UI && OtaPageUnlock.isOnOtaPage(view)) {
                            Integer forced = OtaPageUnlock.forcedVisibility(view);
                            if (forced != null) {
                                param.args[0] = forced;
                                return;
                            }
                        }

                        // 孪生仪表页：藏掉「不支持」提示，恢复仪表控件
                        if (RideViews.isInControlPage(view)) {
                            if (RideViews.isUnsupportedHint(view)) {
                                param.args[0] = View.GONE;
                                return;
                            }
                            if (RideViews.isDashboard(view) && target != View.VISIBLE) {
                                param.args[0] = View.VISIBLE;
                                return;
                            }
                        }

                        // 骑行分析页
                        if (RideViews.isInAnalysePage(view) && RideViews.isAnalyseMetric(view)) {
                            if (target != View.VISIBLE) param.args[0] = View.VISIBLE;
                            return;
                        }

                        // 其他骑行页
                        if (target != View.VISIBLE && RideViews.isSpeed(view)) {
                            param.args[0] = View.VISIBLE;
                        }
                    }
                });
    }

    // ==================== 3. onAttachedToWindow ====================

    private static void hookAttachedViews() {
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View view = (View) param.thisObject;

                        if (RideViews.isRideEntry(view)) {
                            view.post(() -> ViewKit.reveal(view));
                            return;
                        }
                        if (OtaOptions.UNLOCK_UI && OtaPageUnlock.isOnOtaPage(view)) {
                            Integer forced = OtaPageUnlock.forcedVisibility(view);
                            if (forced != null) {
                                final int target = forced;
                                view.post(() -> {
                                    if (target == View.VISIBLE) {
                                        ViewKit.reveal(view);
                                    } else {
                                        view.setVisibility(target);
                                    }
                                    OtaPageUnlock.enableButton(view);
                                });
                                return;
                            }
                        }
                        if (RideViews.isInControlPage(view)) {
                            if (RideViews.isUnsupportedHint(view)) {
                                view.post(() -> view.setVisibility(View.GONE));
                            } else if (RideViews.isDashboard(view)) {
                                view.post(() -> ViewKit.reveal(view));
                            }
                            return;
                        }
                        if (RideViews.isInAnalysePage(view) && RideViews.isAnalyseMetric(view)) {
                            view.post(() -> ViewKit.reveal(view));
                            return;
                        }
                        if (RideViews.isSpeed(view)) {
                            view.post(() -> ViewKit.reveal(view));
                        }
                    }
                });
    }

    // ==================== 4. setText ====================

    private static void hookSpeedTextLogging() {
        XposedHelpers.findAndHookMethod(TextView.class, "setText",
                CharSequence.class, TextView.BufferType.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        TextView view = (TextView) param.thisObject;

                        if (RideViews.isRideEntry(view)) {
                            ViewKit.reveal(view);
                            return;
                        }
                        if (RideViews.isInControlPage(view)) {
                            if (RideViews.isUnsupportedHint(view)) {
                                view.setVisibility(View.GONE);
                            } else if (RideViews.isDashboard(view)) {
                                ViewKit.reveal(view);
                            }
                            return;
                        }
                        if (RideViews.isInAnalysePage(view) && RideViews.isAnalyseMetric(view)) {
                            ViewKit.reveal(view);
                            return;
                        }
                        if (!RideViews.isSpeed(view)) return;

                        ViewKit.reveal(view);
                        CharSequence text = view.getText();
                        if (text != null && text.length() > 0) {
                            synchronized (LOGGED_TEXT) {
                                // 每个控件只记一次，避免刷屏；同时也能看出哪些字段真的被赋值了
                                if (LOGGED_TEXT.add(view)) {
                                    HookLog.log(HookLog.SPEED + " "
                                            + ViewKit.resourceName(view) + " = " + text);
                                }
                            }
                        }
                    }
                });
    }
}
