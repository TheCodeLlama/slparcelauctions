package com.slparcelauctions.backend.escrow.dispute.exception;

public class EvidenceImageContentTypeException extends RuntimeException {
    public EvidenceImageContentTypeException(String filename, String contentType) {
        super("Image " + filename + " content-type " + contentType + " not allowed");
    }
}
