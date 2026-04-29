package com.slparcelauctions.backend.notification.preferences;

import java.util.Map;

public record PreferencesDto(
    boolean slImMuted,
    Map<String, Object> slIm
) {}
