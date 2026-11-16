package com.techverito.banking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.DeadLetterResponse;
import com.techverito.banking.entity.DeadLetterEntry;
import com.techverito.banking.entity.DeadLetterStatus;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.DeadLetterItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * The operations side of the dead-letter store: listing the items that were set aside,
 * reading one, and replaying one or a batch of them.
 *
 * <p>A replay publishes the stored envelope <em>unchanged</em> through
 * {@link BusinessEventBus}, so the replayed event carries its original id – that is what
 * lets a duplicate-safe consumer recognise the second delivery and not repeat its effect –
 * and the item records who replayed it, when, and how many times, so nothing that was
 * reported as lost ever loses its history.</p>
 *
 * <p>No broker type appears here: the transport is whatever {@link BusinessEventBus} was
 * configured with.</p>
 */
@Service
@Transactional
public class DeadLetterReplayService {

    private final DeadLetterItemRepository deadLetterItemRepository;
    private final BusinessEventBus businessEventBus;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DeadLetterReplayService(DeadLetterItemRepository deadLetterItemRepository,
                                   BusinessEventBus businessEventBus,
                                   ObjectMapper objectMapper,
                                   Clock clock) {
        this.deadLetterItemRepository = deadLetterItemRepository;
        this.businessEventBus = businessEventBus;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Every dead-lettered item, oldest first, or only the ones in the given state when a
     * state is asked for.
     */
    @Transactional(readOnly = true)
    public List<DeadLetterResponse> list(DeadLetterStatus status) {
        List<DeadLetterEntry> items = (status == null)
                ? deadLetterItemRepository.findAllByOrderByIdAsc()
                : deadLetterItemRepository.findByStatusOrderByIdAsc(status);
        return items.stream()
                .map(DeadLetterResponse::from)
                .toList();
    }

    /**
     * One dead-lettered item, with everything it kept.
     */
    @Transactional(readOnly = true)
    public DeadLetterResponse get(Long id) {
        return DeadLetterResponse.from(findOrThrow(id));
    }

    /**
     * Replays one item: the stored envelope is published exactly as it was recorded, so
     * the event keeps its id, and the item records who ran the replay, when, and that it
     * has been replayed once more.
     */
    public DeadLetterResponse replay(Long id, String replayedBy) {
        if (replayedBy == null || replayedBy.isBlank()) {
            throw new IllegalArgumentException(
                    "A replay records who ran it, so the name of the person replaying is required.");
        }
        DeadLetterEntry item = findOrThrow(id);
        businessEventBus.publish(deserialise(item));

        item.setStatus(DeadLetterStatus.REPLAYED);
        item.setReplayedBy(replayedBy);
        item.setReplayedAt(clock.instant());
        item.setReplayCount(item.getReplayCount() + 1);
        return DeadLetterResponse.from(deadLetterItemRepository.save(item));
    }

    /**
     * Replays several items in one go. Every id is resolved before anything is replayed,
     * so an unknown id fails the whole batch instead of leaving it half applied.
     */
    public List<DeadLetterResponse> replayBatch(List<Long> ids, String replayedBy) {
        List<Long> requested = (ids == null) ? List.of() : List.copyOf(ids);
        for (Long id : requested) {
            findOrThrow(id);
        }
        List<DeadLetterResponse> replayed = new ArrayList<>();
        for (Long id : requested) {
            replayed.add(replay(id, replayedBy));
        }
        return replayed;
    }

    private DeadLetterEntry findOrThrow(Long id) {
        return deadLetterItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dead-lettered item", id));
    }

    private BusinessEvent deserialise(DeadLetterEntry item) {
        try {
            return objectMapper.readValue(item.getPayload(), BusinessEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The stored dead-lettered item " + item.getSourceId() + " ("
                    + item.getEventType() + ") could not be read back for replay.", ex);
        }
    }
}
