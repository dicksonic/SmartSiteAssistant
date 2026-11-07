package com.reg.assistant.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级 traceId：进 MDC（日志 pattern 引用）、进响应头（前端报错时可上报定位）。
 * 桥接进 logback 的 G-AIOS 日志与请求同线程，自动带同一 traceId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 16 位。取值依据：单机日请求量万级，16 位 hex 撞号概率可忽略，且比全量 UUID 省一半日志行宽。
        // 失效条件：日后多实例部署需在前面加实例前缀。
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TRACE_ID, traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID); // 线程归还线程池前必须清理，否则下一个请求串号
        }
    }
}