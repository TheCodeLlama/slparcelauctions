package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

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

    @InjectMocks
    private UserService userService;

    @Test
    void createUser_hashesPasswordAndPersists() {
        CreateUserRequest request = new CreateUserRequest("alice@example.com", "password123", "Alice");
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(1L);
            u.setCreatedAt(OffsetDateTime.now());
            return u;
        });

        UserResponse response = userService.createUser(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.displayName()).isEqualTo("Alice");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void createUser_duplicateEmail_throws() {
        CreateUserRequest request = new CreateUserRequest("dup@example.com", "password123", null);
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void getUserById_found_returnsResponse() {
        User user = User.builder()
                .id(7L)
                .email("bob@example.com")
                .passwordHash("hash")
                .displayName("Bob")
                .build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        UserResponse response = userService.getUserById(7L);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.email()).isEqualTo("bob@example.com");
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
                .email("carol@example.com")
                .passwordHash("hash")
                .displayName("Carol")
                .bio("hi")
                .build();
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));

        UserProfileResponse profile = userService.getPublicProfile(3L);

        assertThat(profile.id()).isEqualTo(3L);
        assertThat(profile.displayName()).isEqualTo("Carol");
        assertThat(profile.bio()).isEqualTo("hi");
    }

    @Test
    void updateUser_updatesDisplayNameAndBio() {
        User user = User.builder()
                .id(2L)
                .email("dave@example.com")
                .passwordHash("hash")
                .displayName("Dave")
                .build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        UpdateUserRequest update = new UpdateUserRequest("Dave the Great", "new bio");
        UserResponse response = userService.updateUser(2L, update);

        assertThat(response.displayName()).isEqualTo("Dave the Great");
        assertThat(response.bio()).isEqualTo("new bio");
    }

    @Test
    void deleteUser_deletesEntity() {
        User user = User.builder().id(5L).email("e@e.com").passwordHash("h").build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));

        userService.deleteUser(5L);

        verify(userRepository, times(1)).delete(user);
    }
}
