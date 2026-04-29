package com.slparcelauctions.backend.escrow.dispute.exception;

public class EvidenceTooManyImagesException extends RuntimeException {
    public EvidenceTooManyImagesException(int provided, int max) {
        super("Too many images: " + provided + " > max " + max);
    }
}
