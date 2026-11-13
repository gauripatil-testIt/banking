package com.techverito.banking.event;

import org.springframework.stereotype.Component;

/**
 * Holds the correlation id and the actor of the request in flight, so every
 * event published during that one request is stamped with the same correlation
 * id. The values live in a thread-local and are cleared at the end of the
 * request, so nothing leaks between requests.
 *
 * <p>The application has no authentication, so the actor comes from the
 * {@code X-Actor} request header and falls back to {@code "system"}.</p>
 */
@Component
public class EventRequestContext {

    private static final String SYSTEM_ACTOR = "system";

    private final ThreadLocal<RequestValues> current = new ThreadLocal<>();

    /**
     * Marks the start of a request. A null or blank actor is stored as
     * {@code "system"}.
     */
    public void start(String correlationId, String actor) {
        current.set(new RequestValues(correlationId, resolveActor(actor)));
    }

    /**
     * The correlation id of the request in flight, or null when there is none.
     */
    public String correlationId() {
        RequestValues values = current.get();
        return (values != null) ? values.correlationId() : null;
    }

    /**
     * The actor of the request in flight; {@code "system"} when no request is in
     * flight or no actor was supplied.
     */
    public String actor() {
        RequestValues values = current.get();
        return (values != null) ? resolveActor(values.actor()) : SYSTEM_ACTOR;
    }

    /**
     * Removes the values of the request that just finished; safe to call when
     * nothing was ever started.
     */
    public void clear() {
        current.remove();
    }

    private String resolveActor(String actor) {
        return (actor == null || actor.isBlank()) ? SYSTEM_ACTOR : actor;
    }

    private record RequestValues(String correlationId, String actor) {}
}
