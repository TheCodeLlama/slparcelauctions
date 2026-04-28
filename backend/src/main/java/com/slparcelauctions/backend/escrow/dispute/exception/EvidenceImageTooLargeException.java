package com.slparcelauctions.backend.escrow.dispute.exception;

public class EvidenceImageTooLargeException extends RuntimeException {
    public EvidenceImageTooLargeException(String filename, long size, long max) {
        super("Image " + filename + " size " + size + " exceeds max " + max);
    }
}
