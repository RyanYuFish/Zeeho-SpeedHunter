package com.github.zeehospeedhunter.ride;

import android.app.Application;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按天的小账本（对应技术文档里的 {@code CacheManager.java}）。
 *
 * <p><b>为什么必须落盘</b>：轨迹只在 {@code ridetrack_v2} 那一次响应里出现；
 * 而 App 的「每日列表」（{@code myRideInfo}）与「月度汇总」（{@code analyse}）
 * 是另外两个接口，<b>不带轨迹</b>，不落盘就永远补不上。</p>
 *
 * <p>脚本原版用 Shadowrocket 的 {@code $persistentStore} 存 {@code zeeho_day_<day>}，
 * 这里换成同名语义的 JSON 文件：{@code <com.cfmoto>/files/zeeho_days/2026_10_04.json}。
 * 选这个目录是因为它跟 {@code HookLog} 同处，本进程已经验证过可写。</p>
 *
 * <p>每个文件的内容：{@code {b, d, rides:{rideId:{b,d}, …}}} ——
 * 日总计 + 当天每条行程的明细（明细用于 rideId 对齐，避免同一趟行程被重复计数）。</p>
 */
public final class RideDayStore {

    private static final String DIR = "zeeho_days";
    private static final String PKG = "com.cfmoto";
    /**
     * 「待重灌」清单的文件名（放在 zeeho_days 的<b>上一级</b>，否则会被 clearAll 一起删掉）。
     *
     * <p>★ 这个文件是 iOS 版 {@code [ClearStore] cleared N keys} 那行日志的「有状态版」。
     * iOS 脚本清完账本只能把 N 打进控制台，界面上看不到，于是用户以为清完就好了，
     * 实际上还得逐月重翻一遍 —— 忘了就永远是 0。本模块把它落盘并通过广播回传给 UI，
     * 让「还差哪几个月没重灌」直接显示在标定页上。</p>
     */
    private static final String PENDING_FILE = "zeeho_pending.json";

    private RideDayStore() {
    }

    /** 一天的小账本。 */
    public static final class Day {
        public int b;
        public int d;
        public final Map<String, int[]> rides = new LinkedHashMap<>();
        /** rideId → {里程 km, 时长 min, 极速 km/h}；给 myRideInfo / analyse 补基础指标用。 */
        public final Map<String, double[]> metrics = new LinkedHashMap<>();

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            put(o, "b", b);
            put(o, "d", d);
            JSONObject rs = new JSONObject();
            for (Map.Entry<String, int[]> e : rides.entrySet()) {
                int[] v = e.getValue();
                JSONObject one = new JSONObject();
                put(one, "b", v[0]);
                put(one, "d", v[1]);
                put(rs, e.getKey(), one);
            }
            put(o, "rides", rs);
            JSONObject ms = new JSONObject();
            for (Map.Entry<String, double[]> e : metrics.entrySet()) {
                double[] v = e.getValue();
                JSONObject one = new JSONObject();
                put(one, "km", v[0]);
                put(one, "min", v[1]);
                put(one, "vmax", v[2]);
                put(ms, e.getKey(), one);
            }
            put(o, "m", ms);
            return o;
        }

        static Day from(JSONObject o) {
            Day day = new Day();
            day.b = o.optInt("b", 0);
            day.d = o.optInt("d", 0);
            JSONObject rs = o.optJSONObject("rides");
            if (rs != null) {
                java.util.Iterator<String> keys = rs.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    JSONObject one = rs.optJSONObject(k);
                    if (one != null) {
                        day.rides.put(k, new int[]{one.optInt("b", 0), one.optInt("d", 0)});
                    }
                }
            }
            JSONObject ms = o.optJSONObject("m");
            if (ms != null) {
                java.util.Iterator<String> keys = ms.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    JSONObject one = ms.optJSONObject(k);
                    if (one != null) {
                        day.metrics.put(k, new double[]{
                                one.optDouble("km", 0),
                                one.optDouble("min", 0),
                                one.optDouble("vmax", 0)});
                    }
                }
            }
            return day;
        }

        /** 重新累计日总计（明细是唯一真相）。 */
        void recount() {
            b = 0;
            d = 0;
            for (int[] v : rides.values()) {
                b += v[0];
                d += v[1];
            }
        }

        /** 当天所有行程的里程之和（km）。 */
        public double totalKm() {
            return sum(0);
        }

        /** 当天所有行程的时长之和（min）。 */
        public double totalMin() {
            return sum(1);
        }

        /** 当天所有行程里的最高速度（km/h）。 */
        public double totalMaxV() {
            double max = 0;
            for (double[] v : metrics.values()) {
                if (v[2] > max) {
                    max = v[2];
                }
            }
            return max;
        }

        private double sum(int idx) {
            double s = 0;
            for (double[] v : metrics.values()) {
                s += v[idx];
            }
            return s;
        }
    }

    // ==================== 读 ====================

    public static Day read(String day) {
        try {
            File dir = dir();
            if (dir == null) {
                return null;
            }
            File f = new File(dir, fileName(day));
            if (!f.exists() || f.length() == 0) {
                return null;
            }
            FileInputStream in = new FileInputStream(f);
            byte[] bytes;
            try {
                bytes = new byte[(int) f.length()];
                if (in.read(bytes) <= 0) {
                    return null;
                }
            } finally {
                in.close();
            }
            return Day.from(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 写 ====================

    /**
     * 按天聚合：<b>无条件覆盖</b>该 rideId 的记录并重算日总计。
     *
     * <p>★ 刻意没有「值为 0 就跳过」的守卫：轨迹算出来的就是真相，
     * 该行程重新出现在别的响应里（例如翻页命中同一趟）时必须覆盖掉旧的，
     * 否则会出现「先算到 0、后来算到 3，日总计还留着 0」的脏账。</p>
     */
    public static void accum(String day, String rideId, int b, int bd) {
        accum(day, rideId, b, bd, 0, 0, 0);
    }

    /** 同上，并记录该趟的基础指标（里程 km / 时长 min / 极速 km/h）供其他接口补 0 值。 */
    public static void accum(String day, String rideId, int b, int bd,
                             double km, double min, double maxV) {
        if (day == null || rideId == null || rideId.isEmpty()) {
            return;
        }
        Day cur = read(day);
        if (cur == null) {
            cur = new Day();
        }
        cur.rides.put(rideId, new int[]{b, bd});
        if (km > 0 || min > 0 || maxV > 0) {
            cur.metrics.put(rideId, new double[]{km, min, maxV});
        }
        cur.recount();
        write(day, cur);
    }

    private static void write(String day, Day value) {
        try {
            File dir = dir();
            if (dir == null) {
                return;
            }
            FileOutputStream out = new FileOutputStream(new File(dir, fileName(day)), false);
            try {
                out.write(value.toJson().toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // 落盘失败只影响跨接口的回填，本次响应里的数已经写进去了
        }
    }

    // ==================== 路径 ====================

    private static String fileName(String day) {
        return day.replace(".", "_") + ".json";
    }

    /**
     * 清空整个 day store（改完标定参数后要重算，旧账是用旧参数算的）。
     *
     * <p>★ 清空的同时把<b>被删掉的天数记进「待重灌」清单</b>（{@code zeeho_pending.json}）。
     * 这是从 iOS 版 {@code [ClearStore] cleared N keys} 学来的：脚本只能把 N 打进控制台，
     * 界面上看不到，用户就会以为「清完了=好了」，然后忘了重灌、看到的全是 0 ——
     * 本项目实测就踩过这个坑（清空 25 天账本，重灌只做了 1 天）。
     * 有了清单，标定页能直接显示「还差哪几个月」。</p>
     *
     * <p>只在「标定页」被显式点击时调用 —— 轨迹不常驻，删了就只能重新去 App 里
     * 逐月翻「历史轨迹」再灌一遍，所以这是个明确的动作而不是自动行为。</p>
     *
     * @return 删掉的文件数
     */
    public static int clearAll() {
        int n = 0;
        java.util.Set<String> cleared = new java.util.LinkedHashSet<>();
        try {
            File dir = dir();
            if (dir == null) {
                return 0;
            }
            File[] files = dir.listFiles();
            if (files == null) {
                return 0;
            }
            for (File f : files) {
                if (f.getName().endsWith(".json")) {
                    // 文件名 2026_07_01.json → 2026.07.01，记进待重灌清单
                    String day = f.getName().replace('_', '.').replace(".json", "");
                    if (f.delete()) {
                        n++;
                        cleared.add(day);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (n > 0) {
            addPending(cleared);
        }
        return n;
    }

    // ==================== 待重灌清单 ====================

    /**
     * 读「待重灌」清单。
     *
     * @return 天字符串集合（{@code 2026.07.01} 形式）；读不到返回空集
     */
    public static java.util.Set<String> pendingDays() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            File base = baseDir();
            if (base == null) return out;
            File f = new File(base, PENDING_FILE);
            if (!f.exists() || f.length() == 0) return out;
            byte[] bytes = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                if (in.read(bytes) <= 0) return out;
            } finally {
                in.close();
            }
            org.json.JSONArray arr =
                    new org.json.JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } catch (Throwable t) {
            return out;
        }
        return out;
    }

    /**
     * 累计待重灌的天（{@code accum} 每天被灌过之后调用，把那一天销账）。
     *
     * <p>为什么要有这个：清空是原子的，重灌是手工的（要回 App 逐月翻）。
     * 中间只要差一天，{@code myRideInfo} 那天就填不上、UI 显示 0，而且没有任何提示。
     * 销账后清单里剩下的就是「真的还没灌」的月份，一眼能看出缺口在哪。</p>
     */
    public static void markFilled(String day) {
        if (day == null || day.isEmpty()) return;
        java.util.Set<String> p = pendingDays();
        if (p.isEmpty()) return;
        if (p.remove(day)) {
            writePending(p);
        }
    }

    /** 把一批天加进待重灌清单。 */
    private static void addPending(java.util.Set<String> days) {
        if (days == null || days.isEmpty()) return;
        java.util.Set<String> p = pendingDays();
        p.addAll(days);
        writePending(p);
    }

    private static void writePending(java.util.Set<String> days) {
        try {
            File base = baseDir();
            if (base == null) return;
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String d : days) {
                arr.put(d);
            }
            FileOutputStream out =
                    new FileOutputStream(new File(base, PENDING_FILE), false);
            try {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把待重灌清单按月汇总，给 UI 显示（{@code 2026.07} 这种粒度）。
     *
     * @return 月份字符串集合，按字典序
     */
    public static java.util.Set<String> pendingMonths() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String day : pendingDays()) {
            if (day != null && day.length() >= 7) {
                out.add(day.substring(0, 7));   // 2026.07.01 -> 2026.07
            }
        }
        return out;
    }

    /** 待重灌的天数（UI 显示用）。 */
    public static int pendingDayCount() {
        return pendingDays().size();
    }

    /** 统计当前已有多少天的账（标定页显示用）。 */
    public static int dayCount() {
        try {
            File dir = dir();
            if (dir == null) {
                return 0;
            }
            File[] files = dir.listFiles();
            if (files == null) {
                return 0;
            }
            int n = 0;
            for (File f : files) {
                if (f.getName().endsWith(".json")) {
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static File dir() {
        File base = baseDir();
        if (base == null) {
            return null;
        }
        File d = new File(base, DIR);
        if (!d.exists() && !d.mkdirs()) {
            return null;
        }
        return d;
    }

    /** day store 的上一级目录（导出的轨迹样本也放这里）。 */
    static File exportDir() {
        return baseDir();
    }

    private static File baseDir() {
        try {
            Application app = currentApplication();
            File base = app != null ? app.getExternalFilesDir(null) : null;
            if (base == null) {
                base = new File(Environment.getExternalStorageDirectory(),
                        "Android/data/" + PKG + "/files");
            }
            return base;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Throwable ignored) {
        }
    }
}
