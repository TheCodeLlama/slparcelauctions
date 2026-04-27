package com.slparcelauctions.backend.admin.exception;

import java.util.Map;

public record AdminApiError(String code, String message, Map<String, Object> details) {

    public static AdminApiError of(String code, String message) {
        return new AdminApiError(code, message, Map.of());
    }

    public static AdminApiError of(String code, String message, Map<String, Object> details) {
        return new AdminApiError(code, message, details);
    }
}
