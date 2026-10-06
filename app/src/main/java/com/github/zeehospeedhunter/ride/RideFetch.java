package com.github.zeehospeedhunter.ride;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import com.github.zeehospeedhunter.core.HookKit;
import com.github.zeehospeedhunter.core.HookLog;

/**
 * 请求侧改写：把 {@code ridetrack_v2} 的 {@code pageSize} 从 20 放大到 {@link #PAGE_SIZE}。
 *
 * <p><b>为什么必须做（实测结论，别被表面现象骗了）</b>：服务端把 {@code pageSize} 硬钳在 20 ——
 * 改成 200 之后返回的<b>条数完全不变</b>（实测 7 月仍是 20 条 / 205 KB）。所以放大
 * pageSize <b>不是</b>「一次拿全」。</p>
 *
 * <p>它真正的作用是：让 App 的分页判断失效。App 靠「本页返回条数 &lt; 请求的 pageSize」
 * 判断是否到底；pageSize=20 时刚好返回 20 条（满页），App 只能靠人工上拉才继续翻页；
 * 而声明 200 之后 App 会<b>一路翻到空页为止</b>（实测 9 月自动翻 9 页拿到 168 条 / 30 天，
 * 8 月 31 天、7 月 21 天全部自动补齐）。也就是说：<b>每页都回填 + 让 App 自己翻到底</b>
 * 才是全量方案，缺一不可。</p>
 *
 * <p>为什么安全：只改一个数字参数，不碰任何请求头/凭据/业务字段。
 * 响应变大后 {@code net.NetPatch} 对这个 URL 也会走放宽后的字节上限
 * （见 {@link #pageSizeBytes()}）。</p>
 *
 * <p>需要 {@code rideFill} 开着才有意义 —— 放大 pageSize 本身不产生任何可见效果，
 * 它的唯一目的就是把完整轨迹喂给 {@link RideFill}。</p>
 */
public final class RideFetch {

    private static final String TAG = HookLog.RIDE;

    /** 放大后的每页条数。一个月最多 30 天 × 每天 3~5 趟 ≈ 150，200 足够覆盖。 */
    public static final int PAGE_SIZE = 200;

    private static final Set<String> LOGGED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 防止自己 newBuilder().build() 又进自己的 hook。 */
    private static final ThreadLocal<Boolean> REBUILDING = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    /** okhttp Request$Builder 的候选类名（App 加固后可能被重定位）。 */
    public static final String[] REQUEST_BUILDERS = {
            "okhttp3.Request$Builder",
            "com.cfmoto.okhttp3.Request$Builder",
            "com.cfmoto.shadow.okhttp3.Request$Builder",
    };

    private RideFetch() {
    }

    public static void installAll() {
        for (final String className : REQUEST_BUILDERS) {
            HookKit.hookIfExists(TAG, className, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(REBUILDING.get())) return;
                    try {
                        if (!RideOptions.fullScan()) return;
                        widenPageSize(param);
                    } catch (Throwable t) {
                        logOnce("widen failed: " + t);
                    }
                }
            });
        }
        HookLog.log(TAG + " ridetrack pageSize widener installed ("
                + PAGE_SIZE + ", on=" + RideOptions.fullScan() + ")");
    }

    private static void widenPageSize(XC_MethodHook.MethodHookParam param) {
        Object request = param.getResult();
        if (request == null) return;
        Object urlObj = XposedHelpers.callMethod(request, "url");
        if (urlObj == null) return;
        String url = urlObj.toString();
        if (!url.contains("ridetrack_v2")) return;

        String marker = "pageSize=";
        int at = url.indexOf(marker);
        if (at < 0) return;
        int from = at + marker.length();
        int to = from;
        while (to < url.length() && Character.isDigit(url.charAt(to))) to++;
        if (to == from) return;
        int current = 0;
        try {
            current = Integer.parseInt(url.substring(from, to));
        } catch (NumberFormatException e) {
            return;
        }
        if (current >= PAGE_SIZE) return;

        String widened = url.substring(0, from) + PAGE_SIZE + url.substring(to);
        Object builder = XposedHelpers.callMethod(request, "newBuilder");
        XposedHelpers.callMethod(builder, "url", widened);
        REBUILDING.set(Boolean.TRUE);
        try {
            param.setResult(XposedHelpers.callMethod(builder, "build"));
        } finally {
            REBUILDING.set(Boolean.FALSE);
        }
        logOnce("pageSize " + current + " -> " + PAGE_SIZE + "  @ ridetrack_v2");
    }

    /**
     * {@code ridetrack_v2} 放宽后的响应体上限。
     *
     * <p>虽然 pageSize 放大后服务端仍只给 20 条（实测 205 KB），但保留余量：
     * 将来服务端真放开限制、或按天分组的响应变大时，回填不至于刚跑起来就被体积门槛挡掉。</p>
     */
    public static long pageSizeBytes() {
        return 16L * 1024 * 1024;
    }

    private static void logOnce(String message) {
        if (LOGGED.add(message)) {
            HookLog.log(TAG + " " + message);
        }
    }

    /** 供 {@code MainActivity} 说明文案用：把 {@link #PAGE_SIZE} 讲成人话。 */
    public static String describe() {
        return String.format(Locale.ROOT, "每页 %d 条", PAGE_SIZE);
    }
}
