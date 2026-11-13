package com.techverito.banking.event;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Mints one correlation id per HTTP request, reads the actor from the
 * {@code X-Actor} header, echoes the correlation id back in the response so a
 * caller can quote it, and clears the context afterwards.
 */
@Component
public class EventRequestContextFilter extends OncePerRequestFilter {

    public static final String ACTOR_HEADER = "X-Actor";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final EventRequestContext requestContext;

    public EventRequestContextFilter(EventRequestContext requestContext) {
        this.requestContext = requestContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = UUID.randomUUID().toString();
        requestContext.start(correlationId, request.getHeader(ACTOR_HEADER));
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            requestContext.clear();
        }
    }
}
