package com.slparcelauctions.backend.admin.infrastructure.terminals;

import com.slparcelauctions.backend.sl.SlHeaderValidator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Terminal heartbeat endpoint (Epic 10 sub-spec 3 Task 12). Called by
 * in-world escrow-terminal scripts on a periodic timer to report liveness
 * and current L$ balance.
 *
 * <p>Security mirrors {@link com.slparcelauctions.backend.escrow.terminal.TerminalRegistrationController}:
 * the path is {@code permitAll} in {@code SecurityConfig} and the actual
 * trust boundary is {@link SlHeaderValidator} validating the
 * SL-grid-injected {@code X-SecondLife-Shard} + {@code X-SecondLife-Owner-Key}
 * headers. LSL scripts cannot present a JWT, so SL header validation is the
 * only viable trust gate for terminal traffic.
 */
@RestController
@RequestMapping("/api/v1/sl/terminal")
@RequiredArgsConstructor
public class TerminalHeartbeatController {

    private final TerminalHeartbeatService service;
    private final SlHeaderValidator headerValidator;

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody TerminalHeartbeatRequest req) {
        headerValidator.validate(shard, ownerKey);
        service.handle(req);
        return ResponseEntity.ok().build();
    }
}
