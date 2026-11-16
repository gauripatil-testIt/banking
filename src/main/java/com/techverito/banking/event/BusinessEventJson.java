package com.techverito.banking.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.techverito.banking.exception.BusinessEventValidationException;
import org.springframework.stereotype.Component;

/**
 * The one place that turns a {@link BusinessEvent} into the documented JSON and back,
 * so the in-memory bus, the Kafka adapter, tests and consumers all share one wire
 * shape.
 *
 * <p>It owns a dedicated {@link ObjectMapper} instead of mutating the application-wide
 * Spring mapper: dates are ISO-8601 strings and unknown properties are ignored, which
 * is what makes "adding a field does not break existing consumers" a property of this
 * codec rather than of the whole application.</p>
 *
 * <p>A reader supplies the concrete payload class, so no Jackson type metadata and no
 * polymorphic type registry is involved: the payload shape is chosen by the reader,
 * which keeps a version bump additive.</p>
 */
@Component
public class BusinessEventJson {

    private final ObjectMapper mapper;

    public BusinessEventJson() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Writes the documented wire format. An incomplete envelope cannot reach this
     * method, because {@link BusinessEvent} rejects it when it is constructed.
     */
    public String toJson(BusinessEvent<? extends EventPayload> event) {
        if (event == null) {
            throw new BusinessEventValidationException("A business event is required to write JSON.");
        }
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Could not write the business event " + event.eventId() + " as JSON: " + ex.getOriginalMessage(),
                    ex);
        }
    }

    /**
     * Reads a documented event into the envelope, with the payload bound to the
     * supplied class. Unknown JSON fields, at the envelope level and inside the
     * payload, are ignored rather than rejected.
     */
    public <T extends EventPayload> BusinessEvent<T> fromJson(String json, Class<T> payloadType) {
        if (json == null || json.isBlank()) {
            throw new BusinessEventValidationException("The JSON of a business event is required.");
        }
        if (payloadType == null) {
            throw new BusinessEventValidationException(
                    "The payload type to read the business event as is required.");
        }
        try {
            JavaType type = mapper.getTypeFactory().constructParametricType(BusinessEvent.class, payloadType);
            return mapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new BusinessEventValidationException(
                    "Could not read the business event JSON: " + ex.getOriginalMessage());
        }
    }
}
