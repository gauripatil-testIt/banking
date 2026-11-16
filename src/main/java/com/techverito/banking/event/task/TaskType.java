package com.techverito.banking.event.task;

/**
 * The slow work this application hands to a background worker. Each constant is one
 * kind of task and has its own queue and routing rule, declared in {@link TaskQueue},
 * so a slow task of one kind never holds up another.
 *
 * <p>These are deliberately separate from {@link com.techverito.banking.event.EventType}:
 * an event announces what already happened, a task asks for work that has not been
 * done yet, and the two vocabularies are not the same list.</p>
 */
public enum TaskType {

    WELCOME_EMAIL,
    STATEMENT_GENERATION,
    DOCUMENT_EXPORT,
    DOCUMENT_SCAN
}
