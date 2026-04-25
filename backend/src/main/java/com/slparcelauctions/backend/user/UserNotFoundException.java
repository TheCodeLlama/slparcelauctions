package com.slparcelauctions.backend.user;

import com.slparcelauctions.backend.common.exception.ResourceNotFoundException;

public class UserNotFoundException extends ResourceNotFoundException {

    public UserNotFoundException(Long id) {
        super("User not found: id=" + id);
    }

    /**
     * Free-form message constructor used by avatar-keyed lookups
     * (e.g. {@code PenaltyTerminalService} resolving by SL avatar UUID).
     * Maps to HTTP 404 via the global handler.
     */
    public UserNotFoundException(String message) {
        super(message);
    }
}
