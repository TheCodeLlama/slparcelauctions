package com.slparcelauctions.backend.common.image;

/**
 * Theme-pair selector for every image surface that ships a light + dark variant
 * (group cover, group logo, group default listing photo, user default cover,
 * auction listing photo).
 *
 * <p>{@link #parse(String)} is the wire-shape entry point used by controllers
 * receiving a {@code {variant}} path-parameter or {@code ?variant=} query-string
 * arg. Anything other than {@code light} / {@code dark} (case-insensitive)
 * raises {@link InvalidVariantException}, which the global exception handler
 * maps to {@code 400 INVALID_VARIANT}.
 *
 * <p>{@link #slug()} is the canonical lowercase wire form used in storage keys
 * (e.g. {@code realty-groups/{publicId}/cover-light.webp}) and URL query
 * strings. Keep storage-key callers on {@link #slug()} so the on-disk layout
 * matches the wire surface exactly.
 */
public enum ImageVariant {
    LIGHT, DARK;

    public static ImageVariant parse(String value) {
        if (value == null) {
            throw new InvalidVariantException("variant required");
        }
        return switch (value.toLowerCase()) {
            case "light" -> LIGHT;
            case "dark" -> DARK;
            default -> throw new InvalidVariantException(value);
        };
    }

    public String slug() {
        return name().toLowerCase();
    }
}
