package com.aiops.module.monitor.service;

import com.aiops.common.BizException;
import com.aiops.module.monitor.dto.AgentMetricPoint;
import com.aiops.module.monitor.dto.CreateAgentWithTargetRequest;
import com.aiops.module.monitor.entity.MetricData;
import com.aiops.module.monitor.entity.MonitorAgent;
import com.aiops.module.monitor.entity.MonitorTarget;
import com.aiops.module.monitor.mapper.MetricDataMapper;
import com.aiops.module.monitor.mapper.MonitorAgentMapper;
import com.aiops.module.monitor.mapper.MonitorTargetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 远程 Linux agent（Push 模式）：创建、启停、指标/心跳接收、在线状态。
 * 多表写入（target + agent、metric_data + agent 心跳）各自放在一个事务里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SecureRandom SR = new SecureRandom();

    private final MonitorAgentMapper monitorAgentMapper;
    private final MonitorTargetMapper monitorTargetMapper;
    private final MetricDataMapper metricDataMapper;

    /** 平台对外基础URL， install_command 里用 */
    @Value("${aiops.platform.base-url:http://127.0.0.1:8080}")
    private String platformBaseUrl;

    public String platformBaseUrl() {
        return platformBaseUrl;
    }

    public List<Map<String, Object>> list() {
        List<MonitorAgent> agents = monitorAgentMapper.selectList(
                new LambdaQueryWrapper<MonitorAgent>().orderByDesc(MonitorAgent::getCreateTime));
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
        return out;
    }

    /** 为已有 target 新建 agent，返回 install_command 等元信息 */
    @Transactional
    public Map<String, Object> create(Long targetId) {
        if (targetId == null) {
            throw new BizException("targetId 必填");
        }
        MonitorTarget target = monitorTargetMapper.selectById(targetId);
        if (target == null) {
            throw new BizException("target 不存在：" + targetId);
        }
        return doCreateAgent(target);
    }

    /**
     * 新建监控对象 + 同时为其创建 Agent（一站式接入还没在系统里的机器）。
     * target / agent 任一写入失败整体回滚，不会出现"建了一半"的脏数据。
     */
    @Transactional
    public Map<String, Object> createWithTarget(CreateAgentWithTargetRequest req) {
        String name = req.getName();
        String ip = req.getIp();
        if (name == null || name.isBlank()) throw new BizException("name 必填");
        if (ip == null || ip.isBlank()) throw new BizException("ip 必填");

        // 防重：同名 target 已存在且未删除则报错
        Long dup = monitorTargetMapper.selectCount(new LambdaQueryWrapper<MonitorTarget>()
                .eq(MonitorTarget::getName, name));
        if (dup != null && dup > 0) {
            throw new BizException("监控对象名 '" + name + "' 已存在，直接在下拉里选它，不要重复新建");
        }

        MonitorTarget t = new MonitorTarget();
        t.setName(name.trim());
        t.setIp(ip.trim());
        t.setTargetType(req.getTargetType() == null ? "host" : req.getTargetType());
        t.setOs(req.getOs());
        t.setLogServiceName(req.getLogServiceName());
        t.setDescription(req.getDescription());
        t.setStatus(1);
        t.setGroupId(req.getGroupId());
        monitorTargetMapper.insert(t);
        log.info("[Agent] create-with-target: created target id={} name={}", t.getId(), t.getName());

        Map<String, Object> out = doCreateAgent(t);
        out.put("targetId", t.getId());
        out.put("targetName", t.getName());
        return out;
    }

    public void toggle(Long id) {
        MonitorAgent a = monitorAgentMapper.selectById(id);
        if (a == null) throw new BizException("agent 不存在");
        a.setStatus(a.getStatus() != null && a.getStatus() == 1 ? 0 : 1);
        a.setUpdateTime(LocalDateTime.now());
        monitorAgentMapper.updateById(a);
    }

    /** 未删除的 agent；不存在时抛错 */
    public MonitorAgent requireActive(Long id) {
        MonitorAgent a = monitorAgentMapper.selectById(id);
        if (a == null || a.getDeleted() == 1) throw new BizException("agent 不存在");
        return a;
    }

    /**
     * 接收一批指标并刷新 agent 心跳。value / metricKey 缺失的点跳过，返回实际写入条数。
     * @param version 请求头 X-Agent-Version，可空
     */
    @Transactional
    public Map<String, Object> acceptMetrics(MonitorAgent agent, List<AgentMetricPoint> points, String version) {
        LocalDateTime now = LocalDateTime.now();
        int accepted = 0;
        for (AgentMetricPoint p : points == null ? List.<AgentMetricPoint>of() : points) {
            if (p == null || p.getMetricKey() == null || p.getValue() == null) continue;
            MetricData md = new MetricData();
            md.setTargetId(agent.getTargetId());
            md.setMetricKey(p.getMetricKey());
            md.setMetricValue(p.getValue());
            md.setCollectTime(p.getTimestamp() == null ? now : p.getTimestamp());
            metricDataMapper.insert(md);
            accepted++;
        }
        MonitorAgent upd = new MonitorAgent();
        upd.setId(agent.getId());
        upd.setLastMetricTime(now);
        upd.setLastHeartbeat(now);
        upd.setUpdateTime(now);
        if (version != null && !version.isBlank()) upd.setVersion(version);
        monitorAgentMapper.updateById(upd);

        Map<String, Object> out = new HashMap<>();
        out.put("accepted", accepted);
        out.put("time", now.format(DF));
        return out;
    }

    public Map<String, Object> heartbeat(MonitorAgent agent, String version) {
        LocalDateTime now = LocalDateTime.now();
        MonitorAgent upd = new MonitorAgent();
        upd.setId(agent.getId());
        upd.setLastHeartbeat(now);
        upd.setUpdateTime(now);
        if (version != null && !version.isBlank()) upd.setVersion(version);
        monitorAgentMapper.updateById(upd);

        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("time", now.format(DF));
        return out;
    }

    /** 按 target 查 agent 状态（>2 分钟没心跳视为离线） */
    public Map<String, Object> status(Long targetId) {
        MonitorAgent a = monitorAgentMapper.selectOne(new LambdaQueryWrapper<MonitorAgent>()
                .eq(MonitorAgent::getTargetId, targetId)
                .eq(MonitorAgent::getDeleted, 0)
                .last("LIMIT 1"));
        Map<String, Object> out = new HashMap<>();
        if (a == null) {
            out.put("online", false);
            out.put("reason", "no agent");
            return out;
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
        return out;
    }

    /** 共用的 agent 创建逻辑：防重 → insert → 生成 installCommand → 返回元信息 */
    private Map<String, Object> doCreateAgent(MonitorTarget target) {
        Long targetId = target.getId();
        Long existing = monitorAgentMapper.selectCount(new LambdaQueryWrapper<MonitorAgent>()
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
        return out;
    }

    private static String genAgentKey() {
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
