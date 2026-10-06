package com.github.zeehospeedhunter.ride;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * 急刹检测（对应技术文档里的 {@code BrakeDetector.java}）。
 *
 * <p>核心思路：<b>双窗口「先快后慢」扫描</b>。一脚刹车在速度序列上的特征是
 * 「某个窗口内先冲到峰值、随后掉下来」，所以：</p>
 * <ol>
 *   <li><b>大窗口 5 s / Δv ≥ 15</b>：抓「提前刹」—— 匀速巡航后一脚收油；</li>
 *   <li><b>小窗口 3 s / Δv ≥ 20</b>：抓「急刹」—— 峰值到谷值来得很快；</li>
 *   <li>两套窗口都会命中同一脚刹车，所以候选点先按时间排序，再按
 *       {@code BRAKE_MERGE_GAP(5 点)} 合并（保留 Δv 更大的那个），最后乘
 *       {@code BRAKE_CALIB(2.5)} 把重复计数乘回去。</li>
 * </ol>
 *
 * <p>三重闸门（避免把正常收油算成急刹）：</p>
 * <ul>
 *   <li>谷底必须<b>在峰底之后</b>（{@code minIdx > maxIdx}）—— 顺序反了那是「加速」；</li>
 *   <li>历时 ≥ {@code BRAKE_MIN_DUR(1.5 s)} —— 瞬时跳变是 GPS 噪声；</li>
 *   <li>平均减速度 ≥ {@code RideConfig.BRAKE_A_MIN(0.5 m/s²)}（≈0.05 g）。</li>
 * </ul>
 *
 * <p>复杂度 O(n × win)，win 是常数，1000 点 &lt; 5 ms。</p>
 */
public final class BrakeDetector {

    /** 一个急刹候选：峰值所在点下标 + 该次掉速的 Δv（km/h）。 */
    private static final class Cand {
        final int idx;
        final double dv;

        Cand(int idx, double dv) {
            this.idx = idx;
            this.dv = dv;
        }
    }

    private BrakeDetector() {
    }

    public static int count(List<TrackCalculator.Pt> pts) {
        int n = pts == null ? 0 : pts.size();
        if (n < 4) {
            return 0;
        }
        List<Cand> cands = scan(pts, RideConfig.BRAKE_WIN_LARGE, RideConfig.BRAKE_DV_LARGE);
        cands.addAll(scan(pts, RideConfig.BRAKE_WIN_SMALL, RideConfig.BRAKE_DV_SMALL));
        Collections.sort(cands, (a, b) -> Integer.compare(a.idx, b.idx));

        // 合并：同一脚刹车在两个窗口里各留一个（Δv 大的），间隔不足 MERGE_GAP 的算一次
        List<Cand> merged = new ArrayList<>();
        for (Cand c : cands) {
            if (merged.isEmpty()
                    || c.idx - merged.get(merged.size() - 1).idx >= RideConfig.BRAKE_MERGE_GAP) {
                merged.add(c);
            } else if (c.dv > merged.get(merged.size() - 1).dv) {
                merged.set(merged.size() - 1, c);
            }
        }
        long r = Math.round(merged.size() * RideConfig.brakeCalib());
        return r < 0 ? 0 : (int) r;
    }

    /** 单个窗口的扫描：返回「先快后慢」的候选（按出现顺序，未排序）。 */
    private static List<Cand> scan(List<TrackCalculator.Pt> pts, int win, double dvMin) {
        int n = pts.size();
        List<Cand> cands = new ArrayList<>();
        for (int i = 0; i <= n - win; i++) {
            if (pts.get(i).v < RideConfig.BRAKE_V_MIN) {
                continue;   // 起点速度太低，减速不算急刹
            }
            double maxV = Double.NEGATIVE_INFINITY;
            int maxIdx = i;
            double minV = Double.POSITIVE_INFINITY;
            int minIdx = i;
            int upper = Math.min(i + win, n - 1);
            for (int j = i + 1; j <= upper; j++) {
                double v = pts.get(j).v;
                if (v > maxV) {
                    maxV = v;
                    maxIdx = j;
                }
                if (v < minV) {
                    minV = v;
                    minIdx = j;
                }
            }
            double dv = maxV - minV;
            if (dv < dvMin || minIdx <= maxIdx) {
                continue;
            }
            double dt = (pts.get(minIdx).t - pts.get(maxIdx).t) / 1000.0;
            if (dt < RideConfig.BRAKE_MIN_DUR || dv / 3.6 / dt < RideConfig.BRAKE_A_MIN) {
                continue;
            }
            cands.add(new Cand(maxIdx, dv));
        }
        return cands;
    }
}
