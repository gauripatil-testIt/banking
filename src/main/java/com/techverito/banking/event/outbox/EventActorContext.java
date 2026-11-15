package com.techverito.banking.event.outbox;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Who caused a business event and which request it belongs to, taken from the
 * {@code X-Actor} and {@code X-Correlation-Id} headers of the request being served.
 *
 * <p>The event envelope requires both values, so this resolves them in one place
 * instead of threading two parameters through every controller: a request supplies
 * them, and a caller with no request (a scheduled job, a test) gets {@code system}
 * and a fresh correlation id rather than a blank field the envelope would reject.</p>
 */
@Component
public class EventActorContext {

    static final String ACTOR_HEADER = "X-Actor";

    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    static final String DEFAULT_ACTOR = "system";

    /** The actor of the change being served, never blank. */
    public String actor() {
        String actor = header(ACTOR_HEADER);
        return (actor != null && !actor.isBlank()) ? actor : DEFAULT_ACTOR;
    }

    /** The correlation id tying this change to the request that caused it, never blank. */
    public String correlationId() {
        String correlationId = header(CORRELATION_ID_HEADER);
        return (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : UUID.randomUUID().toString();
    }

    private String header(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        return request.getHeader(name);
    }
}
