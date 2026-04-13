package com.slparcelauctions.backend.sl;

import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for SL-side integration. Bound to {@code slpa.sl.*}.
 *
 * <p>The compact canonicalizing constructor supplies safe defaults: {@code expectedShard}
 * defaults to {@code "Production"} if blank, and {@code trustedOwnerKeys} is always a
 * defensively-copied immutable set. The startup validator ({@link SlStartupValidator})
 * fails fast in the prod profile if the set is empty.
 */
@ConfigurationProperties(prefix = "slpa.sl")
public record SlConfigProperties(
        String expectedShard,
        Set<UUID> trustedOwnerKeys
) {
    public SlConfigProperties {
        if (expectedShard == null || expectedShard.isBlank()) {
            expectedShard = "Production";
        }
        trustedOwnerKeys = trustedOwnerKeys == null ? Set.of() : Set.copyOf(trustedOwnerKeys);
    }
}
