package com.slparcelauctions.backend.storage;

/**
 * Per-purpose dimension cap for the central {@link ImageStorageService}
 * chokepoint. Each caller passes the purpose appropriate for its surface;
 * the helper resizes down (never up) to the per-axis maximum before
 * encoding to WebP. Values mirror the spec §7.5 maxima.
 */
public enum ImagePurpose {

    /** 256px square avatar bytes. The avatar pipeline calls the helper once
     *  per canonical size (64/128/256) and passes the size as a custom
     *  override via {@link ImageStorageContext}; the enum's max is the
     *  largest of the three. */
    AVATAR(256),

    /** Realty-group logo. 512px longest side, alpha preserved with lossless
     *  encoding when present. */
    LOGO(512),

    /** Realty-group cover image. 1920px width, lossy 85 default. */
    COVER(1920),

    /** Per-auction listing photo. 2048px longest side. */
    LISTING_PHOTO(2048),

    /** User default-cover image. 1920px width. */
    DEFAULT_COVER(1920),

    /** Per-dispute evidence image. 2048px longest side. */
    DISPUTE_EVIDENCE(2048);

    /** Maximum pixel size on the longest axis. The helper resizes down if
     *  the input exceeds this; smaller inputs pass through unchanged
     *  (no upscale). */
    public final int maxDim;

    ImagePurpose(int maxDim) {
        this.maxDim = maxDim;
    }
}
