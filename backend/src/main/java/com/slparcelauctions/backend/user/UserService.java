package com.slparcelauctions.backend.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UpdateUserRequest;
import com.slparcelauctions.backend.user.dto.UserProfileResponse;
import com.slparcelauctions.backend.user.dto.UserResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw UserAlreadyExistsException.email(request.email());
        }

        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName())
                .build();

        User saved = userRepository.save(user);
        log.info("Created user id={} email={}", saved.getId(), saved.getEmail());
        return UserResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        return UserResponse.from(loadUser(id));
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getPublicProfile(Long id) {
        return UserProfileResponse.from(loadUser(id));
    }

    @Transactional
    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        User user = loadUser(id);
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.bio() != null) {
            user.setBio(request.bio());
        }
        return UserResponse.from(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = loadUser(id);
        userRepository.delete(user);
        log.info("Deleted user id={}", id);
    }

    private User loadUser(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }
}
