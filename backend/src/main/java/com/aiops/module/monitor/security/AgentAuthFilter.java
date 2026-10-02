package com.aiops.module.monitor.security;

import com.aiops.common.BizException;
import com.aiops.module.monitor.entity.MonitorAgent;
import com.aiops.module.monitor.mapper.MonitorAgentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.GenericFilterBean;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent 上报鉴权 Filter。
 * <p>
 * 只拦 /api/agent/metric 与 /api/agent/heartbeat：
 *  - 头 X-Agent-Key 查 monitor_agent 表，找不到 → 401
 *  - status=0 （禁用） → 403
 *  - 通过则将 MonitorAgent实例注入 request attribute AGENT_CONTEXT
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAuthFilter extends GenericFilterBean {

    public static final String AGENT_ATTR = "AGENT_CONTEXT";
    public static final String AGENT_KEY_HEADER = "X-Agent-Key";

    private final MonitorAgentMapper monitorAgentMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();
        if (!path.startsWith("/api/agent/metric") && !path.startsWith("/api/agent/heartbeat")) {
            chain.doFilter(req, res);
            return;
        }

        String key = request.getHeader(AGENT_KEY_HEADER);
        if (key == null || key.isBlank()) {
            writeError(response, 401, "未提供 X-Agent-Key 头");
            return;
        }

        MonitorAgent agent = monitorAgentMapper.selectOne(new LambdaQueryWrapper<MonitorAgent>()
                .eq(MonitorAgent::getAgentKey, key)
                .eq(MonitorAgent::getDeleted, 0)
                .last("LIMIT 1"));
        if (agent == null) {
            log.warn("[AgentAuth] agent_key 未知： {}", key.substring(0, Math.min(8, key.length())) + "...");
            writeError(response, 401, "未知 agent_key");
            return;
        }
        if (agent.getStatus() == null || agent.getStatus() != 1) {
            writeError(response, 403, "agent 已禁用");
            return;
        }

        request.setAttribute(AGENT_ATTR, agent);
        chain.doFilter(req, res);
    }

    private void writeError(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        Map<String, Object> out = new HashMap<>();
        out.put("code", status);
        out.put("msg", msg);
        resp.getWriter().write(objectMapper.writeValueAsString(out));
    }
}
