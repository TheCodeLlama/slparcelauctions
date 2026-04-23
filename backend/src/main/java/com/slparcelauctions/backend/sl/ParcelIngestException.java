package com.slparcelauctions.backend.sl;

/**
 * Thrown by {@link SlWorldApiClient} when the World API response cannot be
 * mapped to a valid {@code ParcelMetadata} — including unknown or missing
 * {@code maturityrating} values. Bubbles up to the caller;
 * {@link com.slparcelauctions.backend.parcel.ParcelLookupService} surfaces
 * as a parcel-lookup failure rather than silently storing an invalid row.
 */
public class ParcelIngestException extends RuntimeException {

    public ParcelIngestException(String message) {
        super(message);
    }

    public ParcelIngestException(String message, Throwable cause) {
        super(message, cause);
    }
}
