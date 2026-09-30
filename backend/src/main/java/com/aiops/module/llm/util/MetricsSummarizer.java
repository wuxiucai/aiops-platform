package com.aiops.module.llm.util;

import java.util.List;
import java.util.Locale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * M5-3：MetricsSummarizer
 * <p>
 * 把一段时序数据压缩成 ≤ 500 字符的自然语言摘要，供 LLM prompt 使用。
 * <p>
 * 任务书 v2 §6.3 要求 prompt 里不能塞原始指标点（会爆 token），
 * 必须落一个 metricSummary 的紧凑描述，包含 min/max/avg/p95/斜率/趋势。
 * <p>
 * 用法：
 *   MetricsSummarizer.Point p1 = new Point(ts, val)
 *   String s = MetricsSummarizer.summarize("cpu.usage", points);
 */
public class MetricsSummarizer {

    public static final int MAX_CHARS = 500;

    /** 数据点：时间戳 + 数值 */
    public record Point(long timestampMillis, double value) {
    }

    /**
     * 输入不限长度的时序点，返回 ≤ 500 字符摘要。
     * <p>
     * 摘要格式：
     *   指标 cpu.usage（N 点，_range=10min）：min=12.3, max=45.6, avg=28.1, p95=42.8, latest=32.0
     *   趋势：上升（前段 25.0 → 后段 33.0），斜率 ≈ 0.8%/min；3 个突变点（阈值 3σ）
     */
    public static String summarize(String metricKey, List<Point> points) {
        if (points == null || points.isEmpty()) {
            return "指标 " + metricKey + "：无数据";
        }
        List<Point> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingLong(Point::timestampMillis));

        int n = sorted.size();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        for (Point p : sorted) {
            double v = p.value();
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        double avg = sum / n;
        double p95 = percentile(sorted.stream().map(Point::value).collect(Collectors.toList()), 0.95);
        double latest = sorted.get(n - 1).value();

        long rangeMs = sorted.get(n - 1).timestampMillis() - sorted.get(0).timestampMillis();
        String rangeStr = formatDuration(rangeMs);

        // 趋势：前后 1/3 均值对比
        int third = Math.max(1, n / 3);
        double headAvg = avg(sorted, 0, third);
        double tailAvg = avg(sorted, n - third, n);
        String trend;
        if (tailAvg > headAvg * 1.05) trend = String.format(Locale.ROOT, "上升（前段 %.1f → 后段 %.1f）", headAvg, tailAvg);
        else if (tailAvg < headAvg * 0.95) trend = String.format(Locale.ROOT, "下降（前段 %.1f → 后段 %.1f）", headAvg, tailAvg);
        else trend = String.format(Locale.ROOT, "平稳（前段 %.1f → 后段 %.1f）", headAvg, tailAvg);

        // 斜率：首尾差 / 时长（分钟）
        double slopePerMin = 0;
        if (rangeMs > 0) {
            slopePerMin = (latest - sorted.get(0).value()) / (rangeMs / 60_000.0);
        }

        // 突变点：3σ
        double stddev = stddev(sorted, avg);
        int outliers = 0;
        double sigmaThresh = 3 * stddev;
        for (Point p : sorted) {
            if (Math.abs(p.value() - avg) > sigmaThresh) outliers++;
        }

        String summary = String.format(Locale.ROOT,
                "指标 %s（%d点,%s）：min=%.2f, max=%.2f, avg=%.2f, p95=%.2f, latest=%.2f\n"
                        + "趋势:%s, 斜率 ≈ %.2f/min；突变点 %d（σ 阈值 %.2f）",
                metricKey, n, rangeStr, min, max, avg, p95, latest,
                trend, slopePerMin, outliers, sigmaThresh);
        return summary.length() > MAX_CHARS ? summary.substring(0, MAX_CHARS - 1) : summary;
    }

    /* ================== 辅助 ================== */

    private static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) return 0;
        List<Double> s = values.stream().sorted().collect(Collectors.toList());
        int idx = (int) Math.ceil(p * s.size()) - 1;
        return s.get(Math.max(0, Math.min(idx, s.size() - 1)));
    }

    private static double avg(List<Point> pts, int from, int to) {
        if (to <= from) return 0;
        double sum = 0;
        for (int i = from; i < to; i++) sum += pts.get(i).value();
        return sum / (to - from);
    }

    private static double stddev(List<Point> pts, double mean) {
        if (pts.size() < 2) return 0;
        double var = 0;
        for (Point p : pts) {
            double d = p.value() - mean;
            var += d * d;
        }
        return Math.sqrt(var / pts.size());
    }

    private static String formatDuration(long ms) {
        if (ms < 60_000) return (ms / 1000) + "s";
        if (ms < 3600_000) return (ms / 60_000) + "min";
        return (ms / 3600_000) + "h";
    }
}
