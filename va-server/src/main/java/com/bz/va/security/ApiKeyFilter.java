package com.bz.va.security;

import com.bz.va.config.VaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * B端接口鉴权：X-API-Key 白名单（va.api-keys 为空时视为开发模式放行）。
 * 仅守卫 /api/v1 业务端点；/api/v1/health 与前端静态资源放行。
 */
@Component
@Order(1)
public class ApiKeyFilter extends OncePerRequestFilter {

    private final VaProperties properties;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(VaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        return path.equals("/api/v1/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.authEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader("X-API-Key");
        if (key != null && properties.getApiKeys().contains(key)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Map.of("error", Map.of("code", "UNAUTHORIZED", "message", "缺少或无效的 X-API-Key")));
    }
}
