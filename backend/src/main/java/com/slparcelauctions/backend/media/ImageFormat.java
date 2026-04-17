package com.slparcelauctions.backend.media;

import java.util.Locale;
import java.util.Optional;

/**
 * Image formats accepted by the shared {@link ImageUploadValidator}. Format
 * identification is by byte-level sniff (ImageIO format name), never by the
 * client-supplied multipart {@code Content-Type} header, which is trivially
 * spoofable.
 */
public enum ImageFormat {
    JPEG,
    PNG,
    WEBP;

    /**
     * Maps an ImageIO reader's {@code getFormatName()} string to an
     * {@link ImageFormat}, or empty if the name does not map to one of the
     * allowed formats.
     */
    public static Optional<ImageFormat> fromImageIoName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "jpeg", "jpg" -> Optional.of(JPEG);
            case "png" -> Optional.of(PNG);
            case "webp" -> Optional.of(WEBP);
            default -> Optional.empty();
        };
    }

    /** Canonical filename extension (no leading dot). */
    public String extension() {
        return switch (this) {
            case JPEG -> "jpg";
            case PNG -> "png";
            case WEBP -> "webp";
        };
    }

    /** HTTP {@code Content-Type} value for this format. */
    public String contentType() {
        return switch (this) {
            case JPEG -> "image/jpeg";
            case PNG -> "image/png";
            case WEBP -> "image/webp";
        };
    }
}
