package com.techverito.banking.event;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the filter with mock request/response objects, so the correlation id, the
 * actor and the thread-local cleanup are asserted without a web context.
 */
class EventRequestContextFilterTest {

    private final EventRequestContext requestContext = new EventRequestContext();

    private final EventRequestContextFilter filter = new EventRequestContextFilter(requestContext);

    @AfterEach
    void clearState() {
        requestContext.clear();
    }

    private String actorSeenDuringRequest(String actorHeaderValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customers");
        if (actorHeaderValue != null) {
            request.addHeader(EventRequestContextFilter.ACTOR_HEADER, actorHeaderValue);
        }
        AtomicReference<String> actor = new AtomicReference<>();
        FilterChain chain = (req, res) -> actor.set(requestContext.actor());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        return actor.get();
    }

    @Test
    void doFilter_generatesOneCorrelationIdAndEchoesItInTheResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customers");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> firstRead = new AtomicReference<>();
        AtomicReference<String> secondRead = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            firstRead.set(requestContext.correlationId());
            secondRead.set(requestContext.correlationId());
        };

        filter.doFilter(request, response, chain);

        assertThat(firstRead.get()).isNotBlank();
        assertThat(secondRead.get()).isEqualTo(firstRead.get());
        assertThat(response.getHeader(EventRequestContextFilter.CORRELATION_ID_HEADER)).isEqualTo(firstRead.get());
        assertThat(requestContext.correlationId()).isNull();
    }

    @Test
    void doFilter_usesXActorHeaderOrDefaultsToSystem() throws Exception {
        assertThat(actorSeenDuringRequest("teller1")).isEqualTo("teller1");
        assertThat(actorSeenDuringRequest("   ")).isEqualTo("system");
        assertThat(actorSeenDuringRequest(null)).isEqualTo("system");
    }

    @Test
    void doFilter_clearsTheContextAfterTheRequest() throws Exception {
        MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", "/customers");
        firstRequest.addHeader(EventRequestContextFilter.ACTOR_HEADER, "teller1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        AtomicReference<String> firstCorrelationId = new AtomicReference<>();
        AtomicReference<String> firstActor = new AtomicReference<>();
        FilterChain firstChain = (req, res) -> {
            firstCorrelationId.set(requestContext.correlationId());
            firstActor.set(requestContext.actor());
        };

        filter.doFilter(firstRequest, firstResponse, firstChain);

        assertThat(firstActor.get()).isEqualTo("teller1");
        assertThat(requestContext.correlationId()).isNull();
        assertThat(requestContext.actor()).isEqualTo("system");

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        AtomicReference<String> secondCorrelationId = new AtomicReference<>();
        AtomicReference<String> secondActor = new AtomicReference<>();
        FilterChain secondChain = (req, res) -> {
            secondCorrelationId.set(requestContext.correlationId());
            secondActor.set(requestContext.actor());
        };

        filter.doFilter(new MockHttpServletRequest("GET", "/customers"), secondResponse, secondChain);

        assertThat(secondActor.get()).isEqualTo("system");
        assertThat(secondCorrelationId.get()).isNotBlank().isNotEqualTo(firstCorrelationId.get());
        assertThat(secondResponse.getHeader(EventRequestContextFilter.CORRELATION_ID_HEADER))
                .isEqualTo(secondCorrelationId.get());
    }
}
