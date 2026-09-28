package io.github.codex.zeehospeed.core;

import android.app.Activity;

import java.util.Locale;

/**
 * 目标 App 的包名、已知组件类名，以及「这个 Activity 属于哪个页面」的判断。
 *
 * <p>这些类名来自 AndroidManifest.xml（用 {@code aapt2 dump xmltree} 解出来的），
 * 业务代码被爱加密加固并不影响 Manifest —— 所以它们是稳定锚点。</p>
 */
public final class Targets {

    public static final String PACKAGE = "com.cfmoto";

    private static final String PREFIX = "com.cfmoto.";

    // ---------- 骑行记录相关页面 ----------
    public static final String RIDE_HISTORY = PREFIX + "ui.moto.HistoryTravelActivity";
    public static final String RIDE_DETAIL = PREFIX + "ui.moto.TravelDetailActivity";
    public static final String RIDE_MY_RIDE = PREFIX + "ui.moto.myrideinfo.MyRideInfoActivity";
    public static final String RIDE_ANALYSE = PREFIX + "ui.moto.DriveAnalyseActivity";
    public static final String RIDE_CONTROL = PREFIX + "ui.moto.controlplatform.ControlPlatformActivity";

    private static final String HINT_ANALYSE = "analyse";
    private static final String HINT_CONTROL = "controlplatform";

    // ---------- OTA 相关页面与服务（来自 AndroidManifest.xml） ----------
    public static final String[] OTA_ACTIVITIES = {
            PREFIX + "ui.mine.ota.OTAActivity",
            PREFIX + "ui.mine.ota.OTAUpgradeSettingsActivity",
            PREFIX + "ota.OtaMultitaskActivity",
            PREFIX + "ota.OtaDetailActivity",
            PREFIX + "ota.OtaUpRecordActivity",
    };

    public static final String[] OTA_SERVICES = {
            PREFIX + "ui.mine.ota.OTADownloadService",
            PREFIX + "ui.mine.ota.OTAService",
    };

    private static final String[] HINT_OTA = {
            ".ota.", "otaactivity", "otaupgrade", "otadetail", "otamultitask", "otauprecord",
    };

    private Targets() {
    }

    /** 只处理目标 App 自己的页面，系统弹窗等一律跳过。 */
    public static boolean isTargetClass(String className) {
        return className != null && className.startsWith(PREFIX);
    }

    public static boolean isRideRecord(Activity activity) {
        return isHistory(activity) || isDetail(activity) || isMyRide(activity);
    }

    public static boolean isHistory(Activity activity) {
        return matches(activity, RIDE_HISTORY);
    }

    public static boolean isDetail(Activity activity) {
        return matches(activity, RIDE_DETAIL);
    }

    public static boolean isMyRide(Activity activity) {
        return matches(activity, RIDE_MY_RIDE);
    }

    public static boolean isAnalyse(Activity activity) {
        return matches(activity, RIDE_ANALYSE) || contains(activity, HINT_ANALYSE);
    }

    public static boolean isControl(Activity activity) {
        return matches(activity, RIDE_CONTROL) || contains(activity, HINT_CONTROL);
    }

    /** OTA 页面：先精确匹配 Manifest 里的类名，再按类名片段兜底。 */
    public static boolean isOta(Activity activity) {
        if (activity == null) return false;
        String className = activity.getClass().getName();
        for (String known : OTA_ACTIVITIES) {
            if (known.equals(className)) return true;
        }
        String lower = className.toLowerCase(Locale.ROOT);
        for (String hint : HINT_OTA) {
            if (lower.contains(hint)) return true;
        }
        return false;
    }

    private static boolean matches(Activity activity, String className) {
        return activity != null && className.equals(activity.getClass().getName());
    }

    private static boolean contains(Activity activity, String hint) {
        return activity != null
                && activity.getClass().getName().toLowerCase(Locale.ROOT).contains(hint);
    }
}
