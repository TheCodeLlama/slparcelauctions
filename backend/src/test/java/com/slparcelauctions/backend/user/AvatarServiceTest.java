package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockMultipartFile;

import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.storage.exception.ObjectNotFoundException;
import com.slparcelauctions.backend.user.dto.UserResponse;
import com.slparcelauctions.backend.user.exception.AvatarTooLargeException;
import com.slparcelauctions.backend.user.exception.InvalidAvatarSizeException;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

class AvatarServiceTest {

    private UserRepository userRepository;
    private ObjectStorageService storage;
    private AvatarImageProcessor processor;
    private AvatarService service;

    @BeforeEach
    void setup() {
        userRepository = mock(UserRepository.class);
        storage = mock(ObjectStorageService.class);
        processor = mock(AvatarImageProcessor.class);
        service = new AvatarService(userRepository, storage, processor, new DefaultResourceLoader());
    }

    @Test
    void upload_happyPath_putsThreeObjectsAndUpdatesUser() {
        UUID userPublicId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        User user = User.builder()
                .id(1L).publicId(userPublicId).email("a@b.c").passwordHash("x").verified(false).build();
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{1, 2, 3});
        Map<Integer, byte[]> resized = Map.of(
                64, new byte[]{10},
                128, new byte[]{20},
                256, new byte[]{30});
        when(processor.process(any(byte[].class))).thenReturn(resized);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserResponse resp = service.upload(1L, file);

        verify(storage).put(eq("avatars/1/64.png"), any(byte[].class), eq("image/png"));
        verify(storage).put(eq("avatars/1/128.png"), any(byte[].class), eq("image/png"));
        verify(storage).put(eq("avatars/1/256.png"), any(byte[].class), eq("image/png"));
        String expectedUrl = "/api/v1/users/" + userPublicId + "/avatar/256";
        assertThat(user.getProfilePicUrl()).isEqualTo(expectedUrl);
        assertThat(resp.profilePicUrl()).isEqualTo(expectedUrl);
    }

    @Test
    void upload_oversizedFile_throwsAvatarTooLarge() {
        byte[] oversized = new byte[(int) (2 * 1024 * 1024) + 1];
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", oversized);

        assertThatThrownBy(() -> service.upload(1L, file))
                .isInstanceOf(AvatarTooLargeException.class);
        verify(processor, never()).process(any());
        verify(storage, never()).put(any(), any(), any());
    }

    @Test
    void upload_unsupportedFormat_propagatesFromProcessor() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.bmp", "image/bmp", new byte[]{1, 2, 3});
        when(processor.process(any(byte[].class)))
                .thenThrow(new UnsupportedImageFormatException("bmp not allowed"));

        assertThatThrownBy(() -> service.upload(1L, file))
                .isInstanceOf(UnsupportedImageFormatException.class);
        verify(storage, never()).put(any(), any(), any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void fetch_userDoesNotExist_throwsUserNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fetch(999L, 128))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void fetch_userHasNoAvatar_returnsPlaceholder() {
        User user = User.builder().id(1L).profilePicUrl(null).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        StoredObject result = service.fetch(1L, 128);

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.bytes()).isNotEmpty();
        verify(storage, never()).get(any());
    }

    @Test
    void fetch_userHasAvatar_returnsProxiedBytes() {
        User user = User.builder().id(1L).profilePicUrl("/api/v1/users/1/avatar/256").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        byte[] stored = new byte[]{50, 51, 52};
        when(storage.get("avatars/1/128.png"))
                .thenReturn(new StoredObject(stored, "image/png", 3L));

        StoredObject result = service.fetch(1L, 128);

        assertThat(result.bytes()).isEqualTo(stored);
    }

    @Test
    void fetch_orphanedProfilePicUrl_returnsPlaceholder() {
        User user = User.builder().id(1L).profilePicUrl("/api/v1/users/1/avatar/256").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(storage.get("avatars/1/128.png"))
                .thenThrow(new ObjectNotFoundException("avatars/1/128.png", null));

        StoredObject result = service.fetch(1L, 128);

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.bytes()).isNotEmpty();
    }

    @Test
    void fetch_invalidSize_throwsInvalidAvatarSize() {
        assertThatThrownBy(() -> service.fetch(1L, 99))
                .isInstanceOf(InvalidAvatarSizeException.class);
        verify(userRepository, never()).findById(any());
    }
}
