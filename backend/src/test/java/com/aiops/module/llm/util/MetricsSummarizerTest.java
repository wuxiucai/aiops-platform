package com.aiops.module.llm.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5-3：MetricsSummarizer 单测。
 * 输入 1440 点（每分钟一个，共 24h），输出 ≤ 500 字符，含 min/max/avg/p95/斜率。
 */
class MetricsSummarizerTest {

    @Test
    void summarize1440Points() {
        List<MetricsSummarizer.Point> pts = new ArrayList<>();
        long t0 = System.currentTimeMillis() - 1440L * 60_000;
        for (int i = 0; i < 1440; i++) {
            // 轻微上倾的随机数
            double v = 20.0 + i * 0.01 + (i % 60 == 0 ? 5.0 : 0);
            pts.add(new MetricsSummarizer.Point(t0 + i * 60_000L, v));
        }
        String s = MetricsSummarizer.summarize("cpu.usage", pts);
        assertTrue(s.length() <= 500, "must fit 500 chars: " + s.length());
        assertTrue(s.contains("cpu.usage"));
        assertTrue(s.contains("min="), "missing min");
        assertTrue(s.contains("max="), "missing max");
        assertTrue(s.contains("avg="), "missing avg");
        assertTrue(s.contains("p95="), "missing p95");
        assertTrue(s.contains("斜率"), "missing slope");
        assertTrue(s.contains("min"), "duration label min expected");
        assertTrue(s.contains("趋势:"), "missing trend");
        System.out.println("Sample summary: " + s);
    }

    @Test
    void summarizeSmallSeries() {
        List<MetricsSummarizer.Point> pts = List.of(
                new MetricsSummarizer.Point(0, 10),
                new MetricsSummarizer.Point(60_000, 20),
                new MetricsSummarizer.Point(120_000, 30)
        );
        String s = MetricsSummarizer.summarize("jvm.heap.usage", pts);
        assertTrue(s.contains("jvm.heap.usage"));
        assertTrue(s.contains("3点"));
        assertTrue(s.length() <= 500);
    }

    @Test
    void summarizeEmpty() {
        String s = MetricsSummarizer.summarize("cpu.usage", List.of());
        assertEquals("指标 cpu.usage：无数据", s);
    }

    @Test
    void summarizeDetectsSpike() {
        List<MetricsSummarizer.Point> pts = new ArrayList<>();
        long t0 = 0;
        for (int i = 0; i < 200; i++) pts.add(new MetricsSummarizer.Point(t0 + i * 1000L, 10));
        pts.add(new MetricsSummarizer.Point(t0 + 200_000, 999)); // 突变
        String s = MetricsSummarizer.summarize("cpu.usage", pts);
        assertTrue(s.contains("突变点"), "must detect spike");
    }

    @Test
    void summarizeTrendUp() {
        List<MetricsSummarizer.Point> pts = new ArrayList<>();
        for (int i = 0; i < 90; i++) pts.add(new MetricsSummarizer.Point(i * 1000L, 10 + i));
        String s = MetricsSummarizer.summarize("mem.usage", pts);
        assertTrue(s.contains("上升"), "expected trend=上升: " + s);
    }

    @Test
    void summarizeTrendDown() {
        List<MetricsSummarizer.Point> pts = new ArrayList<>();
        for (int i = 0; i < 90; i++) pts.add(new MetricsSummarizer.Point(i * 1000L, 100 - i));
        String s = MetricsSummarizer.summarize("cpu.usage", pts);
        assertTrue(s.contains("下降"), "expected trend=下降: " + s);
    }
}
