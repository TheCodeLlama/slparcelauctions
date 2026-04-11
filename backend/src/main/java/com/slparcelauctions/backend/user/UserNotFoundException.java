package com.slparcelauctions.backend.user;

import com.slparcelauctions.backend.common.exception.ResourceNotFoundException;

public class UserNotFoundException extends ResourceNotFoundException {

    public UserNotFoundException(Long id) {
        super("User not found: id=" + id);
    }
}
