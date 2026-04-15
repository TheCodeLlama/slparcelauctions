package com.slparcelauctions.backend.storage.exception;

import lombok.Getter;

@Getter
public class ObjectNotFoundException extends RuntimeException {
    private final String key;

    public ObjectNotFoundException(String key, Throwable cause) {
        super("Object not found: " + key, cause);
        this.key = key;
    }
}
