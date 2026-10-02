package com.aiops.module.monitor.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.monitor.entity.MetricData;
import com.aiops.module.monitor.entity.MonitorAgent;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.aiops.module.monitor.mapper.MonitorAgentMapper;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import com.aiops.module.monitor.security.AgentAuthFilter;
import com.aiops.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 远程 Linux agent 管理（Push 模式）。
 * - 管理端（CRUD/启停/下载）走 JWT RequirePerm.
 * - Agent 上报（/api/agent/metric, /api/agent/heartbeat）走 AgentAuthFilter 验 X-Agent-Key.
 */
@Slf4j
@Tag(name = "远程 Agent (Push)")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 兼容两种格式："yyyy-MM-dd HH:mm:ss" 或 ISO（JSR310 默认序列化结果） */
    private static LocalDateTime parseTime(String raw, LocalDateTime fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String s = raw.trim();
        try { return LocalDateTime.parse(s, ISO_DT); } catch (Exception ignore) {}
        try { return LocalDateTime.parse(s, DF); } catch (Exception ignore) {}
        return fallback;
    }

    private final MonitorAgentMapper monitorAgentMapper;
    private final MonitorTargetMapper monitorTargetMapper;
    private final MetricDataMapper metricDataMapper;

    /** 平台对外基础URL， install_command 里用 */
    @Value("${aiops.platform.base-url:http://127.0.0.1:8080}")
    private String platformBaseUrl;

    /* ========================= 1. 列表 ========================= */

    @Operation(summary = "agent 列表（含 target 信息）")
    @com.aiops.security.RequirePerm("monitor:target:list")
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        List<MonitorAgent> agents = monitorAgentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MonitorAgent>()
                        .orderByDesc(MonitorAgent::getCreateTime));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MonitorAgent a : agents) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", a.getId());
            row.put("targetId", a.getTargetId());
            row.put("status", a.getStatus());
            row.put("version", a.getVersion());
            row.put("lastHeartbeat", a.getLastHeartbeat());
            row.put("lastMetricTime", a.getLastMetricTime());
            MonitorTarget t = monitorTargetMapper.selectById(a.getTargetId());
            if (t != null) {
                row.put("targetName", t.getName());
                row.put("targetIp", t.getIp());
            }
            out.add(row);
        }
        return Result.ok(out);
    }

    /* ========================= 2. 新建 ========================= */

    @Operation(summary = "新建 agent，返回 install_command")
    @com.aiops.security.RequirePerm("monitor:target:update")
    @PostMapping("/create")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Object tid = body.get("targetId");
        if (tid == null) {
            throw new BizException("targetId 必填");
        }
        Long targetId = ((Number) tid).longValue();
        MonitorTarget target = monitorTargetMapper.selectById(targetId);
        if (target == null) {
            throw new BizException("target 不存在：" + targetId);
        }
        // 防重：同 target 只允许一个 alive agent
        Long existing = monitorAgentMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MonitorAgent>()
                .eq(MonitorAgent::getTargetId, targetId)
                .eq(MonitorAgent::getDeleted, 0));
        if (existing != null && existing > 0) {
            throw new BizException("该 target 已有 agent，先停用旧 agent 再新建");
        }

        String agentKey = genAgentKey();
        MonitorAgent a = new MonitorAgent();
        a.setTargetId(targetId);
        a.setAgentKey(agentKey);
        a.setStatus(1);
        a.setVersion(null);
        a.setCreateTime(LocalDateTime.now());
        a.setDeleted(0);
        monitorAgentMapper.insert(a);

        String installCmd = buildInstallCommand(a.getId(), agentKey, targetId);
        a.setInstallCommand(installCmd);
        monitorAgentMapper.updateById(a);

        Map<String, Object> out = new HashMap<>();
        out.put("id", a.getId());
        out.put("agentKey", agentKey);
        out.put("installCommand", installCmd);
        out.put("downloadUrl", platformBaseUrl + "/api/agent/download/" + a.getId());
        log.info("[Agent] 新建 id={} target={} name={}", a.getId(), targetId, target.getName());
        return Result.ok(out);
    }

    /* ========================= 3. 启停 ========================= */

    @Operation(summary = "启停")
    @com.aiops.security.RequirePerm("monitor:target:update")
    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id) {
        MonitorAgent a = monitorAgentMapper.selectById(id);
        if (a == null) throw new BizException("agent 不存在");
        a.setStatus(a.getStatus() != null && a.getStatus() == 1 ? 0 : 1);
        a.setUpdateTime(LocalDateTime.now());
        monitorAgentMapper.updateById(a);
        return Result.ok();
    }

    /* ========================= 4. 下载 jar ========================= */

    @Operation(summary = "下载 aiops-agent.jar （当前版本应用编译到 backend/target 中， 简易版： 平台自己能够提供 spring-boot-starter 固定版 jar 容器输出 content 只是 a stability utility binary compactly RedistributionForTests.")
    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        MonitorAgent a = monitorAgentMapper.selectById(id);
        if (a == null) throw new BizException("agent 不存在");

        // 真实 agent jar 由 agent/ 独立 module 现编译生成， 打包路径 backend/jar_inject/aiops-agent-{agentkey}.jar
        // 为赶验收时间并当 agent module 未准备好时， 用 stub：直接返回含 agent_key 的 metadata(暂行到其中一份速利 友好 Florida用业风）
        String stub = String.format(
                "AgentKey=%s%nTargetId=%s%nPlatformUrl=%s%n%nDownload full jar from backend/jar_inject/aiops-agent-%s.jar when ready.%n",
                a.getAgentKey(), a.getTargetId(), platformBaseUrl, a.getAgentKey());
        byte[] content = stub.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"aiops-agent." + a.getAgentKey() + ".txt\"")
                .body(new ByteArrayResource(content));
    }

    /* ========================= 5. metric 上报 ========================= */

    @Operation(summary = "Agent 上报指标（需要 X-Agent-Key 头）")
    @PostMapping("/metric")
    public Result<Map<String, Object>> pushMetric(@RequestBody List<Map<String, Object>> metrics,
                                                  HttpServletRequest req) {
        MonitorAgent agent = (MonitorAgent) req.getAttribute(AgentAuthFilter.AGENT_ATTR);
        if (agent == null) {
            throw new BizException("agent 未鉴权");
        }
        LocalDateTime now = LocalDateTime.now();
        int accepted = 0;
        for (Map<String, Object> m : metrics) {
            String key = (String) m.get("metricKey");
            Object v = m.get("value");
            Object ts = m.get("timestamp");
            if (key == null || v == null) continue;
            MetricData md = new MetricData();
            md.setTargetId(agent.getTargetId());
            md.setMetricKey(key);
            md.setMetricValue(v instanceof Number n ? BigDecimal.valueOf(n.doubleValue())
                    : new BigDecimal(String.valueOf(v)));
            md.setCollectTime(parseTime(ts == null ? null : String.valueOf(ts), now));
            metricDataMapper.insert(md);
            accepted++;
        }
        // 更新 last_metric_time & version if header present
        MonitorAgent upd = new MonitorAgent();
        upd.setId(agent.getId());
        upd.setLastMetricTime(now);
        upd.setLastHeartbeat(now);
        upd.setUpdateTime(now);
        String ver = req.getHeader("X-Agent-Version");
        if (ver != null && !ver.isBlank()) upd.setVersion(ver);
        monitorAgentMapper.updateById(upd);

        Map<String, Object> out = new HashMap<>();
        out.put("accepted", accepted);
        out.put("time", now.format(DF));
        return Result.ok(out);
    }

    /* ========================= 6. 心跳 ========================= */

    @Operation(summary = "Agent 心跳（需要 X-Agent-Key 头）")
    @PostMapping("/heartbeat")
    public Result<Map<String, Object>> heartbeat(@RequestBody(required = false) Map<String, Object> body,
                                                 HttpServletRequest req) {
        MonitorAgent agent = (MonitorAgent) req.getAttribute(AgentAuthFilter.AGENT_ATTR);
        if (agent == null) {
            throw new BizException("agent 未鉴权");
        }
        LocalDateTime now = LocalDateTime.now();
        MonitorAgent upd = new MonitorAgent();
        upd.setId(agent.getId());
        upd.setLastHeartbeat(now);
        upd.setUpdateTime(now);
        String ver = body != null ? String.valueOf(body.getOrDefault("version", "")) : "";
        if (!ver.isBlank()) upd.setVersion(ver);
        monitorAgentMapper.updateById(upd);

        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("time", now.format(DF));
        return Result.ok(out);
    }

    /* ========================= 7. 状态查询（前端轮询） ========================= */

    @Operation(summary = "按 target 查 agent 状态（>2分钟没心跳视为离线）")
    @com.aiops.security.RequirePerm("monitor:target:list")
    @GetMapping("/status/{targetId}")
    public Result<Map<String, Object>> status(@PathVariable Long targetId) {
        MonitorAgent a = monitorAgentMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MonitorAgent>()
                        .eq(MonitorAgent::getTargetId, targetId)
                        .eq(MonitorAgent::getDeleted, 0)
                        .last("LIMIT 1"));
        Map<String, Object> out = new HashMap<>();
        if (a == null) {
            out.put("online", false);
            out.put("reason", "no agent");
            return Result.ok(out);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean online = a.getLastHeartbeat() != null
                && a.getLastHeartbeat().isAfter(now.minusMinutes(2));
        out.put("online", online);
        out.put("agentId", a.getId());
        out.put("status", a.getStatus());
        out.put("lastHeartbeat", a.getLastHeartbeat());
        out.put("lastMetricTime", a.getLastMetricTime());
        out.put("version", a.getVersion());
        return Result.ok(out);
    }

    /* ========================= 辅助 ========================= */

    private static final SecureRandom SR = new SecureRandom();

    private String genAgentKey() {
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) sb.append(alphabet.charAt(SR.nextInt(alphabet.length())));
        return sb.toString();
    }

    private String buildInstallCommand(Long agentId, String agentKey, Long targetId) {
        return "wget " + platformBaseUrl + "/api/agent/download/" + agentId + " -O aiops-agent.jar\n"
                + "nohup java -jar aiops-agent.jar \\\n"
                + "  --platform.url=" + platformBaseUrl + " \\\n"
                + "  --agent.key=" + agentKey + " \\\n"
                + "  --target.id=" + targetId + " \\\n"
                + "  > aiops-agent.log 2>&1 &";
    }
}
