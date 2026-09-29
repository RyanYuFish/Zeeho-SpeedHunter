package com.github.zeehospeedhunter.core;

import android.app.Activity;

import java.lang.ref.WeakReference;

/**
 * 记录「当前处于哪个页面」。
 *
 * <p>很多控件从自己的 {@code Context} 追不到宿主 Activity（被 ContextWrapper 包住、
 * 或还没 attach 到窗口），这时用最近一次 onResume 的页面兜底判断。</p>
 *
 * <p>全部用 {@link WeakReference}，不会把 Activity 拖住不放。</p>
 */
public final class ActivityTracker {

    private static WeakReference<Activity> ride = new WeakReference<>(null);
    private static WeakReference<Activity> analyse = new WeakReference<>(null);
    private static WeakReference<Activity> control = new WeakReference<>(null);
    private static WeakReference<Activity> ota = new WeakReference<>(null);

    private ActivityTracker() {
    }

    public static void setRide(Activity activity) {
        ride = new WeakReference<>(activity);
    }

    public static void setAnalyse(Activity activity) {
        analyse = new WeakReference<>(activity);
    }

    public static void setControl(Activity activity) {
        control = new WeakReference<>(activity);
    }

    public static void setOta(Activity activity) {
        ota = new WeakReference<>(activity);
    }

    public static Activity getRide() {
        return ride.get();
    }

    public static Activity getAnalyse() {
        return analyse.get();
    }

    public static Activity getControl() {
        return control.get();
    }

    public static Activity getOta() {
        return ota.get();
    }
}
