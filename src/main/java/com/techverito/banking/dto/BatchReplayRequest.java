package com.techverito.banking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Asking for several dead-lettered items to be replayed in one go. Both the ids and the
 * name of whoever ran the replay are required; an unknown id fails the whole batch before
 * anything is replayed, so a caller never gets a half-applied batch.
 */
public record BatchReplayRequest(
        @NotEmpty List<Long> ids,
        @NotBlank String replayedBy
) {}
