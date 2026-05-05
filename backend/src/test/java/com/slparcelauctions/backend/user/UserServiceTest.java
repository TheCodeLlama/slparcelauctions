package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.slparcelauctions.backend.notification.NotificationService;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UpdateUserRequest;
import com.slparcelauctions.backend.user.dto.UserProfileResponse;
import com.slparcelauctions.backend.user.dto.UserResponse;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private UserService userService;

    @Test
    void createUser_hashesPasswordAndPersists() {
        CreateUserRequest request = new CreateUserRequest("alice", "password123!");
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("password123!")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            try {
                java.lang.reflect.Field idField = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(saved, 1L);
                java.lang.reflect.Field createdAtField = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("createdAt");
                createdAtField.setAccessible(true);
                createdAtField.set(saved, java.time.OffsetDateTime.now());
            } catch (Exception e) { throw new RuntimeException(e); }
            return saved;
        });

        UserResponse response = userService.createUser(request);

        assertThat(response.publicId()).isNotNull();
        assertThat(response.username()).isEqualTo("alice");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void createUser_duplicateUsername_throws() {
        CreateUserRequest request = new CreateUserRequest("dup", "password123!");
        when(userRepository.existsByUsername("dup")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void getUserById_found_returnsResponse() {
        User user = User.builder()
                .id(7L)
                .email("bob@example.com")
                .username("bob")
                .passwordHash("hash")
                .displayName("Bob")
                .build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        UserResponse response = userService.getUserById(7L);

        assertThat(response.publicId()).isNotNull();
        assertThat(response.username()).isEqualTo("bob");
    }

    @Test
    void getUserById_missing_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(99L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void getPublicProfile_returnsProfile() {
        User user = User.builder()
                .id(3L)
                .username("carol")
                .passwordHash("hash")
                .displayName("Carol")
                .bio("hi")
                .build();
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));

        UserProfileResponse profile = userService.getPublicProfile(3L);

        assertThat(profile.publicId()).isNotNull();
        assertThat(profile.displayName()).isEqualTo("Carol");
        assertThat(profile.bio()).isEqualTo("hi");
    }

    @Test
    void updateUser_mutatesEntityForDirtyCheckingWithoutCallingSave() {
        User user = User.builder()
                .id(2L)
                .username("dave")
                .passwordHash("hash")
                .displayName("Dave")
                .bio("old bio")
                .build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        UpdateUserRequest update = new UpdateUserRequest("Dave the Great", "new bio");
        UserResponse response = userService.updateUser(2L, update);

        assertThat(user.getDisplayName()).isEqualTo("Dave the Great");
        assertThat(user.getBio()).isEqualTo("new bio");
        assertThat(response.displayName()).isEqualTo("Dave the Great");
        assertThat(response.bio()).isEqualTo("new bio");
        verify(userRepository).findById(2L);
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void updateUser_nullFieldsLeaveExistingValuesUntouched() {
        User user = User.builder()
                .id(8L)
                .username("eve")
                .passwordHash("hash")
                .displayName("Eve")
                .bio("keep me")
                .build();
        when(userRepository.findById(8L)).thenReturn(Optional.of(user));

        UpdateUserRequest partial = new UpdateUserRequest(null, null);
        userService.updateUser(8L, partial);

        assertThat(user.getDisplayName()).isEqualTo("Eve");
        assertThat(user.getBio()).isEqualTo("keep me");
    }

    @Test
    void deleteUser_deletesEntity() {
        User user = User.builder().id(5L).username("eee").passwordHash("h").build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));

        userService.deleteUser(5L);

        verify(userRepository, times(1)).delete(user);
    }

    @Test
    void bumpTokenVersion_incrementsByOne() {
        User user = User.builder()
                .id(10L)
                .username("frank")
                .passwordHash("hash")
                .tokenVersion(3L)
                .build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));

        userService.bumpTokenVersion(10L);

        assertThat(user.getTokenVersion()).isEqualTo(4L);
        verify(userRepository).findById(10L);
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void bumpTokenVersion_throwsWhenUserMissing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.bumpTokenVersion(99L))
                .isInstanceOf(UserNotFoundException.class);
    }
}
