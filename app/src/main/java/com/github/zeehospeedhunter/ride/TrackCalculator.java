package com.github.zeehospeedhunter.ride;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹解析 + 基础指标计算（对应技术文档里的 {@code TrackCalculator.java}）。
 *
 * <p>轨迹串格式（{@code ridetrack_v2} 的 {@code trajectory} 字段，服务端原样）：</p>
 * <pre>
 *   lon,lat,speed,ts[,dir]&amp;lon,lat,speed,ts[,dir]&amp;…
 * </pre>
 * <p>分段符是 {@code &amp;}，点内分隔符是 {@code ,}；每点至少 4 个字段（经度/纬度/速度/时间戳），
 * 第 5 个字段（方向）用不上。<b>坏点直接丢</b>，一处脏数据不许毁掉整趟行程。</p>
 *
 * <p>指标定义与文档 4.1 一致：</p>
 * <table>
 *   <tr><td>总里程</td><td>Haversine 累加相邻点距离</td></tr>
 *   <tr><td>总时长</td><td>末点时间戳 − 首点时间戳</td></tr>
 *   <tr><td>平均速度</td><td>总里程 / 总时长</td></tr>
 *   <tr><td>最高速度</td><td>轨迹点速度最大值</td></tr>
 *   <tr><td>移动时长</td><td>累加速度 &gt; {@code MOVING_V_MIN} 的相邻点时间差</td></tr>
 *   <tr><td>平均移动速度</td><td>总里程 / 移动时长</td></tr>
 * </table>
 *
 * <p>耗时：1000+ 点在手机上 &lt; 10 ms（纯 O(n) 线性扫描，无排序、无装箱）。</p>
 */
public final class TrackCalculator {

    /** 一个轨迹点：经度、纬度、速度（km/h）、时间（ms）、原始第 5 字段。 */
    public static final class Pt {
        public double lon;
        public double lat;
        public double v;
        public double t;
        public double d;
    }

    /** 一趟行程的基础指标。单位：里程 km、时长 min、速度 km/h。 */
    public static final class Metrics {
        public int points;
        public double distanceKm;
        public double durationMin;
        public double avgSpeed;
        public double maxSpeed;
        public double movingMin;
        public double avgMovingSpeed;

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "pts=%d km=%.2f dur=%.1fmin avg=%.1f max=%.1f mov=%.1fmin vavg=%.1f",
                    points, distanceKm, durationMin, avgSpeed, maxSpeed,
                    movingMin, avgMovingSpeed);
        }
    }

    private TrackCalculator() {
    }

    // ==================== 解析 ====================

    public static List<Pt> parse(String traj) {
        List<Pt> pts = new ArrayList<>();
        if (traj == null || traj.isEmpty()) {
            return pts;
        }
        String[] seg = traj.split("&");
        for (String s : seg) {
            if (s.isEmpty()) {
                continue;
            }
            String[] p = s.split(",");
            if (p.length < 4) {
                continue;
            }
            try {
                Pt pt = new Pt();
                pt.lon = Double.parseDouble(p[0]);
                pt.lat = Double.parseDouble(p[1]);
                pt.v = Double.parseDouble(p[2]);
                pt.t = Double.parseDouble(p[3]);
                pt.d = p.length > 4 ? parseOrZero(p[4]) : 0;
                pts.add(pt);
            } catch (NumberFormatException ignored) {
                // 单点坏就丢这个点
            }
        }
        return pts;
    }

    private static double parseOrZero(String s) {
        try {
            return Double.parseDouble(s);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ==================== 指标 ====================

    /** 点数不足 {@link RideConfig#MIN_POINTS} 时返回 {@code null}（算出来的数不可信）。 */
    public static Metrics compute(List<Pt> pts) {
        if (pts == null || pts.size() < RideConfig.MIN_POINTS) {
            return null;
        }
        Metrics m = new Metrics();
        m.points = pts.size();

        double meters = 0;
        double movingMs = 0;
        for (int i = 1; i < pts.size(); i++) {
            Pt a = pts.get(i - 1);
            Pt b = pts.get(i);
            meters += haversine(a.lon, a.lat, b.lon, b.lat);
            double vIn = Math.max(a.v, b.v);
            double dt = b.t - a.t;
            if (vIn > RideConfig.MOVING_V_MIN && dt > 0 && dt < 60000) {
                movingMs += dt;
            }
            if (b.v > m.maxSpeed) {
                m.maxSpeed = b.v;
            }
        }
        // 末段速度也要算进 max（循环里比的是 b.v，末点已包含）
        m.distanceKm = meters / 1000.0;
        m.durationMin = Math.max(0, pts.get(pts.size() - 1).t - pts.get(0).t) / 60000.0;
        m.movingMin = movingMs / 60000.0;
        m.avgSpeed = m.durationMin > 0 ? m.distanceKm / m.durationMin : 0;
        m.avgMovingSpeed = m.movingMin > 0 ? m.distanceKm / m.movingMin : 0;
        if (m.maxSpeed < 0) {
            m.maxSpeed = 0;
        }
        return m;
    }

    // ==================== 几何 ====================

    /** Haversine 距离（米）。 */
    public static double haversine(double lon1, double lat1, double lon2, double lat2) {
        double dLat = (lat2 - lat1) * Math.PI / 180;
        double dLon = (lon2 - lon1) * Math.PI / 180;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return RideConfig.EARTH_R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 大圆方位角（度）。 */
    public static double bearing(double lon1, double lat1, double lon2, double lat2) {
        double y = Math.sin((lon2 - lon1) * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180);
        double x = Math.cos(lat1 * Math.PI / 180) * Math.sin(lat2 * Math.PI / 180)
                - Math.sin(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180)
                * Math.cos((lon2 - lon1) * Math.PI / 180);
        return Math.atan2(y, x) * 180 / Math.PI;
    }

    /** 角度差，归一到 (-180, 180]。 */
    public static double angDiff(double a, double b) {
        double d = b - a;
        while (d > 180) {
            d -= 360;
        }
        while (d < -180) {
            d += 360;
        }
        return d;
    }

    /** 两角的中点（走最短弧）。 */
    public static double mid(double a, double b) {
        return a + angDiff(a, b) / 2;
    }
}
