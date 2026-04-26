package com.slparcelauctions.backend.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

public record UnreadCountResponse(
    long count,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Long> byGroup
) {
    public static UnreadCountResponse of(long count) {
        return new UnreadCountResponse(count, null);
    }

    public static UnreadCountResponse withBreakdown(long count, Map<String, Long> byGroup) {
        return new UnreadCountResponse(count, byGroup);
    }
}
