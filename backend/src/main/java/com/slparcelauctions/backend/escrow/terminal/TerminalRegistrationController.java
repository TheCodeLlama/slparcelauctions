package com.slparcelauctions.backend.escrow.terminal;

import java.time.OffsetDateTime;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.escrow.terminal.dto.TerminalRegisterRequest;
import com.slparcelauctions.backend.sl.SlHeaderValidator;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Terminal registration endpoint (spec §7.5). Called by the in-world
 * escrow-terminal script on startup and on HTTP-in URL refresh. Secured by
 * two layers:
 *
 * <ol>
 *   <li>SL-injected headers ({@code X-SecondLife-Shard} / {@code X-SecondLife-Owner-Key})
 *       validated by {@link SlHeaderValidator} — identical trust boundary to
 *       {@code /api/v1/sl/verify}.</li>
 *   <li>A body-carried {@code sharedSecret} verified in
 *       {@link TerminalService#assertSharedSecret(String)}; mismatches throw
 *       {@link com.slparcelauctions.backend.escrow.exception.TerminalAuthException}.</li>
 * </ol>
 *
 * Security at the HTTP layer is {@code permitAll} in {@code SecurityConfig} —
 * see the same pattern already used by {@code SlVerificationController}.
 */
@RestController
@RequestMapping("/api/v1/sl/terminal")
@RequiredArgsConstructor
public class TerminalRegistrationController {

    private final TerminalService terminalService;
    private final SlHeaderValidator headerValidator;

    @PostMapping("/register")
    public ResponseEntity<TerminalResponse> register(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody TerminalRegisterRequest req) {
        headerValidator.validate(shard, ownerKey);
        Terminal t = terminalService.register(req);
        return ResponseEntity.ok(new TerminalResponse(
                t.getTerminalId(), t.getHttpInUrl(), t.getLastSeenAt()));
    }

    public record TerminalResponse(String terminalId, String httpInUrl,
                                   OffsetDateTime lastSeenAt) { }
}
