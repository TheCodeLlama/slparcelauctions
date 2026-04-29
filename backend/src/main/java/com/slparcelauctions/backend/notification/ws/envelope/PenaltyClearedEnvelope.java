package com.slparcelauctions.backend.notification.ws.envelope;

public record PenaltyClearedEnvelope(String type) {
    public PenaltyClearedEnvelope() {
        this("PENALTY_CLEARED");
    }
}
