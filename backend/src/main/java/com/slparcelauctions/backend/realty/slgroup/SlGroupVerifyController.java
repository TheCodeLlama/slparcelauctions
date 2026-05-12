package com.slparcelauctions.backend.realty.slgroup;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.realty.slgroup.dto.SlGroupVerifyRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * LSL callback endpoint for sub-project E spec §7.3 founder-via-terminal verification.
 *
 * <p>Auth: the existing in-world request filter validates the shared-secret HMAC and the
 * {@code X-SecondLife-Owner-Key} header before this handler runs. If anything fails the
 * filter, the request is 401'd upstream — the controller itself trusts that any request
 * reaching it has already cleared the LSL trust gate.
 */
@RestController
@RequestMapping("/api/v1/sl/sl-group")
@RequiredArgsConstructor
public class SlGroupVerifyController {

    private final RealtyGroupSlGroupService service;

    @PostMapping("/verify")
    public ResponseEntity<String> verify(@Valid @RequestBody SlGroupVerifyRequest req) {
        service.handleTerminalCallback(req.verificationCode(), req.founderAvatarUuid());
        // Terminal scripts owner-say on OK; the response body is informational.
        return ResponseEntity.ok("OK");
    }
}
