package com.slparcelauctions.backend.realty.exception;

import java.util.UUID;

import lombok.Getter;

@Getter
public class GroupDissolvedException extends RuntimeException {
    private final UUID publicId;

    public GroupDissolvedException(UUID publicId) {
        super("Realty group is dissolved: " + publicId);
        this.publicId = publicId;
    }
}
