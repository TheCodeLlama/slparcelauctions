package com.slparcelauctions.backend.notification.slim.internal;

import com.slparcelauctions.backend.notification.slim.SlImMessage;
import com.slparcelauctions.backend.notification.slim.SlImMessageRepository;
import com.slparcelauctions.backend.notification.slim.SlImMessageStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/sl-im")
@RequiredArgsConstructor
public class SlImInternalController {

    private final SlImMessageRepository repo;
    private final JdbcTemplate jdbc;
    private final SlImInternalProperties props;

    public record PendingItem(long id, String avatarUuid, String messageText) {}
    public record PendingResponse(List<PendingItem> messages) {}

    @GetMapping("/pending")
    public PendingResponse pending(@RequestParam(defaultValue = "10") int limit) {
        if (limit > props.maxBatchLimit() || limit < 1) {
            throw new IllegalArgumentException(
                "limit must be in [1, " + props.maxBatchLimit() + "]");
        }
        // FOR UPDATE SKIP LOCKED in pollPending() is advisory: this method isn't
        // @Transactional, so the row lock releases when the query returns —
        // before the LSL script delivers. Real multi-dispatcher dedup would need
        // a PENDING → IN_PROGRESS → DELIVERED status transition.
        List<SlImMessage> rows = repo.pollPending(limit);
        List<PendingItem> items = rows.stream()
            .map(m -> new PendingItem(m.getId(), m.getAvatarUuid(), m.getMessageText()))
            .toList();
        return new PendingResponse(items);
    }

    @PostMapping("/{id}/delivered")
    public ResponseEntity<Void> delivered(@PathVariable long id) {
        return transition(id, SlImMessageStatus.DELIVERED);
    }

    @PostMapping("/{id}/failed")
    public ResponseEntity<Void> failed(@PathVariable long id) {
        return transition(id, SlImMessageStatus.FAILED);
    }

    /**
     * State machine for /delivered:
     *   PENDING   → DELIVERED (204, set delivered_at + increment attempts)
     *   DELIVERED → 204 no-op (idempotent; delivered_at NOT re-stamped)
     *   FAILED    → 409
     *   EXPIRED   → 409
     *   missing   → 404
     *
     * State machine for /failed:
     *   PENDING   → FAILED (204, increment attempts)
     *   FAILED    → 204 no-op (idempotent)
     *   DELIVERED → 409
     *   EXPIRED   → 409
     *   missing   → 404
     *
     * Implemented as a single conditional UPDATE returning affected rows count,
     * with a follow-up read on the no-op path to determine the response.
     */
    private ResponseEntity<Void> transition(long id, SlImMessageStatus target) {
        String sql = (target == SlImMessageStatus.DELIVERED)
            ? """
                UPDATE sl_im_message
                SET status = 'DELIVERED',
                    delivered_at = COALESCE(delivered_at, now()),
                    updated_at = now(),
                    attempts = attempts + 1
                WHERE id = ? AND status = 'PENDING'
                """
            : """
                UPDATE sl_im_message
                SET status = 'FAILED',
                    updated_at = now(),
                    attempts = attempts + 1
                WHERE id = ? AND status = 'PENDING'
                """;
        int updated = jdbc.update(sql, id);
        if (updated == 1) {
            return ResponseEntity.noContent().build();
        }

        Optional<SlImMessage> opt = repo.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        SlImMessageStatus current = opt.get().getStatus();
        if (current == target) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
