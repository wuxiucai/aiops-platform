package com.aiops.module.monitor.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.monitor.dto.AgentHeartbeatRequest;
import com.aiops.module.monitor.dto.AgentMetricPoint;
import com.aiops.module.monitor.dto.CreateAgentRequest;
import com.aiops.module.monitor.dto.CreateAgentWithTargetRequest;
import com.aiops.module.monitor.entity.MonitorAgent;
import com.aiops.module.monitor.security.AgentAuthFilter;
import com.aiops.module.monitor.service.AgentService;
import com.aiops.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 远程 Linux agent 管理（Push 模式）。实现见 {@link AgentService}。
 * - 管理端（CRUD/启停/下载）走 JWT RequirePerm.
 * - Agent 上报（/api/agent/metric, /api/agent/heartbeat）走 AgentAuthFilter 验 X-Agent-Key.
 */
@Tag(name = "远程 Agent (Push)")
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /* ========================= 1. 列表 ========================= */

    @Operation(summary = "agent 列表（含 target 信息）")
    @RequirePerm("monitor:target:list")
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(agentService.list());
    }

    /* ========================= 2. 新建 ========================= */

    @Operation(summary = "新建 agent，返回 install_command")
    @RequirePerm("monitor:target:update")
    @PostMapping("/create")
    public Result<Map<String, Object>> create(@RequestBody CreateAgentRequest body) {
        return Result.ok(agentService.create(body.getTargetId()));
    }

    /** 前端 Agent.vue 新建对话框在「目标主机」下拉选 "+ 新建主机" 时调用 */
    @Operation(summary = "新建 target + agent（一次性，事务）")
    @RequirePerm("monitor:target:add")
    @PostMapping("/create-with-target")
    public Result<Map<String, Object>> createWithTarget(@RequestBody CreateAgentWithTargetRequest body) {
        return Result.ok(agentService.createWithTarget(body));
    }

    /* ========================= 3. 启停 ========================= */

    @Operation(summary = "启停")
    @RequirePerm("monitor:target:update")
    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id) {
        agentService.toggle(id);
        return Result.ok();
    }

    /* ========================= 3.5 查询 install_command ========================= */

    @Operation(summary = "查询一条 agent 的 install_command（用于前端复制）")
    @RequirePerm("monitor:target:list")
    @GetMapping("/install-command/{id}")
    public Result<Map<String, String>> installCommand(@PathVariable Long id) {
        MonitorAgent a = agentService.requireActive(id);
        Map<String, String> m = new HashMap<>();
        m.put("installCommand", a.getInstallCommand() == null ? "" : a.getInstallCommand());
        return Result.ok(m);
    }

    /* ========================= 4. 下载 jar ========================= */

    /** agent jar 路径。默认读 classpath 同级 ../agent/target/aiops-agent-1.0.0.jar */
    @Value("${aiops.agent.jar-path:../agent/target/aiops-agent-1.0.0.jar}")
    private String agentJarPath;

    @Operation(summary = "下载 aiops-agent.jar")
    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id) throws java.io.IOException {
        agentService.requireActive(id);

        java.io.File f = resolveAgentJar();
        if (f == null) {
            throw new BizException("agent jar 未就绪：找不到 aiops-agent jar"
                    + "（尝试过 " + agentJarPath + " + 兜底路径）。请先在 agent/ 模块执行 mvn package");
        }
        ByteArrayResource res = new ByteArrayResource(java.nio.file.Files.readAllBytes(f.toPath()));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(res.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"aiops-agent.jar\"")
                // 显式禁止缓存：失败响应（1001）不应被浏览器记住，避免恢复后还看到旧错误页
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(res);
    }

    /**
     * 解析 agent jar 的真实路径。候选位置按优先级：
     *   1. ${aiops.agent.jar-path}（默认 ../agent/target/aiops-agent-1.0.0.jar，相对当前 cwd）
     *   2. ${user.dir}/agent/target/aiops-agent-1.0.0.jar        （cwd 已是项目根）
     *   3. ${user.dir}/../agent/target/aiops-agent-1.0.0.jar     （cwd 是 backend/，往上一层）
     *   4. classpath同级 aiops-agent-1.0.0.jar                   （部署到生产时 jar 放同目录）
     * 返回 null 表示全部失败。
     */
    private java.io.File resolveAgentJar() {
        java.util.List<java.io.File> candidates = new java.util.ArrayList<>();
        candidates.add(new java.io.File(agentJarPath));
        String userDir = System.getProperty("user.dir");
        candidates.add(new java.io.File(userDir, "agent/target/aiops-agent-1.0.0.jar"));
        java.io.File dirFile = new java.io.File(userDir);
        if (dirFile.getParentFile() != null) {
            candidates.add(new java.io.File(dirFile.getParentFile(), "agent/target/aiops-agent-1.0.0.jar"));
        }
        try {
            String codeSourcePath = AgentController.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            candidates.add(new java.io.File(codeSourcePath).getParentFile() != null
                    ? new java.io.File(new java.io.File(codeSourcePath).getParentFile(), "aiops-agent-1.0.0.jar")
                    : null);
        } catch (Exception ignored) {}
        for (java.io.File f : candidates) {
            if (f != null && f.exists() && f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /* ========================= 5. metric 上报 ========================= */

    @Operation(summary = "Agent 上报指标（需要 X-Agent-Key 头）")
    @PostMapping("/metric")
    public Result<Map<String, Object>> pushMetric(@RequestBody List<AgentMetricPoint> metrics,
                                                  HttpServletRequest req) {
        return Result.ok(agentService.acceptMetrics(authedAgent(req), metrics, req.getHeader("X-Agent-Version")));
    }

    /* ========================= 6. 心跳 ========================= */

    @Operation(summary = "Agent 心跳（需要 X-Agent-Key 头）")
    @PostMapping("/heartbeat")
    public Result<Map<String, Object>> heartbeat(@RequestBody(required = false) AgentHeartbeatRequest body,
                                                 HttpServletRequest req) {
        return Result.ok(agentService.heartbeat(authedAgent(req), body == null ? null : body.getVersion()));
    }

    /* ========================= 7. 状态查询（前端轮询） ========================= */

    @Operation(summary = "按 target 查 agent 状态（>2分钟没心跳视为离线）")
    @RequirePerm("monitor:target:list")
    @GetMapping("/status/{targetId}")
    public Result<Map<String, Object>> status(@PathVariable Long targetId) {
        return Result.ok(agentService.status(targetId));
    }

    /** AgentAuthFilter 校验 X-Agent-Key 后放入请求属性 */
    private static MonitorAgent authedAgent(HttpServletRequest req) {
        MonitorAgent agent = (MonitorAgent) req.getAttribute(AgentAuthFilter.AGENT_ATTR);
        if (agent == null) {
            throw new BizException("agent 未鉴权");
        }
        return agent;
    }
}
