package com.aiops.module.log.service;

import com.aiops.common.BizException;
import com.aiops.datasource.log.EsLogClient;
import com.aiops.datasource.log.EsQueryBuilder;
import com.aiops.module.esa.entity.EsDatasource;
import com.aiops.module.esa.entity.EsIndexConfig;
import com.aiops.module.esa.mapper.EsDatasourceMapper;
import com.aiops.module.esa.mapper.EsIndexConfigMapper;
import com.aiops.module.log.drain.DrainParseResult;
import com.aiops.module.log.drain.DrainService;
import com.aiops.module.log.entity.LogAnomaly;
import com.aiops.module.log.entity.LogDetectRule;
import com.aiops.module.log.entity.LogTemplate;
import com.aiops.module.log.entity.LogTemplateStat;
import com.aiops.module.log.mapper.LogAnomalyMapper;
import com.aiops.module.log.mapper.LogDetectRuleMapper;
import com.aiops.module.log.mapper.LogTemplateMapper;
import com.aiops.module.log.mapper.LogTemplateStatMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日志模板提取 Job + 日志异常检测 Job 的共用业务实现。
 * <p>
 * LogTemplateJob：每 10min 一次。
 *   1. 从 ES 拉过去 10min 内 service ∈ 训练域的日志
 *   2. 逐条 DrainParser.addLogMessage → template_hash
 *   3. UPSERT log_template（uk_hash=datasource_id+template_hash），累计 total_count
 *   4. 写 log_template_stat（window_count）
 *   5. 检测到"新模板"时触发 log_anomaly (new_template)
 * <p>
 * LogDetectJob：每 10min 一次（与 TemplateJob 顺序执行）。
 *   对所有 enabled 的 log_detect_rule，按 rule_type 走 4 种检测（基于 log_template_stat 滑动窗口）：
 *   new_template / rare_template / spike / error_rate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogTemplateJobService {

    /** Drain 训练域（论文红线条目：只处理这 3 个服务） */
    public static final Set<String> TRAIN_SERVICES = Set.of(
            "order-service", "payment-service", "aiops-platform");

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter HH = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EsDatasourceMapper esDatasourceMapper;
    private final EsIndexConfigMapper esIndexConfigMapper;
    private final EsLogClient esLogClient;
    private final DrainService drainService;
    private final LogTemplateMapper logTemplateMapper;
    private final LogTemplateStatMapper logTemplateStatMapper;
    private final LogDetectRuleMapper logDetectRuleMapper;
    private final LogAnomalyMapper logAnomalyMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 跑一轮模板提取。
     * @param windowMinutes 回看窗口（分钟）；通常 = 10（与 Job 周期一致）
     * @return 摘要
     */
    @Transactional
    public Map<String, Object> runTemplateJob(int windowMinutes) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusMinutes(windowMinutes);
        LocalDateTime end = now;

        EsDatasource ds = esDatasourceMapper.selectById(1L);
        EsIndexConfig idx = esIndexConfigMapper.selectById(1L);
        if (ds == null || idx == null) {
            throw new BizException("ES 数据源/索引未配置");
        }

        // 1. 拉日志（service∈训练域，2 小时防漏）
        List<Map<String, Object>> rawLogs = fetchLogs(ds, idx, start, end, 5000);
        log.info("[LogTemplateJob] 拉取窗口 logs={}, window={}min", rawLogs.size(), windowMinutes);

        int newClusters = 0;
        int upserts = 0;
        long totalCountDelta = 0;
        Map<String, Integer> templateWindow = new HashMap<>();  // hash -> 本窗口计数
        Map<Long, Integer> templateWindowById = new HashMap<>(); // templateId -> 计数
        Map<String, LogTemplate> hashCache = new HashMap<>();
        Set<String> serviceSeen = new HashSet<>();

        LocalDateTime validateStart = now.minusMinutes(windowMinutes);
        for (Map<String, Object> log1 : rawLogs) {
            String service = (String) log1.get("service");
            if (service == null || !TRAIN_SERVICES.contains(service)) {
                continue;
            }
            serviceSeen.add(service);
            String level = (String) log1.get("level");
            String message = (String) log1.get("message");
            String traceId = (String) log1.get("traceId");
            if (message == null || message.isBlank()) {
                continue;
            }

            // 2. Drain
            DrainParseResult r = drainService.parse(1L, 1L, message);
            String templateHash = sha1Of(r.getTemplateText());
            LogTemplate t = hashCache.computeIfAbsent(templateHash, h ->
                    logTemplateMapper.selectOne(new LambdaQueryWrapper<LogTemplate>()
                            .eq(LogTemplate::getDatasourceId, 1L)
                            .eq(LogTemplate::getTemplateHash, h)
                            .last("LIMIT 1")));
            LocalDateTime lineTime = parseTime((String) log1.get("time"));

            if (t == null) {
                // 3a. 新模板 → insert
                t = new LogTemplate();
                t.setDatasourceId(1L);
                t.setIndexConfigId(1L);
                t.setClusterId((int) r.getClusterId());
                t.setTemplateText(r.getTemplateText());
                t.setTokenCount(r.getTokenCount());
                t.setTemplateHash(templateHash);
                t.setFirstSeen(lineTime);
                t.setLastSeen(lineTime);
                t.setTotalCount(1L);
                t.setLastWindowCount(1);
                t.setSampleLog(message);
                t.setVariables(r.getParameters() == null ? "[]"
                        : toJson(r.getParameters()));
                t.setLevel(level);
                t.setService(service);
                t.setStatus(0);
                logTemplateMapper.insert(t);
                hashCache.put(templateHash, t);
                newClusters++;
                upserts++;
                // 新增模板告警（如果启用）
                if (lineTime != null && lineTime.isAfter(validateStart)) {
                    createAnomalyIfEnabled("new_template", t,
                            "发现新日志模板：" + (service == null ? "" : service),
                            "level=" + level + "，模板=" + abbreviate(r.getTemplateText(), 120),
                            BigDecimal.ONE, BigDecimal.ZERO);
                }
            } else {
                // 3b. 更新既有
                upserts++;
                LogTemplate upd = new LogTemplate();
                upd.setId(t.getId());
                upd.setLastSeen(lineTime);
                upd.setTotalCount(t.getTotalCount() + 1);
                upd.setSampleLog(message);
                upd.setVariables(toJson(r.getParameters()));
                logTemplateMapper.updateById(upd);
                t.setTotalCount(t.getTotalCount() + 1);
            }
            totalCountDelta++;
            templateWindow.merge(templateHash, 1, Integer::sum);
            templateWindowById.merge(t.getId(), 1, Integer::sum);
        }

        // 4. log_template_stat：每个模板写一行窗口计数
        int statRows = 0;
        for (Map.Entry<Long, Integer> e : templateWindowById.entrySet()) {
            LogTemplateStat s = new LogTemplateStat();
            s.setTemplateId(e.getKey());
            s.setStatTime(end);
            s.setWindowCount(e.getValue());
            logTemplateStatMapper.insert(s);
            statRows++;
            // 顺手更新 log_template.last_window_count
            LogTemplate upd = new LogTemplate();
            upd.setId(e.getKey());
            upd.setLastWindowCount(e.getValue());
            logTemplateMapper.updateById(upd);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("window", windowMinutes + "min");
        out.put("start", start.format(HH));
        out.put("end", end.format(HH));
        out.put("logsFetched", rawLogs.size());
        out.put("logsTrained", serviceSeen.size() + " services");
        out.put("trainDomainHits", serviceSeen);
        out.put("totalCountDelta", totalCountDelta);
        out.put("upserts", upserts);
        out.put("newClusters", newClusters);
        out.put("statRows", statRows);
        log.info("[LogTemplateJob] end newClusters={}, statRows={}, windowLogs={}", newClusters, statRows, rawLogs.size());
        return out;
    }

    /**
     * 异常检测 Job。基于 log_template_stat 滑动窗口对 4 种规则类型判。
     * 在 LogTemplateJob 之后调用，使用同样的窗口。
     */
    @Transactional
    public Map<String, Object> runDetectJob(int windowMinutes) {
        List<LogDetectRule> rules = logDetectRuleMapper.selectList(
                new LambdaQueryWrapper<LogDetectRule>().eq(LogDetectRule::getEnabled, 1));
        Map<String, Integer> summary = new HashMap<>();
        int anomalies = 0;
        for (LogDetectRule rule : rules) {
            int n = switch (rule.getRuleType()) {
                case "new_template" -> detectNewTemplate(rule, windowMinutes);
                case "rare_template" -> detectRareTemplate(rule, windowMinutes);
                case "spike" -> detectSpike(rule, windowMinutes);
                case "error_rate" -> detectErrorRate(rule, windowMinutes);
                default -> 0;
            };
            if (n > 0) {
                anomalies += n;
                summary.merge(rule.getRuleType(), n, Integer::sum);
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("rules", rules.size());
        out.put("anomaliesCreated", anomalies);
        out.put("byType", summary);
        log.info("[LogDetectJob] rules={}, anomalies={}, byType={}", rules.size(), anomalies, summary);
        return out;
    }

    // ---------------- 4 种检测实现 ----------------

    /** 1) new_template：本窗口新建模板数 ≥ threshold（默认 1） → 已直接在 TemplateJob 内触发，这里仅兜底 */
    private int detectNewTemplate(LogDetectRule rule, int winMin) {
        // 已在 TemplateJob 内部每命中 isNewCluster 时调用 createAnomalyIfEnabled("new_template", ...)
        // 这里不做重复检测；返回 0
        return 0;
    }

    /** 2) rare_template：本窗口 count < max_count 且 total_count = 窗口count（说明只此一次） */
    private int detectRareTemplate(LogDetectRule rule, int winMin) {
        JsonNode cfg = parseParams(rule.getParams());
        int maxCount = cfg.path("max_count").asInt(3);
        LocalDateTime since = LocalDateTime.now().minusMinutes(winMin);
        List<LogTemplate> templates = logTemplateMapper.selectList(
                new LambdaQueryWrapper<LogTemplate>()
                        .eq(LogTemplate::getDatasourceId, 1L)
                        .eq(LogTemplate::getStatus, 0)
                        .ge(LogTemplate::getLastSeen, since));
        int hits = 0;
        for (LogTemplate t : templates) {
            if (t.getLastWindowCount() != null && t.getLastWindowCount() <= maxCount
                    && t.getTotalCount() != null && t.getTotalCount() <= maxCount) {
                createAnomalyIfEnabled("rare_template", t, "稀有模板（仅 " + t.getTotalCount() + " 次）",
                        "模板：" + abbreviate(t.getTemplateText(), 120),
                        BigDecimal.valueOf(t.getLastWindowCount()), BigDecimal.valueOf(maxCount));
                hits++;
            }
        }
        return hits;
    }

    /** 3) spike：本窗口 window_count > 过去 6 个窗口均值 × spike_ratio（默认 3） */
    private int detectSpike(LogDetectRule rule, int winMin) {
        JsonNode cfg = parseParams(rule.getParams());
        double ratio = cfg.path("spike_ratio").asDouble(3.0);
        // 取每个模板的最近 7 个窗口
        List<LogTemplate> templates = logTemplateMapper.selectList(
                new LambdaQueryWrapper<LogTemplate>()
                        .eq(LogTemplate::getDatasourceId, 1L)
                        .eq(LogTemplate::getStatus, 0)
                        .orderByDesc(LogTemplate::getLastSeen)
                        .last("LIMIT 100"));
        int hits = 0;
        for (LogTemplate t : templates) {
            List<LogTemplateStat> stats = logTemplateStatMapper.selectList(
                    new LambdaQueryWrapper<LogTemplateStat>()
                            .eq(LogTemplateStat::getTemplateId, t.getId())
                            .orderByDesc(LogTemplateStat::getStatTime)
                            .last("LIMIT 7"));
            if (stats.size() < 4) continue;
            int cur = stats.get(0).getWindowCount();
            long sum = 0;
            for (int i = 1; i < stats.size(); i++) sum += stats.get(i).getWindowCount();
            double avg = sum / (double) (stats.size() - 1);
            if (avg < 1) continue;  // 基线太低不报
            if (cur > avg * ratio) {
                createAnomalyIfEnabled("spike", t, "模板爆量：" + abbreviate(t.getTemplateText(), 80),
                        String.format("本窗口 %d 次，历史均值 %.1f，比率 %.1f", cur, avg, cur / avg),
                        BigDecimal.valueOf(cur), BigDecimal.valueOf(avg));
                hits++;
            }
        }
        return hits;
    }

    /** 4) error_rate：本窗口 ERROR 数 / 总数 > error_ratio（默认 0.3） */
    private int detectErrorRate(LogDetectRule rule, int winMin) {
        JsonNode cfg = parseParams(rule.getParams());
        double threshold = cfg.path("error_ratio").asDouble(0.3);
        EsDatasource ds = esDatasourceMapper.selectById(1L);
        EsIndexConfig idx = esIndexConfigMapper.selectById(1L);
        if (ds == null || idx == null) return 0;
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusMinutes(winMin);
        Map<String, Object> body = new HashMap<>();
        body.put("datasourceId", 1);
        body.put("indexConfigId", 1);
        body.put("startTime", start.format(HH));
        body.put("endTime", end.format(HH));
        List<Map<String, Object>> logs = fetchLogs(ds, idx, start, end, 5000);
        int total = 0, err = 0;
        for (Map<String, Object> l : logs) {
            String svc = (String) l.get("service");
            if (svc == null || !TRAIN_SERVICES.contains(svc)) continue;
            total++;
            if ("ERROR".equalsIgnoreCase((String) l.get("level"))) err++;
        }
        if (total < 20) return 0;
        double ratio = err / (double) total;
        if (ratio > threshold) {
            String title = String.format("错误率飙升：%d/%d (%.0f%%)", err, total, ratio * 100);
            LogAnomaly a = new LogAnomaly();
            a.setRuleId(rule.getId());
            a.setDatasourceId(1L);
            a.setAnomalyType("error_rate");
            a.setTitle(title);
            a.setDescription("error_rate=" + ratio + "，样本量 " + total);
            a.setLevel(rule.getLevel() == null ? "CRITICAL" : rule.getLevel());
            a.setTriggerValue(BigDecimal.valueOf(ratio));
            a.setBaselineValue(BigDecimal.valueOf(threshold));
            a.setStatus("pending");
            a.setFirstTime(LocalDateTime.now());
            a.setLastTime(LocalDateTime.now());
            a.setCount(1);
            logAnomalyMapper.insert(a);
            return 1;
        }
        return 0;
    }

    // ---------------- ES 拉取（复用 EsLogClient） ----------------

    private List<Map<String, Object>> fetchLogs(EsDatasource ds, EsIndexConfig idx,
                                                 LocalDateTime start, LocalDateTime end, int size) {
        EsQueryBuilder.SearchParams p = new EsQueryBuilder.SearchParams(
                idx.getIndexPattern(), idx.getTimeField(), idx.getMessageField(),
                idx.getLevelField(), idx.getServiceField(), idx.getTraceIdField(),
                start, end, null, null, null, null, 1, size, "standard");
        String dsl = EsQueryBuilder.buildSearchDsl(p);
        String resp = esLogClient.postSearch(ds.baseUrl(), idx.getIndexPattern() + "/_search", dsl);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(resp);
            for (JsonNode hit : root.path("hits").path("hits")) {
                JsonNode src = hit.path("_source");
                Map<String, Object> row = new HashMap<>();
                row.put("time", src.path(idx.getTimeField()).asText(null));
                row.put("level", src.path(idx.getLevelField()).asText(null));
                row.put("service", src.path(idx.getServiceField()).asText(null));
                row.put("message", src.path(idx.getMessageField()).asText(null));
                row.put("traceId", src.path(idx.getTraceIdField()).asText(null));
                out.add(row);
            }
        } catch (Exception e) {
            log.error("[LogTemplateJob] ES 响应解析失败: {}", e.getMessage());
            throw new BizException("ES 响应解析失败：" + e.getMessage());
        }
        return out;
    }

    private LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return LocalDateTime.now();
        try {
            // ES @timestamp 是 ISO（含 Z），去掉 Z 解析再补 +8
            String t = s;
            if (t.endsWith("Z")) {
                t = t.substring(0, t.length() - 1);
                return LocalDateTime.parse(t, DT_FMT).plusHours(8);
            }
            return LocalDateTime.parse(t, DT_FMT);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private JsonNode parseParams(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** 模板 hash（sha1 截 40 位 hex） */
    private String sha1Of(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(s.hashCode());
        }
    }

    /**
     * 若有 enabled 的该 type 规则，则插入 log_anomaly（dedup by rule_id+template_id+anomaly_type+last_time>now-10min）
     */
    private void createAnomalyIfEnabled(String type, LogTemplate t, String title, String desc,
                                         BigDecimal trigger, BigDecimal baseline) {
        LogDetectRule rule = logDetectRuleMapper.selectOne(new LambdaQueryWrapper<LogDetectRule>()
                .eq(LogDetectRule::getEnabled, 1)
                .eq(LogDetectRule::getRuleType, type)
                .last("LIMIT 1"));
        if (rule == null) return;
        // 10min 内同模板同类型已报 → 跳过
        LocalDateTime since = LocalDateTime.now().minusMinutes(10);
        Long cnt = logAnomalyMapper.selectCount(new LambdaQueryWrapper<LogAnomaly>()
                .eq(LogAnomaly::getRuleId, rule.getId())
                .eq(LogAnomaly::getTemplateId, t.getId())
                .eq(LogAnomaly::getAnomalyType, type)
                .ge(LogAnomaly::getLastTime, since)
                .ne(LogAnomaly::getStatus, "false_positive"));
        if (cnt != null && cnt > 0) return;
        LogAnomaly a = new LogAnomaly();
        a.setRuleId(rule.getId());
        a.setTemplateId(t.getId());
        a.setDatasourceId(1L);
        a.setAnomalyType(type);
        a.setTitle(title);
        a.setDescription(desc);
        a.setLevel(rule.getLevel() == null ? "WARN" : rule.getLevel());
        a.setTriggerValue(trigger);
        a.setBaselineValue(baseline);
        a.setStatus("pending");
        LocalDateTime now = LocalDateTime.now();
        a.setFirstTime(now);
        a.setLastTime(now);
        a.setCount(1);
        logAnomalyMapper.insert(a);
        log.info("[LogAnomaly] created type={} template={} title={}", type, t.getId(), title);
    }
}
