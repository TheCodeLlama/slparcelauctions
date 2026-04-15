package com.slparcelauctions.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.storage.exception.ObjectNotFoundException;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

class S3ObjectStorageServiceTest {

    private S3Client s3;
    private StorageConfigProperties props;
    private S3ObjectStorageService service;

    @BeforeEach
    void setup() {
        s3 = mock(S3Client.class);
        props = new StorageConfigProperties(
                "test-bucket", "us-east-1", null, false, null, null);
        service = new S3ObjectStorageService(s3, props);
    }

    @Test
    void put_callsS3WithCorrectBucketKeyContentType() {
        byte[] bytes = new byte[]{1, 2, 3};

        service.put("avatars/1/256.png", bytes, "image/png");

        ArgumentCaptor<PutObjectRequest> reqCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(reqCaptor.capture(), any(RequestBody.class));
        PutObjectRequest captured = reqCaptor.getValue();
        assertThat(captured.bucket()).isEqualTo("test-bucket");
        assertThat(captured.key()).isEqualTo("avatars/1/256.png");
        assertThat(captured.contentType()).isEqualTo("image/png");
        assertThat(captured.contentLength()).isEqualTo(3L);
    }

    @Test
    void get_happyPath_returnsStoredObject() {
        byte[] bytes = new byte[]{10, 20, 30};
        GetObjectResponse response = GetObjectResponse.builder()
                .contentType("image/png")
                .contentLength(3L)
                .build();
        ResponseBytes<GetObjectResponse> responseBytes = ResponseBytes.fromByteArray(response, bytes);
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

        StoredObject result = service.get("avatars/1/256.png");

        assertThat(result.bytes()).isEqualTo(bytes);
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.contentLength()).isEqualTo(3L);
    }

    @Test
    void get_noSuchKey_throwsObjectNotFound() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOf(ObjectNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void delete_callsS3WithKey() {
        service.delete("avatars/1/256.png");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(captor.getValue().key()).isEqualTo("avatars/1/256.png");
    }

    @Test
    void exists_returnsTrueWhenHeadSucceeds() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        assertThat(service.exists("avatars/1/256.png")).isTrue();
    }

    @Test
    void exists_returnsFalseOnNoSuchKey() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertThat(service.exists("missing")).isFalse();
    }

    @Test
    void deletePrefix_paginatesAcrossMultipleBatches() {
        // First call: 2 objects + isTruncated=true
        ListObjectsV2Response firstPage = ListObjectsV2Response.builder()
                .contents(
                        S3Object.builder().key("avatars/1/64.png").build(),
                        S3Object.builder().key("avatars/1/128.png").build())
                .isTruncated(true)
                .nextContinuationToken("TOKEN")
                .build();
        // Second call: 1 object + isTruncated=false
        ListObjectsV2Response secondPage = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("avatars/1/256.png").build())
                .isTruncated(false)
                .build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(firstPage, secondPage);

        service.deletePrefix("avatars/1/");

        // Two listObjectsV2 calls (initial page + continuation token page)
        verify(s3, org.mockito.Mockito.times(2)).listObjectsV2(any(ListObjectsV2Request.class));
        // Two delete calls (one per page), each with the page's objects
        ArgumentCaptor<DeleteObjectsRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3, org.mockito.Mockito.times(2)).deleteObjects(deleteCaptor.capture());
        assertThat(deleteCaptor.getAllValues()).hasSize(2);
        assertThat(deleteCaptor.getAllValues().get(0).delete().objects()).hasSize(2);
        assertThat(deleteCaptor.getAllValues().get(1).delete().objects()).hasSize(1);
    }

    @Test
    void deletePrefix_emptyPrefix_makesNoDeleteCall() {
        ListObjectsV2Response emptyPage = ListObjectsV2Response.builder()
                .contents(java.util.Collections.emptyList())
                .isTruncated(false)
                .build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(emptyPage);

        service.deletePrefix("avatars/99/");

        verify(s3, org.mockito.Mockito.never()).deleteObjects(any(DeleteObjectsRequest.class));
    }
}
