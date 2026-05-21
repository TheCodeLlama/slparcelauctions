package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import com.slparcelauctions.backend.storage.ImagePurpose;
import com.slparcelauctions.backend.storage.ImageStorageContext;
import com.slparcelauctions.backend.storage.ImageStorageService;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredImage;
import com.slparcelauctions.backend.user.dto.UserDefaultCoverDto;

class UserDefaultCoverServiceTest {

    private UserRepository userRepository;
    private ObjectStorageService storage;
    private ImageStorageService imageStorage;
    private UserDefaultCoverService service;

    @BeforeEach
    void setup() {
        userRepository = mock(UserRepository.class);
        storage = mock(ObjectStorageService.class);
        imageStorage = mock(ImageStorageService.class);
        service = new UserDefaultCoverService(userRepository, storage, imageStorage);
    }

    private static User buildUser(Long id) {
        UUID pid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return User.builder()
                .id(id).publicId(pid)
                .email("a@b.c").username("alice").passwordHash("x")
                .build();
    }

    private static MockMultipartFile jpegFile() {
        return new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    /** Stubs the chokepoint to echo back the caller's key + ".webp". */
    private void stubChokepointEcho(long sizeBytes) {
        when(imageStorage.storeImage(any(), any(ImageStorageContext.class)))
                .thenAnswer(inv -> {
                    ImageStorageContext ctx = inv.getArgument(1);
                    return new StoredImage(ctx.objectKey() + ".webp", "image/webp", sizeBytes);
                });
    }

    @Test
    void upload_happyPath_chokepointWritesWebpAndUpdatesUser_withNoPriorCover() {
        User user = buildUser(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        stubChokepointEcho(3L);
        when(storage.presignGet(anyString(), any(Duration.class))).thenReturn("https://example/x");

        UserDefaultCoverDto dto = service.upload(42L, jpegFile());

        assertThat(user.getDefaultCoverLightObjectKey()).startsWith("users/42/default-cover-");
        // Output key has the .webp extension applied by the chokepoint.
        assertThat(user.getDefaultCoverLightObjectKey()).endsWith(".webp");
        assertThat(user.getDefaultCoverLightContentType()).isEqualTo("image/webp");
        assertThat(user.getDefaultCoverLightSizeBytes()).isEqualTo(3L);
        // Caller-supplied key has NO extension; the chokepoint appends one.
        ArgumentCaptor<ImageStorageContext> ctxCap =
                ArgumentCaptor.forClass(ImageStorageContext.class);
        verify(imageStorage).storeImage(any(), ctxCap.capture());
        assertThat(ctxCap.getValue().purpose()).isEqualTo(ImagePurpose.DEFAULT_COVER);
        assertThat(ctxCap.getValue().objectKey())
                .startsWith("users/42/default-cover-")
                .doesNotContain(".");
        // No prior key — no delete should happen.
        verify(storage, never()).delete(anyString());
        // ObjectStorageService.put is no longer called directly by this service.
        verify(storage, never()).put(anyString(), any(), anyString());
        assertThat(dto.url()).isEqualTo("https://example/x");
        assertThat(dto.contentType()).isEqualTo("image/webp");
        assertThat(dto.sizeBytes()).isEqualTo(3L);
    }

    @Test
    void upload_replacesExistingObjectAndUpdatesKey() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/42/default-cover-old-uuid.jpg");
        user.setDefaultCoverLightContentType("image/jpeg");
        user.setDefaultCoverLightSizeBytes(100L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        stubChokepointEcho(3L);
        when(storage.presignGet(anyString(), any(Duration.class))).thenReturn("https://example/x");

        UserDefaultCoverDto dto = service.upload(42L, jpegFile());

        assertThat(user.getDefaultCoverLightObjectKey()).startsWith("users/42/default-cover-");
        assertThat(user.getDefaultCoverLightObjectKey()).isNotEqualTo("users/42/default-cover-old-uuid.jpg");
        // The old .jpg key is what gets deleted — historical objects retain
        // their original extension on the row, the helper only changes new
        // writes going forward.
        verify(storage).delete("users/42/default-cover-old-uuid.jpg");
        assertThat(dto).isNotNull();
    }

    @Test
    void upload_oldKeyDeleteFails_swallowsAndReturns() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/42/default-cover-old.jpg");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        stubChokepointEcho(3L);
        when(storage.presignGet(anyString(), any(Duration.class))).thenReturn("https://example/x");
        doThrow(new RuntimeException("S3 boom")).when(storage).delete("users/42/default-cover-old.jpg");

        UserDefaultCoverDto dto = service.upload(42L, jpegFile());

        // Service does NOT propagate — the new key is already saved on the user
        // row. Orphaning the old object is acceptable; an S3 lifecycle policy can
        // sweep it later.
        assertThat(dto).isNotNull();
        assertThat(user.getDefaultCoverLightObjectKey()).startsWith("users/42/default-cover-");
    }

    @Test
    void upload_userNotFound_throws() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(42L, jpegFile()))
                .isInstanceOf(UserNotFoundException.class);

        verify(imageStorage, never()).storeImage(any(), any(ImageStorageContext.class));
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void get_unset_throwsNotFound() {
        User user = buildUser(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.get(42L))
                .isInstanceOf(UserDefaultCoverNotFoundException.class);
    }

    @Test
    void get_set_returnsDtoWithPresignedUrl() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/42/default-cover-abc.jpg");
        user.setDefaultCoverLightContentType("image/jpeg");
        user.setDefaultCoverLightSizeBytes(123L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(storage.presignGet(eq("users/42/default-cover-abc.jpg"), any(Duration.class)))
                .thenReturn("https://example/abc");

        UserDefaultCoverDto dto = service.get(42L);

        assertThat(dto.url()).isEqualTo("https://example/abc");
        assertThat(dto.contentType()).isEqualTo("image/jpeg");
        assertThat(dto.sizeBytes()).isEqualTo(123L);
    }

    @Test
    void delete_clearsColumnsAndS3() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/42/default-cover-abc.jpg");
        user.setDefaultCoverLightContentType("image/jpeg");
        user.setDefaultCoverLightSizeBytes(123L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        service.delete(42L);

        assertThat(user.getDefaultCoverLightObjectKey()).isNull();
        assertThat(user.getDefaultCoverLightContentType()).isNull();
        assertThat(user.getDefaultCoverLightSizeBytes()).isNull();
        verify(storage).delete("users/42/default-cover-abc.jpg");
    }

    @Test
    void delete_unsetIsIdempotent() {
        User user = buildUser(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        service.delete(42L);

        verify(storage, never()).delete(anyString());
    }

    @Test
    void delete_s3DeleteFails_userRowStillCleared() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/42/default-cover-abc.jpg");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("S3 boom")).when(storage).delete(anyString());

        service.delete(42L);

        // Even when S3 delete fails, the row must be cleared so the user
        // re-renders the empty state. Orphan object cleaned up later.
        assertThat(user.getDefaultCoverLightObjectKey()).isNull();
    }
}
