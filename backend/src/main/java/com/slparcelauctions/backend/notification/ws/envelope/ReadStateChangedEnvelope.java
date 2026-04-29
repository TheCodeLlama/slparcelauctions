package com.slparcelauctions.backend.notification.ws.envelope;

public record ReadStateChangedEnvelope(String type) {
    public ReadStateChangedEnvelope() {
        this("READ_STATE_CHANGED");
    }
}
