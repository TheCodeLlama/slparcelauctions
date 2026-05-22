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

import com.slparcelauctions.backend.common.image.ImageVariant;
import com.slparcelauctions.backend.storage.ImagePurpose;
import com.slparcelauctions.backend.storage.ImageStorageContext;
import com.slparcelauctions.backend.storage.ImageStorageService;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredImage;
import com.slparcelauctions.backend.user.dto.UserDefaultCoverDto;

class UserDefaultCoverServiceTest {

    private static final UUID PID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        return User.builder()
                .id(id).publicId(PID)
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
    void upload_light_happyPath_chokepointWritesWebpAndUpdatesUser_withNoPriorCover() {
        User user = buildUser(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        stubChokepointEcho(3L);
        when(storage.presignGet(anyString(), any(Duration.class))).thenReturn("https://example/x");

        UserDefaultCoverDto dto = service.upload(42L, ImageVariant.LIGHT, jpegFile());

        assertThat(user.getDefaultCoverLightObjectKey())
                .isEqualTo("users/" + PID + "/default-cover-light.webp");
        assertThat(user.getDefaultCoverLightContentType()).isEqualTo("image/webp");
        assertThat(user.getDefaultCoverLightSizeBytes()).isEqualTo(3L);
        // Dark slot untouched.
        assertThat(user.getDefaultCoverDarkObjectKey()).isNull();
        assertThat(user.getDefaultCoverDarkContentType()).isNull();
        assertThat(user.getDefaultCoverDarkSizeBytes()).isNull();
        // Caller-supplied key has NO extension; the chokepoint appends one.
        ArgumentCaptor<ImageStorageContext> ctxCap =
                ArgumentCaptor.forClass(ImageStorageContext.class);
        verify(imageStorage).storeImage(any(), ctxCap.capture());
        assertThat(ctxCap.getValue().purpose()).isEqualTo(ImagePurpose.DEFAULT_COVER);
        assertThat(ctxCap.getValue().objectKey())
                .isEqualTo("users/" + PID + "/default-cover-light")
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
    void upload_dark_writesDarkSlotAndLeavesLightSlotAlone() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/" + PID + "/default-cover-light.webp");
        user.setDefaultCoverLightContentType("image/webp");
        user.setDefaultCoverLightSizeBytes(99L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        stubChokepointEcho(5L);
        when(storage.presignGet(anyString(), any(Duration.class))).thenReturn("https://example/d");

        UserDefaultCoverDto dto = service.upload(42L, ImageVariant.DARK, jpegFile());

        assertThat(user.getDefaultCoverDarkObjectKey())
                .isEqualTo("users/" + PID + "/default-cover-dark.webp");
        assertThat(user.getDefaultCoverDarkContentType()).isEqualTo("image/webp");
        assertThat(user.getDefaultCoverDarkSizeBytes()).isEqualTo(5L);
        // Light slot preserved.
        assertThat(user.getDefaultCoverLightObjectKey())
                .isEqualTo("users/" + PID + "/default-cover-light.webp");
        assertThat(user.getDefaultCoverLightContentType()).isEqualTo("image/webp");
        assertThat(user.getDefaultCoverLightSizeBytes()).isEqualTo(99L);
        verify(storage, never()).delete(anyString());
        assertThat(dto.url()).isEqualTo("https://example/d");
    }

    @Test
    void upload_overwriteSameVariantKey_doesNotDeletePriorObject() {
        User user = buildUser(42L);
        // Already populated with the canonical light key; overwrite must NOT
        // call delete on the same key it's about to PUT.
        user.setDefaultCoverLightObjectKey("users/" + PID + "/default-cover-light.webp");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        stubChokepointEcho(3L);
        when(storage.presignGet(anyString(), any(Duration.class))).thenReturn("https://example/x");

        service.upload(42L, ImageVariant.LIGHT, jpegFile());

        verify(storage, never()).delete(anyString());
    }

    @Test
    void upload_replacesLegacyUuidSuffixedKey_deletesIt() {
        User user = buildUser(42L);
        // Legacy row from before plan Task 4 — UUID-suffixed key on the
        // light column. The new write uses the canonical
        // {publicId}/default-cover-light.webp key, so the legacy object
        // must be purged.
        user.setDefaultCoverLightObjectKey("users/42/default-cover-old-uuid.jpg");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        stubChokepointEcho(3L);
        when(storage.presignGet(anyString(), any(Duration.class))).thenReturn("https://example/x");

        service.upload(42L, ImageVariant.LIGHT, jpegFile());

        assertThat(user.getDefaultCoverLightObjectKey())
                .isEqualTo("users/" + PID + "/default-cover-light.webp");
        verify(storage).delete("users/42/default-cover-old-uuid.jpg");
    }

    @Test
    void upload_oldKeyDeleteFails_swallowsAndReturns() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/42/default-cover-old.jpg");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        stubChokepointEcho(3L);
        when(storage.presignGet(anyString(), any(Duration.class))).thenReturn("https://example/x");
        doThrow(new RuntimeException("S3 boom")).when(storage).delete("users/42/default-cover-old.jpg");

        UserDefaultCoverDto dto = service.upload(42L, ImageVariant.LIGHT, jpegFile());

        // Service does NOT propagate — the new key is already saved on the user
        // row. Orphaning the old object is acceptable; an S3 lifecycle policy can
        // sweep it later.
        assertThat(dto).isNotNull();
        assertThat(user.getDefaultCoverLightObjectKey())
                .isEqualTo("users/" + PID + "/default-cover-light.webp");
    }

    @Test
    void upload_userNotFound_throws() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(42L, ImageVariant.LIGHT, jpegFile()))
                .isInstanceOf(UserNotFoundException.class);

        verify(imageStorage, never()).storeImage(any(), any(ImageStorageContext.class));
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void get_light_unset_throwsNotFound() {
        User user = buildUser(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.get(42L, ImageVariant.LIGHT))
                .isInstanceOf(UserDefaultCoverNotFoundException.class);
    }

    @Test
    void get_dark_unset_throwsNotFound_evenWhenLightSet() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/" + PID + "/default-cover-light.webp");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.get(42L, ImageVariant.DARK))
                .isInstanceOf(UserDefaultCoverNotFoundException.class);
    }

    @Test
    void get_light_set_returnsDtoWithPresignedUrl() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/" + PID + "/default-cover-light.webp");
        user.setDefaultCoverLightContentType("image/webp");
        user.setDefaultCoverLightSizeBytes(123L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(storage.presignGet(eq("users/" + PID + "/default-cover-light.webp"), any(Duration.class)))
                .thenReturn("https://example/abc");

        UserDefaultCoverDto dto = service.get(42L, ImageVariant.LIGHT);

        assertThat(dto.url()).isEqualTo("https://example/abc");
        assertThat(dto.contentType()).isEqualTo("image/webp");
        assertThat(dto.sizeBytes()).isEqualTo(123L);
    }

    @Test
    void get_dark_set_returnsDtoWithPresignedUrl() {
        User user = buildUser(42L);
        user.setDefaultCoverDarkObjectKey("users/" + PID + "/default-cover-dark.webp");
        user.setDefaultCoverDarkContentType("image/webp");
        user.setDefaultCoverDarkSizeBytes(55L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(storage.presignGet(eq("users/" + PID + "/default-cover-dark.webp"), any(Duration.class)))
                .thenReturn("https://example/dark");

        UserDefaultCoverDto dto = service.get(42L, ImageVariant.DARK);

        assertThat(dto.url()).isEqualTo("https://example/dark");
        assertThat(dto.contentType()).isEqualTo("image/webp");
        assertThat(dto.sizeBytes()).isEqualTo(55L);
    }

    @Test
    void delete_light_clearsLightColumnsAndS3_leavesDarkSlotIntact() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/" + PID + "/default-cover-light.webp");
        user.setDefaultCoverLightContentType("image/webp");
        user.setDefaultCoverLightSizeBytes(123L);
        user.setDefaultCoverDarkObjectKey("users/" + PID + "/default-cover-dark.webp");
        user.setDefaultCoverDarkContentType("image/webp");
        user.setDefaultCoverDarkSizeBytes(55L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        service.delete(42L, ImageVariant.LIGHT);

        assertThat(user.getDefaultCoverLightObjectKey()).isNull();
        assertThat(user.getDefaultCoverLightContentType()).isNull();
        assertThat(user.getDefaultCoverLightSizeBytes()).isNull();
        // Dark slot preserved.
        assertThat(user.getDefaultCoverDarkObjectKey())
                .isEqualTo("users/" + PID + "/default-cover-dark.webp");
        assertThat(user.getDefaultCoverDarkContentType()).isEqualTo("image/webp");
        assertThat(user.getDefaultCoverDarkSizeBytes()).isEqualTo(55L);
        verify(storage).delete("users/" + PID + "/default-cover-light.webp");
    }

    @Test
    void delete_dark_clearsDarkColumnsAndS3_leavesLightSlotIntact() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/" + PID + "/default-cover-light.webp");
        user.setDefaultCoverLightContentType("image/webp");
        user.setDefaultCoverLightSizeBytes(123L);
        user.setDefaultCoverDarkObjectKey("users/" + PID + "/default-cover-dark.webp");
        user.setDefaultCoverDarkContentType("image/webp");
        user.setDefaultCoverDarkSizeBytes(55L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        service.delete(42L, ImageVariant.DARK);

        assertThat(user.getDefaultCoverDarkObjectKey()).isNull();
        assertThat(user.getDefaultCoverDarkContentType()).isNull();
        assertThat(user.getDefaultCoverDarkSizeBytes()).isNull();
        // Light slot preserved.
        assertThat(user.getDefaultCoverLightObjectKey())
                .isEqualTo("users/" + PID + "/default-cover-light.webp");
        verify(storage).delete("users/" + PID + "/default-cover-dark.webp");
    }

    @Test
    void delete_unsetIsIdempotent() {
        User user = buildUser(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        service.delete(42L, ImageVariant.LIGHT);
        service.delete(42L, ImageVariant.DARK);

        verify(storage, never()).delete(anyString());
    }

    @Test
    void delete_s3DeleteFails_userRowStillCleared() {
        User user = buildUser(42L);
        user.setDefaultCoverLightObjectKey("users/" + PID + "/default-cover-light.webp");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("S3 boom")).when(storage).delete(anyString());

        service.delete(42L, ImageVariant.LIGHT);

        // Even when S3 delete fails, the row must be cleared so the user
        // re-renders the empty state. Orphan object cleaned up later.
        assertThat(user.getDefaultCoverLightObjectKey()).isNull();
    }

    @Test
    void fetchBytes_light_unset_throwsNotFound() {
        User user = buildUser(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.fetchBytes(42L, ImageVariant.LIGHT))
                .isInstanceOf(UserDefaultCoverNotFoundException.class);
    }

    @Test
    void fetchBytes_dark_set_returnsObjectFromCorrectSlot() {
        User user = buildUser(42L);
        user.setDefaultCoverDarkObjectKey("users/" + PID + "/default-cover-dark.webp");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        service.fetchBytes(42L, ImageVariant.DARK);

        verify(storage).get("users/" + PID + "/default-cover-dark.webp");
    }
}
