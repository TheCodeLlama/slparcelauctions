package com.slparcelauctions.backend.storage;

import java.util.List;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.storage.exception.ObjectNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3ObjectStorageService implements ObjectStorageService {

    private final S3Client s3;
    private final StorageConfigProperties props;

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(props.bucket())
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes));
        log.debug("S3 put: bucket={} key={} size={}", props.bucket(), key, bytes.length);
    }

    /**
     * Loads the full object into memory as a {@code byte[]}. Appropriate for
     * avatar-sized PNGs (&lt;150KB each). <strong>Do NOT reuse this method for
     * larger objects</strong> (parcel photos, listing images, review photos)
     * without first refactoring to return a streaming
     * {@code ResponseInputStream<GetObjectResponse>} — a future caller that tries
     * to load a 5MB parcel photo via this method will blow the heap on a hot path.
     */
    @Override
    public StoredObject get(String key) {
        try {
            ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(key)
                            .build());
            return new StoredObject(
                    response.asByteArray(),
                    response.response().contentType(),
                    response.response().contentLength());
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(key, e);
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .build());
    }

    @Override
    public void deletePrefix(String keyPrefix) {
        String continuationToken = null;
        int totalDeleted = 0;
        while (true) {
            ListObjectsV2Request.Builder listBuilder = ListObjectsV2Request.builder()
                    .bucket(props.bucket())
                    .prefix(keyPrefix);
            if (continuationToken != null) {
                listBuilder.continuationToken(continuationToken);
            }
            ListObjectsV2Response listed = s3.listObjectsV2(listBuilder.build());
            if (!listed.contents().isEmpty()) {
                List<ObjectIdentifier> ids = listed.contents().stream()
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .toList();
                s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(props.bucket())
                        .delete(Delete.builder().objects(ids).build())
                        .build());
                totalDeleted += ids.size();
            }
            if (!Boolean.TRUE.equals(listed.isTruncated())) {
                break;
            }
            continuationToken = listed.nextContinuationToken();
        }
        log.info("Deleted {} objects under prefix '{}'", totalDeleted, keyPrefix);
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
