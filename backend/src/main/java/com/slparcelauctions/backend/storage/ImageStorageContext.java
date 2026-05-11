package com.slparcelauctions.backend.storage;

/**
 * Per-call context for {@link ImageStorageService#storeImage}. Carries the
 * {@link ImagePurpose} (which determines the default maxDim cap and
 * quality/lossless strategy) and the caller-derived object key (sans
 * extension — the helper appends {@code .webp}).
 *
 * <p>Avatars upload at three canonical sizes (64/128/256) by calling the
 * helper three times with {@code maxDimOverride} pinned to the target
 * size; logo/cover/listing-photo callers pass {@code maxDimOverride = 0}
 * and let the purpose's default cap apply.
 *
 * @param purpose         which class of upload this is; controls the default
 *                        dim cap and the encoder quality/lossless strategy
 * @param objectKey       full S3 object key without an image-format extension;
 *                        the helper appends or replaces the extension with
 *                        {@code .webp}
 * @param maxDimOverride  if positive, overrides {@link ImagePurpose#maxDim};
 *                        zero or negative means "use the purpose's default"
 */
public record ImageStorageContext(ImagePurpose purpose, String objectKey, int maxDimOverride) {

    /** Convenience constructor — most callers use the purpose's default
     *  dim cap and pass no override. */
    public ImageStorageContext(ImagePurpose purpose, String objectKey) {
        this(purpose, objectKey, 0);
    }

    /** Effective dimension cap — override if positive, otherwise the
     *  purpose's default. */
    public int effectiveMaxDim() {
        return maxDimOverride > 0 ? maxDimOverride : purpose.maxDim;
    }
}
