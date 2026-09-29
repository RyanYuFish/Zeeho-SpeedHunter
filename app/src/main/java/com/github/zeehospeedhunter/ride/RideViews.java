package com.github.zeehospeedhunter.ride;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Locale;

import com.github.zeehospeedhunter.core.ActivityTracker;
import com.github.zeehospeedhunter.core.Targets;
import com.github.zeehospeedhunter.core.ViewKit;

/**
 * 骑行记录页的控件识别。
 *
 * <p>全部基于「资源 ID 名 + 同行标签文案」，不依赖被加固的业务类名 ——
 * 这样 App 升级、混淆都不会让判断失效，最差也只是变成 no-op。</p>
 */
public final class RideViews {

    private RideViews() {
    }

    /** 骑行分析入口（列表项或带「骑行分析」文案的控件）。 */
    public static boolean isRideEntry(View view) {
        if ("ll_rid_analysis".equals(ViewKit.resourceName(view))) return true;
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                String label = text.toString().trim();
                return "骑行分析".equals(label) || "rid_analysis".equalsIgnoreCase(label);
            }
        }
        return false;
    }

    /** 一行骑行统计里的数值控件（速度、急加速、压弯…）。 */
    public static boolean isSpeed(View view) {
        Activity activity = ViewKit.findActivity(view.getContext());
        if (activity == null) activity = ActivityTracker.getRide();
        if (activity == null) return false;

        if (Targets.isAnalyse(activity)) return isAnalyseMetric(view);
        if (!Targets.isRideRecord(activity)) return false;

        String name = ViewKit.resourceName(view);
        if (name != null && isRideMetricName(name)) {
            // 历史轨迹列表里只恢复速度，避免把整列都点亮
            return !Targets.isHistory(activity) || name.contains("speed");
        }
        return !Targets.isHistory(activity) && hasMetricLabelSibling(view);
    }

    /** 骑行分析页的统计项。 */
    public static boolean isAnalyseMetric(View view) {
        String name = ViewKit.resourceName(view);
        if (name == null) return false;

        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("speed")
                || lower.contains("acceler")
                || lower.contains("deceler")
                || lower.contains("brake")
                || lower.contains("bending")
                || lower.contains("bend")
                || lower.contains("lean")
                || lower.contains("corner")
                || lower.contains("curve")
                || lower.contains("turning")
                || lower.contains("sharp")
                || lower.contains("extreme")
                || lower.contains("night")
                || lower.contains("long_drive")
                || lower.contains("longdrive")
                || lower.contains("off_light")
                || lower.contains("offlight")
                || lower.contains("off_turn")
                || lower.contains("ride_mileage")
                || lower.contains("riding_time")
                || lower.contains("jia_su")
                || lower.contains("jian_su")
                || lower.contains("wan_dao")
                || lower.contains("ya_wan");
    }

    /** 孪生仪表页的核心控件（车速、电量、里程、档位…）。 */
    public static boolean isDashboard(View view) {
        String name = ViewKit.resourceName(view);
        if (name == null) return false;

        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("speed")
                || lower.contains("battery")
                || lower.contains("soc")
                || lower.contains("power")
                || lower.contains("gear")
                || lower.contains("mileage")
                || lower.contains("range")
                || lower.contains("odo")
                || lower.contains("voltage")
                || lower.contains("current")
                || lower.contains("temp")
                || lower.contains("percent")
                || lower.contains("dashboard")
                || lower.contains("instrument")
                || lower.contains("cluster")
                || lower.contains("panel");
    }

    /** 「当前车辆不支持孪生仪表」这类提示 —— 解锁时要藏掉。 */
    public static boolean isUnsupportedHint(View view) {
        if (!(view instanceof TextView)) {
            // 提示常常是包着 TextView 的容器，往下看一眼
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child instanceof TextView) {
                        CharSequence text = ((TextView) child).getText();
                        if (text != null && containsUnsupported(text.toString())) return true;
                    }
                }
            }
            return false;
        }
        CharSequence text = ((TextView) view).getText();
        return text != null && containsUnsupported(text.toString());
    }

    private static boolean containsUnsupported(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("不支持孪生")
                || lower.contains("不支持")
                || lower.contains("孪生仪表")
                || lower.contains("暂不支持")
                || lower.contains("此车型")
                || lower.contains("当前车辆");
    }

    /** 详情页里承载骑行数据的容器。 */
    public static boolean isRecordContainer(View view) {
        String name = ViewKit.resourceName(view);
        return "cl_ride_info".equals(name) || "group_rideinfo".equals(name);
    }

    /** 容器里是否已经有真实数值 —— 没有就不该把空壳显示出来。 */
    public static boolean hasMeaningfulRecordValue(View root) {
        if (root == null) return false;
        if (root instanceof TextView) {
            CharSequence text = ((TextView) root).getText();
            if (text != null && isMeaningful(text.toString())) {
                String name = ViewKit.resourceName(root);
                if ("tv_all_km".equals(name) || "tv_hour".equals(name)
                        || "tv_min".equals(name) || "tv_speed".equals(name)
                        || "tv_speed_playing".equals(name)
                        || "tv_travel_speed".equals(name)) {
                    return true;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasMeaningfulRecordValue(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static boolean isMeaningful(String text) {
        String value = text.trim();
        return !value.isEmpty() && !"--".equals(value) && !"无数据".equals(value);
    }

    /** 控件所在页面是不是骑行分析页（拿不到 Activity 时用最近一次兜底）。 */
    public static boolean isInAnalysePage(View view) {
        Activity activity = ViewKit.findActivity(view.getContext());
        if (activity == null) activity = ActivityTracker.getAnalyse();
        return Targets.isAnalyse(activity);
    }

    /** 控件所在页面是不是孪生仪表页。 */
    public static boolean isInControlPage(View view) {
        Activity activity = ViewKit.findActivity(view.getContext());
        if (activity == null) activity = ActivityTracker.getControl();
        return Targets.isControl(activity);
    }

    // ---------- 资源名 / 同辈标签的判定 ----------

    private static boolean isRideMetricName(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("speed") || value.contains("acceler")
                || value.contains("deceler") || value.contains("brake")
                || value.contains("bending") || value.contains("corner")
                || value.contains("curve") || value.contains("lean")
                || value.contains("bend") || value.contains("turning")
                // tv_sharp_turn（压弯）既不含 bend 也不含 turn/turning，此前全靠祖先链搭便车
                // —— App 一旦把压弯拆出容器它就会第一个消失，这里显式补上
                || value.contains("sharp")
                || value.contains("jia_su") || value.contains("jian_su")
                || value.contains("wan_dao") || value.contains("ya_wan")
                || value.contains("max_speed") || value.contains("brakes_times");
    }

    /**
     * 数值控件本身没有可识别的 ID 时，看同一行（同一个父容器）的标签文案。
     * 这是「字段名可能变、但界面文案不变」的兜底。
     */
    private static boolean hasMetricLabelSibling(View view) {
        if (!(view.getParent() instanceof ViewGroup)) return false;
        ViewGroup parent = (ViewGroup) view.getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View sibling = parent.getChildAt(i);
            if (sibling == view || !(sibling instanceof TextView)) continue;
            CharSequence text = ((TextView) sibling).getText();
            if (text == null) continue;
            String label = text.toString().toLowerCase(Locale.ROOT);
            if (label.contains("极速") || label.contains("最高速度")
                    || label.contains("最大速度") || label.contains("急加速")
                    || label.contains("急减速") || label.contains("急刹")
                    || label.contains("压弯") || label.contains("弯道")
                    || label.contains("加速") || label.contains("减速")
                    || label.contains("刹车") || label.contains("制动")
                    || label.contains("加速度") || label.contains("减速度")
                    || label.contains("acceleration") || label.contains("deceleration")
                    || label.contains("brake") || label.contains("bending")
                    || label.contains("corner") || label.contains("lean")) {
                return true;
            }
        }
        return false;
    }
}
