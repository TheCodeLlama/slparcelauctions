package com.slparcelauctions.backend.realty.exception;

import java.time.OffsetDateTime;

import lombok.Getter;

@Getter
public class RealtyGroupRenameCooldownException extends RuntimeException {
    private final OffsetDateTime cooldownEndsAt;

    public RealtyGroupRenameCooldownException(OffsetDateTime cooldownEndsAt) {
        super("Realty group rename is on cooldown until " + cooldownEndsAt);
        this.cooldownEndsAt = cooldownEndsAt;
    }
}
