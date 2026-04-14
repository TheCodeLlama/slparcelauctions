package com.slparcelauctions.backend.storage;

public interface ObjectStorageService {

    /** Puts a single object. Overwrites any existing object at the same key. */
    void put(String key, byte[] bytes, String contentType);

    /**
     * Fetches a single object's bytes. Throws {@link com.slparcelauctions.backend.storage.exception.ObjectNotFoundException}
     * on S3 404.
     */
    StoredObject get(String key);

    /** Deletes a single object. No-op if the key doesn't exist (S3 delete is idempotent). */
    void delete(String key);

    /**
     * Batch-deletes every object under the given key prefix. Paginates via
     * {@code isTruncated} + continuation token so >1000 objects are handled.
     */
    void deletePrefix(String keyPrefix);

    /** Returns true if the object exists at the given key. */
    boolean exists(String key);
}
