package com.slparcelauctions.backend.sl;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.sl.dto.SlParcelVerifyRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Header-gated endpoint consumed by in-world LSL rezzable verification
 * objects for Method B auctions. The {@code X-SecondLife-Shard} and
 * {@code X-SecondLife-Owner-Key} headers are injected by the SL grid on
 * {@code llHTTPRequest} calls and act as the trust gate (this path is
 * {@code permitAll} in {@code SecurityConfig} — the {@link SlHeaderValidator}
 * component is the actual security boundary).
 *
 * <p>Returns {@code 204 No Content} on success: the LSL caller doesn't need
 * a response body, and the SL HTTP-in dataserver truncates anything beyond
 * 2KB so keeping the response empty avoids grid-side parsing issues.
 */
@RestController
@RequestMapping("/api/v1/sl/parcel")
@RequiredArgsConstructor
public class SlParcelVerifyController {

    private final SlParcelVerifyService service;

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody SlParcelVerifyRequest body) {
        service.verify(shard, ownerKey, body);
    }
}
