package com.techverito.banking.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Asking for one dead-lettered item to be replayed. The name of whoever ran the replay is
 * required and recorded on the item, the same way an upload records who uploaded it.
 */
public record ReplayRequest(
        @NotBlank String replayedBy
) {}
