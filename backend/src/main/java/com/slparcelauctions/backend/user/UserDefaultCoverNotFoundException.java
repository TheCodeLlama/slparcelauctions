package com.slparcelauctions.backend.user;

import com.slparcelauctions.backend.common.exception.ResourceNotFoundException;

/**
 * Thrown when a user has not yet set a default cover image. Mapped to HTTP
 * 404 by the global exception handler — the settings UI uses the 404 to
 * render the empty state.
 */
public class UserDefaultCoverNotFoundException extends ResourceNotFoundException {

    public UserDefaultCoverNotFoundException(Long userId) {
        super("Default cover not set for user id=" + userId);
    }
}
