package io.github.dcfaight.platformlab.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Runs before Spring Security so 401/403 responses also carry the request ID.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID = "requestId";
    private static final Logger LOG = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);
        long start = System.nanoTime();
        try (var ignored = MDC.putCloseable(REQUEST_ID, requestId)) {
            try {
                chain.doFilter(request, response);
            } finally {
                LOG.info("requestId={} method={} status={} durationMs={}", requestId,
                        request.getMethod(), response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            }
        }
    }
}
