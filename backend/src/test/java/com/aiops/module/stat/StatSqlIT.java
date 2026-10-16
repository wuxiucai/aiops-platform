package com.aiops.module.stat;

import com.aiops.common.BizException;
import com.aiops.module.monitor.service.DashboardStatService;
import com.aiops.module.stat.service.StatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大盘 / 效果评估统计 SQL 在真实 MySQL 上的口径测试。
 * <p>
 * 每个用例前清空涉及的业务表、写入一组已知数据，断言各聚合的精确值；
 * 时间一律相对 NOW() 构造，避免依赖执行时刻。只在显式提供测试库时运行：
 * <pre>mvn test -Dit.db.url="jdbc:mysql://localhost:3306/aiops_it?...&allowPublicKeyRetrieval=true"</pre>
 */
@EnabledIfSystemProperty(named = "it.db.url", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${it.db.url}",
        "aiops.scheduling.enabled=false"
})
class StatSqlIT {

    /** 本测试写入的表；监控对象 id 用 9xx 段，不碰种子里的 1~3 */
    private static final String[] TABLES = {
            "alert_record", "alert_incident", "log_anomaly", "llm_call_log", "log_template",
            "metric_data", "monitor_agent"
    };

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DashboardStatService dashboardStat;
    @Autowired
    StatService stat;

    /** 种子数据（V2）里已有的监控对象数，对象构成类断言在此基础上加本测试写入的数量 */
    private long baseHosts;
    private long baseServices;

    @BeforeEach
    void seed() {
        // 用例会清空业务表：库名必须以 _it 结尾，防止误连开发 / 生产库
        String db = jdbc.queryForObject("SELECT DATABASE()", String.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(db != null && db.endsWith("_it"), "非测试库，拒绝执行：" + db);
        clean();
        baseHosts = jdbc.queryForObject("SELECT COUNT(*) FROM monitor_target WHERE deleted=0 AND target_type='host'", Long.class);
        baseServices = jdbc.queryForObject("SELECT COUNT(*) FROM monitor_target WHERE deleted=0 AND target_type='service'", Long.class);
        // 监控对象：901/902 主机，903 服务，904 已删除主机（不应计入）
        jdbc.update("INSERT INTO monitor_target(id,name,target_type,status,deleted) VALUES "
                + "(901,'it-h1','host',1,0),(902,'it-h2','host',1,0),(903,'it-svc','service',1,0),(904,'it-gone','host',1,1)");
        // agent：901 心跳 30s 前（在线），902 心跳 10min 前（离线），904 在线但对象已删
        jdbc.update("INSERT INTO monitor_agent(target_id,agent_key,status,last_heartbeat,deleted) VALUES "
                + "(901,'it-k1',1,NOW() - INTERVAL 30 SECOND,0),"
                + "(902,'it-k2',1,NOW() - INTERVAL 10 MINUTE,0),"
                + "(904,'it-k4',1,NOW() - INTERVAL 10 SECOND,0)");

        // 告警：近 1 天 4 条（pending 2 / processing 1 / resolved 1），8 天前 1 条（7 天窗口外）
        alert(901, "CPU 高", "CRITICAL", "pending", 5, "NOW() - INTERVAL 1 HOUR", null, null, null);
        alert(901, "CPU 高", "CRITICAL", "pending", 3, "NOW() - INTERVAL 2 HOUR", null, null, null);
        alert(902, "内存高", "WARN", "processing", 1, "NOW() - INTERVAL 3 HOUR",
                "NOW() - INTERVAL 170 MINUTE", null, null);              // 10 分钟后认领
        alert(902, "内存高", "WARN", "resolved", 2, "NOW() - INTERVAL 5 HOUR",
                "NOW() - INTERVAL 280 MINUTE", "NOW() - INTERVAL 4 HOUR", "误报：阈值过低");  // 20 分钟认领，60 分钟解决
        alert(901, "旧告警", "WARN", "resolved", 1, "NOW() - INTERVAL 8 DAY", null, null, null);

        // 事件：open 2（主对象 901 / 902），resolved 1（持续 600s）
        jdbc.update("INSERT INTO alert_incident(incident_no,title,level,status,start_time,primary_target_id,alert_count,duration_sec,create_time,deleted) VALUES "
                + "('IT-1','e1','CRITICAL','open',NOW() - INTERVAL 1 HOUR,901,2,NULL,NOW() - INTERVAL 1 HOUR,0),"
                + "('IT-2','e2','WARN','open',NOW() - INTERVAL 2 HOUR,902,1,NULL,NOW() - INTERVAL 2 HOUR,0),"
                + "('IT-3','e3','WARN','resolved',NOW() - INTERVAL 3 HOUR,902,3,600,NOW() - INTERVAL 3 HOUR,0)");

        // 日志异常：近 24h 2 条（datasource 1、3 各一），30h 前 1 条
        jdbc.update("INSERT INTO log_anomaly(datasource_id,anomaly_type,title,status,create_time) VALUES "
                + "(1,'spike','a1','pending',NOW() - INTERVAL 1 HOUR),"
                + "(3,'spike','a2','pending',NOW() - INTERVAL 2 HOUR),"
                + "(1,'spike','a3','pending',NOW() - INTERVAL 30 HOUR)");

        // LLM 调用：近 24h 3 条（success 2 / fail 1），3 天前 1 条
        jdbc.update("INSERT INTO llm_call_log(provider_id,scene_code,prompt_tokens,completion_tokens,total_tokens,latency_ms,status,create_time) VALUES "
                + "(1,'chat',100,50,150,1200,'success',NOW() - INTERVAL 1 HOUR),"
                + "(1,'chat',200,100,300,800,'success',NOW() - INTERVAL 2 HOUR),"
                + "(2,'nl2dsl',10,0,10,5000,'fail',NOW() - INTERVAL 3 HOUR),"
                + "(1,'chat',1,1,2,100,'success',NOW() - INTERVAL 3 DAY)");

        // 日志模板：svc-a 两个（30、10 次），svc-b 一个（20 次），无 service 一个（5 次）
        jdbc.update("INSERT INTO log_template(datasource_id,index_config_id,template_text,template_hash,total_count,service,level) VALUES "
                + "(1,1,'t-a1','h1',30,'svc-a','INFO'),(1,1,'t-a2','h2',10,'svc-a','WARN'),"
                + "(1,1,'t-b1','h3',20,'svc-b','ERROR'),(1,1,'t-x','h4',5,NULL,'INFO')");

        // 指标：901 最新 cpu 92（旧值 50 不算），902 最新 cpu 40；mem 901=70、902=88；904 已删不算；
        // 1h 均值：cpu (50+92+40)/3，mem (70+88)/2
        jdbc.update("INSERT INTO metric_data(target_id,metric_key,metric_value,collect_time) VALUES "
                + "(901,'cpu.usage',50,NOW() - INTERVAL 5 MINUTE),(901,'cpu.usage',92,NOW() - INTERVAL 1 MINUTE),"
                + "(902,'cpu.usage',40,NOW() - INTERVAL 2 MINUTE),"
                + "(901,'mem.usage',70,NOW() - INTERVAL 1 MINUTE),(902,'mem.usage',88,NOW() - INTERVAL 1 MINUTE),"
                + "(904,'cpu.usage',99,NOW() - INTERVAL 1 MINUTE),"
                + "(902,'cpu.usage',95,NOW() - INTERVAL 30 MINUTE)");   // 15min 窗口外，不参与"最新"
    }

    @AfterEach
    void clean() {
        String db = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (db == null || !db.endsWith("_it")) {
            return;
        }
        for (String t : TABLES) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("DELETE FROM monitor_target WHERE id BETWEEN 900 AND 999");
    }

    /* ================= DashboardStatService ================= */

    @Test
    void fullCount_各口径与对象过滤() {
        assertEquals(2L, count("alert_pending_count", null, 24));
        assertEquals(2L, count("alert_pending_count", 901L, 24));
        assertEquals(0L, count("alert_pending_count", 902L, 24));
        assertEquals(2L, count("incident_open_count", null, 24));
        assertEquals(1L, count("incident_open_count", 902L, 24));
        assertEquals(2L, count("log_anomaly_24h", null, 24));
        assertEquals(3L, count("log_anomaly_24h", null, 48));
        assertEquals(3L, count("llm_call_24h", null, 24));
        assertThrows(BizException.class, () -> dashboardStat.fullCount("nope", null, 24));
    }

    @Test
    void topAlert_按标题聚合触发次数() {
        List<Map<String, Object>> rows = dashboardStat.topAlert(5, 24, null);
        assertEquals(List.of("CPU 高", "内存高"), titles(rows));
        assertEquals(8L, num(rows.get(0).get("cnt")));            // 5 + 3
        assertEquals(3L, num(rows.get(1).get("cnt")));            // 1 + 2
        assertEquals("CRITICAL", rows.get(0).get("level"));

        assertEquals(List.of("内存高"), titles(dashboardStat.topAlert(5, 24, 902L)));
        assertEquals(1, dashboardStat.topAlert(1, 24, null).size());
        // 8 天前的旧告警只在足够长的窗口内出现
        assertTrue(titles(dashboardStat.topAlert(10, 24 * 9, null)).contains("旧告警"));
    }

    @Test
    void topTemplate与服务列表() {
        List<Map<String, Object>> all = dashboardStat.topTemplate(10, null);
        assertEquals(List.of("t-a1", "t-b1", "t-a2", "t-x"),
                all.stream().map(r -> (String) r.get("template")).toList());
        assertEquals(List.of("t-a1", "t-a2"),
                dashboardStat.topTemplate(10, "svc-a").stream().map(r -> (String) r.get("template")).toList());
        assertEquals(List.of("svc-a", "svc-b"), dashboardStat.logServices());
    }

    @Test
    void incidentPage_分页与过滤() {
        Map<String, Object> p1 = dashboardStat.incidentPage(1, 1, "open", null);
        assertEquals(2L, num(p1.get("total")));
        assertEquals("IT-1", ((List<Map<String, Object>>) p1.get("records")).get(0).get("incident_no"));  // start_time 倒序
        Map<String, Object> p2 = dashboardStat.incidentPage(2, 1, "open", null);
        assertEquals("IT-2", ((List<Map<String, Object>>) p2.get("records")).get(0).get("incident_no"));
        assertEquals(1L, num(dashboardStat.incidentPage(1, 10, "open", 902L).get("total")));
        assertEquals(1L, num(dashboardStat.incidentPage(1, 10, "resolved", null).get("total")));
    }

    @Test
    void overview_对象构成_在线_高负载_均值() {
        Map<String, Object> o = dashboardStat.overview();
        assertEquals(baseHosts + 2, o.get("hostCount"));          // 904 已删除不计
        assertEquals(baseServices + 1, o.get("serviceCount"));
        assertEquals(1L, o.get("agentOnline"));                   // 只有 901；904 对象已删
        assertEquals(baseHosts + baseServices + 3 - 1, o.get("agentOffline"));
        assertEquals(2L, o.get("pendingAlerts"));
        assertEquals(1L, o.get("criticalAlertTargets"));
        assertEquals(4L, o.get("alert24h"));
        assertEquals(2L, o.get("openIncidents"));
        assertEquals(1, o.get("highcpuCount"));                   // 901 最新 92 ≥ 80；902 的 95 在 15min 外
        assertEquals("it-h1", o.get("topcpuName"));
        assertEquals(92.0, o.get("topcpuValue"));
        assertEquals(1, o.get("highmemCount"));                   // 902 的 88 ≥ 85
        assertEquals("it-h2", o.get("topmemName"));
        assertEquals(round1((50 + 92 + 40 + 95) / 4.0), o.get("avgCpu"));   // 1h 内 host 的全部样本，904 已删除
        assertEquals(round1((70 + 88) / 2.0), o.get("avgMem"));
    }

    @Test
    void healthMatrix_逐对象() {
        List<Map<String, Object>> rows = dashboardStat.healthMatrix();
        assertEquals(baseHosts + baseServices + 3, rows.size());  // 每个未删除对象一行，904 不出现
        Map<Long, Map<String, Object>> byId = rows.stream()
                .filter(r -> num(r.get("targetId")) >= 900)
                .collect(Collectors.toMap(r -> num(r.get("targetId")), r -> r));
        assertEquals(java.util.Set.of(901L, 902L, 903L), byId.keySet());
        Map<String, Object> h1 = byId.get(901L);
        assertEquals(92.0, h1.get("cpu"));
        assertEquals(70.0, h1.get("mem"));
        assertEquals(2L, h1.get("pendingAlerts"));
        assertEquals(2L, h1.get("criticalAlerts"));
        assertEquals(true, h1.get("agentOnline"));
        Map<String, Object> h2 = byId.get(902L);
        assertEquals(40.0, h2.get("cpu"));
        assertEquals(0L, h2.get("pendingAlerts"));                // processing 不算 pending
        assertEquals(false, h2.get("agentOnline"));
        Map<String, Object> svc = byId.get(903L);
        assertNull(svc.get("cpu"));
        assertEquals(false, svc.get("agentOnline"));
    }

    /* ================= StatService ================= */

    @Test
    void effect_告警与事件指标() {
        Map<String, Object> r = stat.effect(7);
        Map<String, Object> a = (Map<String, Object>) r.get("alert");
        assertEquals(4L, num(a.get("total")));                     // 8 天前那条在窗口外
        assertEquals(2L, num(a.get("pending")));
        assertEquals(1L, num(a.get("claimed")));                   // 认领后状态是 processing
        assertEquals(1L, num(a.get("resolved")));
        assertEquals(1L, num(a.get("falsePositives")));
        assertEquals(15 * 60.0, dbl(a.get("mtta_sec")), 1.0);     // (10min + 20min) / 2
        assertEquals(60 * 60.0, dbl(a.get("mttr_alert_sec")), 1.0);
        assertEquals(0.25, (Double) r.get("falsePositiveRate"), 1e-9);

        Map<String, Long> byLevel = ((List<Map<String, Object>>) r.get("alertByLevel")).stream()
                .collect(Collectors.toMap(m -> (String) m.get("level"), m -> num(m.get("cnt"))));
        assertEquals(Map.of("CRITICAL", 2L, "WARN", 2L), byLevel);

        Map<String, Object> inc = (Map<String, Object>) r.get("incident");
        assertEquals(3L, num(inc.get("total")));
        assertEquals(2L, num(inc.get("open")));
        assertEquals(1L, num(inc.get("resolved")));
        assertEquals(600.0, dbl(inc.get("mttr_sec")), 0.01);
        assertEquals(2.0, dbl(inc.get("avg_alert_per_incident")), 0.01);

        Map<String, Object> tpl = (Map<String, Object>) r.get("template");
        assertEquals(4L, num(tpl.get("tpl_count")));
        assertEquals(65L, num(tpl.get("hit_total")));
        assertEquals(4, ((List<?>) r.get("topTemplates")).size());
    }

    @Test
    void effect_标记误报不填备注也计入误报() {
        alert(903, "误报样例", "WARN", "false_positive", 1, "NOW() - INTERVAL 30 MINUTE", null, "NOW()", null);
        Map<String, Object> r = stat.effect(7);
        Map<String, Object> a = (Map<String, Object>) r.get("alert");
        assertEquals(2L, num(a.get("falsePositives")));
        assertEquals(2.0 / 5, (Double) r.get("falsePositiveRate"), 1e-9);
    }

    @Test
    void llmCost_成本与成功率() {
        Map<String, Object> r = stat.llmCost(1);
        Map<String, Object> o = (Map<String, Object>) r.get("overview");
        assertEquals(3L, num(o.get("total_calls")));
        assertEquals(2L, num(o.get("ok_calls")));
        assertEquals(1L, num(o.get("failed_calls")));
        assertEquals(460L, num(o.get("total_tokens")));
        assertEquals(5000L, num(o.get("max_latency_ms")));

        List<Map<String, Object>> byScene = (List<Map<String, Object>>) r.get("byScene");
        assertEquals("chat", byScene.get(0).get("scene_code"));   // 按 token 倒序
        assertEquals(450L, num(byScene.get(0).get("total_tokens")));

        List<Map<String, Object>> slow = (List<Map<String, Object>>) r.get("slowTop10");
        assertEquals(5000L, num(slow.get(0).get("latency_ms")));

        assertEquals(4L, num(((Map<String, Object>) stat.llmCost(7).get("overview")).get("total_calls")));
    }

    /* ================= helpers ================= */

    private void alert(long targetId, String title, String level, String status, int triggerCount,
                       String createExpr, String claimedExpr, String resolvedExpr, String remark) {
        jdbc.update("INSERT INTO alert_record(rule_id,target_id,metric_key,level,title,status,dedup_key,trigger_count,"
                        + "first_trigger_time,last_trigger_time,claimed_time,resolved_time,handle_remark,create_time) "
                        + "VALUES (1,?,'cpu.usage',?,?,?,?,?," + createExpr + "," + createExpr + ","
                        + (claimedExpr == null ? "NULL" : claimedExpr) + ","
                        + (resolvedExpr == null ? "NULL" : resolvedExpr) + ",?," + createExpr + ")",
                targetId, level, title, status, "it_" + title + "_" + createExpr, triggerCount, remark);
    }

    private long count(String kind, Long targetId, int hours) {
        return num(dashboardStat.fullCount(kind, targetId, hours).get("value"));
    }

    private static List<String> titles(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (String) r.get("title")).toList();
    }

    private static long num(Object v) {
        return ((Number) v).longValue();
    }

    private static double dbl(Object v) {
        return ((Number) v).doubleValue();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
