package com.techverito.banking.event;

import com.techverito.banking.entity.DocumentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Drives the in-memory bus directly: no broker and no Spring context. The
 * transaction behaviour is proved with {@link TransactionSynchronizationManager}
 * itself, so no database is needed either.
 */
class InMemoryEventBusTest {

    private final EventRequestContext requestContext = new EventRequestContext();

    private final InMemoryEventBus eventBus = new InMemoryEventBus(requestContext);

    @AfterEach
    void clearState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        requestContext.clear();
    }

    private BusinessEvent documentUploadedEvent() {
        return BusinessEvent.of(
                new DocumentUploadedPayload(9L, DocumentType.ID_PROOF, "proof.pdf", 4L, "ops.user"), 1L, null);
    }

    @Test
    void publish_deliversToMatchingSubscriberOnly() {
        EventBus.EventSubscriber matching = mock(EventBus.EventSubscriber.class);
        EventBus.EventSubscriber otherType = mock(EventBus.EventSubscriber.class);
        eventBus.subscribe(EventType.DOCUMENT_UPLOADED, matching);
        eventBus.subscribe(EventType.CUSTOMER_REGISTERED, otherType);

        eventBus.publish(documentUploadedEvent(), "ops.user");

        verify(matching, times(1)).onEvent(any(BusinessEvent.class));
        verifyNoInteractions(otherType);
    }

    @Test
    void publish_stampsActorAndCorrelationIdFromRequestContext() {
        requestContext.start("correlation-1", "teller1");
        List<BusinessEvent> received = new ArrayList<>();
        eventBus.subscribe(EventType.DOCUMENT_UPLOADED, received::add);

        eventBus.publish(documentUploadedEvent(), "ops.user");
        eventBus.publish(documentUploadedEvent(), "ops.user");

        assertThat(received).hasSize(2);
        assertThat(received.get(0).actor()).isEqualTo("ops.user");
        assertThat(received.get(0).correlationId()).isEqualTo("correlation-1");
        assertThat(received.get(1).correlationId()).isEqualTo("correlation-1");

        requestContext.clear();
        eventBus.publish(documentUploadedEvent(), "ops.user");

        assertThat(received).hasSize(3);
        assertThat(received.get(2).correlationId()).isNotBlank();
        assertThat(received.get(2).actor()).isEqualTo("ops.user");
    }

    @Test
    void publish_blankActor_isRejected() {
        EventBus.EventSubscriber subscriber = mock(EventBus.EventSubscriber.class);
        eventBus.subscribe(EventType.DOCUMENT_UPLOADED, subscriber);

        assertThatThrownBy(() -> eventBus.publish(documentUploadedEvent(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eventBus.publish(documentUploadedEvent(), "   "))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(subscriber);
    }

    @Test
    void publish_defersDeliveryUntilAfterCommit() {
        EventBus.EventSubscriber subscriber = mock(EventBus.EventSubscriber.class);
        eventBus.subscribe(EventType.DOCUMENT_UPLOADED, subscriber);
        TransactionSynchronizationManager.initSynchronization();
        try {
            eventBus.publish(documentUploadedEvent(), "ops.user");

            verify(subscriber, never()).onEvent(any(BusinessEvent.class));

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).isNotEmpty();
            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(subscriber, times(1)).onEvent(any(BusinessEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publish_rollback_deliversNothing() {
        EventBus.EventSubscriber subscriber = mock(EventBus.EventSubscriber.class);
        eventBus.subscribe(EventType.DOCUMENT_UPLOADED, subscriber);
        TransactionSynchronizationManager.initSynchronization();
        try {
            eventBus.publish(documentUploadedEvent(), "ops.user");

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(subscriber, never()).onEvent(any(BusinessEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void throwingSubscriber_doesNotStopOthersOrPropagate() {
        EventBus.EventSubscriber failing = mock(EventBus.EventSubscriber.class);
        doThrow(new IllegalStateException("subscriber failed")).when(failing).onEvent(any(BusinessEvent.class));
        EventBus.EventSubscriber healthy = mock(EventBus.EventSubscriber.class);
        eventBus.subscribe(EventType.DOCUMENT_UPLOADED, failing);
        eventBus.subscribe(EventType.DOCUMENT_UPLOADED, healthy);

        eventBus.publish(documentUploadedEvent(), "ops.user");

        verify(failing, times(1)).onEvent(any(BusinessEvent.class));
        verify(healthy, times(1)).onEvent(any(BusinessEvent.class));
    }

    @Test
    void cancelledSubscription_receivesNothing() {
        EventBus.EventSubscriber subscriber = mock(EventBus.EventSubscriber.class);
        EventBus.Subscription subscription = eventBus.subscribe(EventType.DOCUMENT_UPLOADED, subscriber);

        subscription.cancel();
        subscription.cancel();
        eventBus.publish(documentUploadedEvent(), "ops.user");

        verifyNoInteractions(subscriber);
    }
}
