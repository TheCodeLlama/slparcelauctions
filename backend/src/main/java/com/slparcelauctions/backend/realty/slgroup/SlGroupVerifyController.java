package com.slparcelauctions.backend.realty.slgroup;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.realty.slgroup.dto.SlGroupVerifyRequest;
import com.slparcelauctions.backend.sl.SlHeaderValidator;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * LSL callback endpoint for sub-project E spec §7.3 founder-via-terminal verification.
 *
 * <p>Auth: this path is {@code permitAll} at the HTTP layer in {@code SecurityConfig}
 * (the in-world LSL caller cannot present a JWT — Second Life's
 * {@code llHTTPRequest} has no way to attach one). The actual trust gate is the
 * {@link SlHeaderValidator} call at the start of {@link #verify} which checks the
 * SL-injected {@code X-SecondLife-Shard} and {@code X-SecondLife-Owner-Key}
 * headers against the configured grid + trusted owner-key set. There is no
 * global in-world request filter; each {@code /api/v1/sl/**} controller is
 * responsible for invoking the validator itself (same pattern as
 * {@code SlParcelVerifyController} / {@code TerminalRegistrationController}).
 */
@RestController
@RequestMapping("/api/v1/sl/sl-group")
@RequiredArgsConstructor
public class SlGroupVerifyController {

    private final RealtyGroupSlGroupService service;
    private final SlHeaderValidator headerValidator;

    @PostMapping("/verify")
    public ResponseEntity<String> verify(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody SlGroupVerifyRequest req) {
        headerValidator.validate(shard, ownerKey);
        service.handleTerminalCallback(req.verificationCode(), req.founderAvatarUuid());
        // Terminal scripts owner-say on OK; the response body is informational.
        return ResponseEntity.ok("OK");
    }
}
