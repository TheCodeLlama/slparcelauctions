package com.slparcelauctions.backend.sl;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Validates the SL-injected request headers ({@code X-SecondLife-Shard} and
 * {@code X-SecondLife-Owner-Key}) against the {@link SlConfigProperties}. All
 * failures throw {@link InvalidSlHeadersException} which maps to HTTP 403.
 *
 * <p>This is the public-endpoint trust boundary for {@code POST /api/v1/sl/verify}
 * (the path is {@code permitAll} in {@code SecurityConfig}). Header rejection logs
 * at WARN to make tampering attempts visible without flooding INFO during normal
 * grid traffic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlHeaderValidator {

    private final SlConfigProperties props;

    public void validate(String shardHeader, String ownerKeyHeader) {
        if (!props.expectedShard().equals(shardHeader)) {
            log.warn("SL header rejected: shard '{}' != '{}'", shardHeader, props.expectedShard());
            throw new InvalidSlHeadersException("Request not from the expected grid");
        }
        UUID key;
        try {
            key = UUID.fromString(ownerKeyHeader);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("SL header rejected: owner key unparseable: '{}'", ownerKeyHeader);
            throw new InvalidSlHeadersException("Owner key missing or malformed");
        }
        if (!props.trustedOwnerKeys().contains(key)) {
            log.warn("SL header rejected: owner key {} not in trusted set", key);
            throw new InvalidSlHeadersException("Owner key is not trusted");
        }
    }
}
