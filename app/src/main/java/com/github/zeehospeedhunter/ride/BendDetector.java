package com.github.zeehospeedhunter.ride;

import java.util.ArrayList;
import java.util.List;


/**
 * 压弯检测（对应技术文档里的 {@code BendDetector.java}）。
 *
 * <p>核心思路：<b>累计转角法</b> —— 光看「航向变化率超过阈值」会把一个连续的弯
 * 数成十几次（每个点都超阈值），所以先把连续超阈值的点合并成一个「run」，
 * 用 run 的<b>累计转角</b>再判一次：一个 run 累计转过 15° 以上才算一次压弯。</p>
 *
 * <p><b>本实现与 iOS 侧 {@code ZeehoRideFill.js} 的 countBends 逐行等价</b>
 * （v16 定稿，参数由 {@link RideConfig} 运行时读取，算法本身不写死常量）：</p>
 * <ol>
 *   <li><b>逐段航向</b>：相邻点算 bearing；段太短（&lt; 6 m，停车时 GPS 乱飘）、
 *       间隔太久（&gt; 25 s，中间丢数据）、时间倒流一律标 NaN 断开；</li>
 *   <li><b>三点圆均值平滑</b>：{@code sh[i] = mid(mid(hdg[i-1],hdg[i]),hdg[i+1])}，
 *       压掉 GPS 锯齿（边界点用两点均值）；</li>
 *   <li><b>标记转弯点</b>：{@code |Δheading| >= BEND_SEED(5°)}，
 *       并过三道闸：速度在 [20, 80] km/h、单步转角 &lt; 45°（防抖点）、方向取符号；</li>
 *   <li><b>合并为同向 run</b>：连续同符号累加 {@code cum}，遇反号立即断开另起一个
 *       （左弯右弯是两次压弯，不该合并）；</li>
 *   <li><b>过滤</b>：{@code BEND_CUM_MIN(15°) <= cum <= BEND_CUM_CAP(90°)}、
 *       段长 ≥ {@link RideConfig#BEND_RUN_MIN}、段内最高速 ≤ {@code BEND_V_MAX(80)}，
 *       最后乘 {@code BEND_COEF}。</li>
 * </ol>
 *
 * <p>★ 参数调整史（别再回退）：</p>
 * <ul>
 *   <li>技术文档 v1.0 给 {@code BEND_V_MIN: 10} → 实测 7 月 1211 次（152 次/100km），
 *       明显偏高。<b>这才是「压弯过多」的真因</b>，不是 cum_min。</li>
 *   <li>把 {@code v_min} 提到 <b>20</b>（与 iOS 定稿一致）后密度降到 85 次/100km。</li>
 *   <li>本项目一度把 {@code cum_min} 也从 15 提到 30 试图压总量，
 *       结果是无依据地把 7 月压到 616 次（只有 iOS 口径的 62%）。
 *       <b>已改回 15 与 iOS 对齐</b>。</li>
 * </ul>
 *
 * <p>复杂度 O(n)，无排序。1000 点 &lt; 5 ms。</p>
 */
public final class BendDetector {

    /** 一个转弯区间：点下标区间 + 累计转角 + 方向（+1 左 / -1 右）。 */
    private static final class Run {
        int s;
        int e;
        double cum;
        int sign;
    }

    private BendDetector() {
    }

    public static int count(List<TrackCalculator.Pt> pts) {
        int n = pts == null ? 0 : pts.size();
        if (n < 4) {
            return 0;
        }

        // ---- 1~4) 逐段航向 → 平滑 → 标记转弯点 → 合并同向 run ----
        // ★ 与 explain() 共用 buildRuns()：诊断输出的 run 编号必须和实际计数的 run
        //   是同一批，否则「R3 被拒」这类信息会指向错误的 run，等于白诊断。
        List<Run> runs = buildRuns(pts);

        // ---- 5) 角度 + 速度 + 长度过滤，再按时间邻近合并同向区间 ----
        int cnt = 0;
        int lastEnd = Integer.MIN_VALUE;
        int lastSign = 0;
        for (Run r : runs) {
            if (r.cum < RideConfig.bendCumMin() || r.cum > RideConfig.bendCumCap()) {
                continue;
            }
            if (r.e - r.s + 1 < RideConfig.BEND_RUN_MIN) {
                continue;
            }
            double maxV = 0;
            for (int j = r.s; j <= r.e && j < n; j++) {
                if (pts.get(j).v > maxV) {
                    maxV = pts.get(j).v;
                }
            }
            if (maxV > RideConfig.bendVMax()) {
                continue;
            }
            if (r.sign == lastSign && r.s - lastEnd <= RideConfig.BEND_MERGE_GAP) {
                lastEnd = r.e;
                continue;
            }
            cnt++;
            lastEnd = r.e;
            lastSign = r.sign;
        }
        return (int) Math.round(cnt * RideConfig.bendCoef());
    }

    // ==================== 诊断（移植自 iOS ZeehoRideFill.js 的 debugPrint） ====================

    /**
     * 逐个 run 打印「算出来是多少 / 为什么被拒」，只在 {@link RideConfig#debugBend()} 打开时跑。
     *
     * <p><b>为什么需要</b>：压弯偏多或偏少时，真正想知道的是「哪些候选弯被哪道闸挡掉了」。
     * 只看最终数字（616 vs 985）没法定位，必须看到每个 run 的 cum / maxV 和拒绝原因。
     * iOS 侧靠 {@code C.DEBUG} + {@code debugPrint()} + {@code TARGET_RIDE_IDS} 做这件事，
     * Java 侧此前只能靠把轨迹导出到电脑上跑 {@code tools/ride/calibrate_bend.py}，
     * 车里想看一眼就得先导出、再 pull、再跑脚本。本方法把这一步搬到设备上。</p>
     *
     * <p><b>开销</b>：与 {@link #count} 共用前半段（算一遍 hdg / sh / runs），
     * 只在 DEBUG 打开时多跑一次过滤循环。每趟一趟，日志一行，可接受。</p>
     *
     * @param rideId 日志里标识用（可空）
     * @return 形如 {@code cnt=3 runs=R0[..]cum=..sgn=..REJ:cum<min(12.0) | R1[..]cum=..OK}
     */
    static String explain(List<TrackCalculator.Pt> pts, String rideId) {
        if (pts == null || pts.size() < 4) {
            return "pts<4";
        }
        int n = pts.size();
        List<Run> runs = buildRuns(pts);
        double cumMin = RideConfig.bendCumMin();
        double cumCap = RideConfig.bendCumCap();
        double vMax = RideConfig.bendVMax();
        int runMin = RideConfig.BEND_RUN_MIN;
        int mergeGap = RideConfig.BEND_MERGE_GAP;

        int cnt = 0;
        int lastEnd = Integer.MIN_VALUE;
        int lastSign = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            double maxV = 0;
            for (int j = r.s; j <= r.e && j < n; j++) {
                if (pts.get(j).v > maxV) {
                    maxV = pts.get(j).v;
                }
            }
            String reason = null;
            if (r.cum < cumMin) {
                reason = String.format(java.util.Locale.ROOT, "cum<min(%.1f)", r.cum);
            } else if (r.cum > cumCap) {
                reason = String.format(java.util.Locale.ROOT, "cum>cap(%.1f)", r.cum);
            } else if (r.e - r.s + 1 < runMin) {
                reason = String.format(java.util.Locale.ROOT, "short(%dpt)", r.e - r.s + 1);
            } else if (maxV > vMax) {
                reason = String.format(java.util.Locale.ROOT, "speed>max(%.0f)", maxV);
            } else if (r.sign == lastSign && r.s - lastEnd <= mergeGap) {
                reason = String.format(java.util.Locale.ROOT, "merged(same sign, gap=%d)",
                        r.s - lastEnd);
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append('R').append(i).append('[').append(r.s).append('-').append(r.e)
                    .append("]cum=").append(String.format(java.util.Locale.ROOT, "%.1f", r.cum))
                    .append("sgn=").append(r.sign)
                    .append("maxV=").append(String.format(java.util.Locale.ROOT, "%.0f", maxV))
                    .append(reason == null ? "OK" : "REJ:" + reason);
            if (reason == null) {
                cnt++;
                lastEnd = r.e;
                lastSign = r.sign;
            }
        }
        if (sb.length() > 900) {          // 单趟轨迹可能几十个 run，截断免得日志爆掉
            sb.setLength(900);
            sb.append("...");
        }
        return "cnt=" + (int) Math.round(cnt * RideConfig.bendCoef())
                + " pts=" + n + " runs=" + runs.size()
                + (sb.length() == 0 ? "" : " [" + sb + "]")
                + (rideId == null ? "" : (" ride=" + rideId));
    }

    /**
     * 只跑到「合并出同向 run」为止 —— {@link #count} 与 {@link #explain} 共用的前半段。
     *
     * <p>抽出来是因为两边必须用<b>同一份</b> run 集合：否则诊断输出的 run 编号
     * 和实际计数的 run 对不上，就白诊断了。</p>
     */
    private static List<Run> buildRuns(List<TrackCalculator.Pt> pts) {
        int n = pts.size();
        double[] hdg = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            TrackCalculator.Pt a = pts.get(i);
            TrackCalculator.Pt b = pts.get(i + 1);
            double d = TrackCalculator.haversine(a.lon, a.lat, b.lon, b.lat);
            double tt = b.t - a.t;
            hdg[i] = (d < RideConfig.BEND_SEG_DIST_MIN || tt > RideConfig.BEND_GAP_MS_MAX || tt <= 0)
                    ? Double.NaN
                    : TrackCalculator.bearing(a.lon, a.lat, b.lon, b.lat);
        }
        double[] sh = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            if (Double.isNaN(hdg[i])) {
                sh[i] = Double.NaN;
                continue;
            }
            Double prev = (i > 0 && !Double.isNaN(hdg[i - 1])) ? hdg[i - 1] : null;
            Double next = (i < n - 2 && !Double.isNaN(hdg[i + 1])) ? hdg[i + 1] : null;
            if (prev != null && next != null) {
                sh[i] = TrackCalculator.mid(TrackCalculator.mid(prev, hdg[i]), next);
            } else if (prev != null) {
                sh[i] = TrackCalculator.mid(prev, hdg[i]);
            } else if (next != null) {
                sh[i] = TrackCalculator.mid(hdg[i], next);
            } else {
                sh[i] = hdg[i];
            }
        }
        boolean[] turn = new boolean[n];
        double[] dhdg = new double[n];
        int[] sgn = new int[n];
        for (int i = 1; i < n - 1; i++) {
            if (Double.isNaN(sh[i - 1]) || Double.isNaN(sh[i])) {
                continue;
            }
            double d = TrackCalculator.angDiff(sh[i - 1], sh[i]);
            dhdg[i] = d;
            double vIn = Math.max(pts.get(i - 1).v, pts.get(i).v);
            if (vIn < RideConfig.bendVMin() || vIn > RideConfig.bendVMax()) {
                continue;
            }
            if (Math.abs(d) >= RideConfig.BEND_STEP_MAX) {
                continue;
            }
            if (Math.abs(d) >= RideConfig.bendSeed()) {
                turn[i] = true;
                sgn[i] = d > 0 ? 1 : -1;
            }
        }
        List<Run> runs = new ArrayList<>();
        Run cur = null;
        int gap = 0;
        for (int i = 1; i < n - 1; i++) {
            if (turn[i]) {
                if (cur == null) {
                    cur = new Run();
                    cur.s = i;
                    cur.sign = sgn[i];
                    cur.cum = Math.abs(dhdg[i]);
                } else if (sgn[i] == cur.sign) {
                    cur.cum += Math.abs(dhdg[i]);
                } else {
                    runs.add(cur);
                    cur = new Run();
                    cur.s = i;
                    cur.sign = sgn[i];
                    cur.cum = Math.abs(dhdg[i]);
                }
                cur.e = i;
                gap = 0;
            } else if (cur != null && gap < RideConfig.BEND_ALLOW_GAP) {
                gap++;
                cur.e = i;
            } else if (cur != null) {
                runs.add(cur);
                cur = null;
            }
        }
        if (cur != null) {
            runs.add(cur);
        }
        return runs;
    }
}
