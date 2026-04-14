package com.slparcelauctions.backend.sl;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.sl.dto.SlVerifyRequest;
import com.slparcelauctions.backend.sl.dto.SlVerifyResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Header-gated endpoint consumed by in-world LSL verification terminals.
 * The {@code X-SecondLife-Shard} and {@code X-SecondLife-Owner-Key} headers
 * are injected by the SL grid on {@code llHTTPRequest} calls and act as the
 * trust gate (this path is {@code permitAll} in {@code SecurityConfig} - the
 * {@link SlHeaderValidator} component is the actual security boundary).
 */
@RestController
@RequestMapping("/api/v1/sl")
@RequiredArgsConstructor
public class SlVerificationController {

    private final SlVerificationService service;

    @PostMapping("/verify")
    public SlVerifyResponse verify(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody SlVerifyRequest body) {
        return service.verify(shard, ownerKey, body);
    }
}
