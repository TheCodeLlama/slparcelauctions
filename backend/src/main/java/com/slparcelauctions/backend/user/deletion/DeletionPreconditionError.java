package com.slparcelauctions.backend.user.deletion;

import java.util.List;

public record DeletionPreconditionError(
        String code,
        String message,
        List<Long> blockingIds) {
}
