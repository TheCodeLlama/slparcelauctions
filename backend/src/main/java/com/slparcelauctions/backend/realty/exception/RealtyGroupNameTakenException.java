package com.slparcelauctions.backend.realty.exception;

import lombok.Getter;

@Getter
public class RealtyGroupNameTakenException extends RuntimeException {
    private final String name;

    public RealtyGroupNameTakenException(String name) {
        super("Realty group name is already taken: " + name);
        this.name = name;
    }
}
