package com.techverito.banking.entity;

import com.techverito.banking.event.BusinessEventType;

/**
 * The slow work that is handed to a background worker instead of being done inside the
 * request.
 *
 * <p>This enum names each unit of slow work and the business event type it is announced
 * as. The exchange, the queue and the routing key of a task type are configuration
 * ({@link com.techverito.banking.config.TaskQueueProperties}), one property per task
 * type, so an environment can change them without a code change and no two task types
 * can share a queue or a routing rule: one slow task type therefore cannot hold up
 * another.</p>
 */
public enum BackgroundTaskType {

    WELCOME_EMAIL {
        @Override
        public BusinessEventType eventType() {
            return BusinessEventType.WELCOME_EMAIL_REQUESTED;
        }
    },

    STATEMENT_GENERATION {
        @Override
        public BusinessEventType eventType() {
            return BusinessEventType.STATEMENT_REQUESTED;
        }
    },

    DOCUMENT_EXPORT {
        @Override
        public BusinessEventType eventType() {
            return BusinessEventType.EXPORT_REQUESTED;
        }
    },

    DOCUMENT_SCAN {
        @Override
        public BusinessEventType eventType() {
            return BusinessEventType.DOCUMENT_SCAN_REQUESTED;
        }
    };

    /** The business event type a task of this kind is announced as. */
    public abstract BusinessEventType eventType();
}
