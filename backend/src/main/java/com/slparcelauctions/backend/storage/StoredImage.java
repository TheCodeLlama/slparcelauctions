package com.slparcelauctions.backend.storage;

/**
 * Result returned by {@link ImageStorageService#storeImage}. Carries the
 * final S3 object key (with {@code .webp} extension), the WebP content
 * type, and the encoded byte length. Callers persist all three onto the
 * relevant domain entity's {@code *_object_key}, {@code *_content_type},
 * {@code *_size_bytes} columns.
 *
 * @param objectKey   final S3 key, always ending in {@code .webp}
 * @param contentType always {@code "image/webp"}
 * @param sizeBytes   final encoded byte length
 */
public record StoredImage(String objectKey, String contentType, long sizeBytes) {}
