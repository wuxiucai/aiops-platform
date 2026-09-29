package com.aiops.demo.payment.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 慢调用过滤器：被 /demo/fault/slow 开启后，&lt;slowPath&gt; 匹配的请求会被 Thread.sleep(ms)。
 * HIGHEST_PRECEDENCE：保证最先被调用，使慢速「真实地」发生。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlowRequestFilter extends OncePerRequestFilter {

    private final FaultState state;

    public SlowRequestFilter(FaultState state) {
        this.state = state;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (state.isSlowEnabled()) {
            String target = state.getSlowPath();
            String uri = request.getRequestURI();
            if (target != null && !target.isEmpty() && uri != null && uri.startsWith(target)) {
                long ms = state.getSlowMs();
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
