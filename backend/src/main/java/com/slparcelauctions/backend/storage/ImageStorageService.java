package com.slparcelauctions.backend.storage;

import java.io.InputStream;

/**
 * Central chokepoint for any raster image upload that ends up in S3.
 * Validates the input by magic-byte sniff (never trusting client-supplied
 * Content-Type), resizes to the per-purpose cap, encodes to WebP, and
 * writes through to the underlying {@link ObjectStorageService}.
 *
 * <p>Every existing image upload path (avatars, default covers, listing
 * photos, dispute evidence) routes through this helper as of the
 * realty-groups slice. New realty-group logo + cover endpoints consume it
 * directly.
 *
 * <p>Historical S3 objects in non-WebP formats remain valid: each domain
 * entity's {@code *_content_type} column is the source of truth at serve
 * time. The helper change only affects new uploads going forward.
 */
public interface ImageStorageService {

    /**
     * Validate, resize, encode-to-WebP, and store the image bytes from
     * {@code in} per the context. Throws
     * {@link com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException}
     * (415) for any input that is not an allow-listed raster type (PNG /
     * JPEG / WebP), is unreadable, or fails encoding. The input stream is
     * fully consumed and closed by the implementation.
     */
    StoredImage storeImage(InputStream in, ImageStorageContext ctx);
}
