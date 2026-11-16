package com.techverito.banking.controller;

import com.techverito.banking.dto.BatchReplayRequest;
import com.techverito.banking.dto.DeadLetterResponse;
import com.techverito.banking.dto.ReplayRequest;
import com.techverito.banking.entity.DeadLetterStatus;
import com.techverito.banking.service.DeadLetterReplayService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The operations surface of the dead-letter store: which items were set aside, what one of
 * them kept, and replaying one or a batch of them.
 *
 * <p>{@code replayedBy} is a required field of the replay request – the name of whoever ran
 * the replay, the same way an upload names who uploaded it – so a missing or blank value is
 * answered with the application's usual 400 ProblemDetail, and an unknown id with its 404.
 * A batch that names any unknown id is rejected before anything is replayed.</p>
 */
@RestController
@RequestMapping("/dead-letters")
public class DeadLetterController {

    private final DeadLetterReplayService deadLetterReplayService;

    public DeadLetterController(DeadLetterReplayService deadLetterReplayService) {
        this.deadLetterReplayService = deadLetterReplayService;
    }

    @GetMapping
    public ResponseEntity<List<DeadLetterResponse>> list(
            @RequestParam(name = "status", required = false) DeadLetterStatus status) {
        return ResponseEntity.ok(deadLetterReplayService.list(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeadLetterResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(deadLetterReplayService.get(id));
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<DeadLetterResponse> replay(@PathVariable Long id,
                                                     @Valid @RequestBody ReplayRequest request) {
        return ResponseEntity.ok(deadLetterReplayService.replay(id, request.replayedBy()));
    }

    @PostMapping("/replay")
    public ResponseEntity<List<DeadLetterResponse>> replayBatch(@Valid @RequestBody BatchReplayRequest request) {
        return ResponseEntity.ok(deadLetterReplayService.replayBatch(request.ids(), request.replayedBy()));
    }
}
