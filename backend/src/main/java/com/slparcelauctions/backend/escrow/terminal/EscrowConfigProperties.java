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
        String terminalSharedSecret,
        Duration terminalLiveWindow,
        Duration commandInFlightTimeout,
        Integer ownershipApiFailureThreshold,
        Duration ownershipReminderDelay,
        Duration sellToBotRecurrence,
        Duration sellToBotRetryBackoff,
        Integer sellToBotFailureThreshold,
        Duration buyParcelFastCadence,
        Duration buyParcelFastWindow,
        Duration buyParcelSlowCadence,
        Integer manualVerifyAttempts) {

    public EscrowConfigProperties {
        if (terminalSharedSecret == null) terminalSharedSecret = "";
        if (terminalLiveWindow == null) terminalLiveWindow = Duration.ofMinutes(15);
        if (commandInFlightTimeout == null) commandInFlightTimeout = Duration.ofMinutes(5);
        if (ownershipApiFailureThreshold == null) ownershipApiFailureThreshold = 5;
        if (ownershipReminderDelay == null) ownershipReminderDelay = Duration.ofHours(24);
        if (sellToBotRecurrence == null) sellToBotRecurrence = Duration.ofMinutes(30);
        if (sellToBotRetryBackoff == null) sellToBotRetryBackoff = Duration.ofMinutes(2);
        if (sellToBotFailureThreshold == null) sellToBotFailureThreshold = 5;
        if (buyParcelFastCadence == null) buyParcelFastCadence = Duration.ofMinutes(5);
        if (buyParcelFastWindow == null) buyParcelFastWindow = Duration.ofHours(1);
        if (buyParcelSlowCadence == null) buyParcelSlowCadence = Duration.ofMinutes(30);
        if (manualVerifyAttempts == null) manualVerifyAttempts = 3;
    }
}
