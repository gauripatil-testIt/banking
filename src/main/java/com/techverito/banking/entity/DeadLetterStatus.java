package com.techverito.banking.entity;

/**
 * Where a dead-lettered item stands: {@link #PENDING} from the moment it was set aside
 * until operations replay it, and {@link #REPLAYED} once a replay has published it
 * again. A replayed item keeps its payload and its history, so it is still there to be
 * read afterwards.
 */
public enum DeadLetterStatus {
    PENDING, REPLAYED
}
