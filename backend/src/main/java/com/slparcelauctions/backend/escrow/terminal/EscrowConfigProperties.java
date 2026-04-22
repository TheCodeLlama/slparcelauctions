package com.slparcelauctions.backend.escrow.terminal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for escrow orchestration. Bound to {@code slpa.escrow.*}.
 * See spec §6.5 for defaults. {@code terminalSharedSecret} is validated at
 * startup by {@link com.slparcelauctions.backend.escrow.config.EscrowStartupValidator}
 * on non-dev profiles.
 */
@ConfigurationProperties(prefix = "slpa.escrow")
public record EscrowConfigProperties(
        Boolean enabled,
        String terminalSharedSecret,
        Duration terminalLiveWindow,
        Duration commandInFlightTimeout,
        Integer ownershipApiFailureThreshold,
        Duration ownershipReminderDelay) {

    public EscrowConfigProperties {
        if (enabled == null) enabled = true;
        if (terminalSharedSecret == null) terminalSharedSecret = "";
        if (terminalLiveWindow == null) terminalLiveWindow = Duration.ofMinutes(15);
        if (commandInFlightTimeout == null) commandInFlightTimeout = Duration.ofMinutes(5);
        if (ownershipApiFailureThreshold == null) ownershipApiFailureThreshold = 5;
        if (ownershipReminderDelay == null) ownershipReminderDelay = Duration.ofHours(24);
    }
}
