package com.slparcelauctions.backend.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findBySlAvatarUuid(UUID slAvatarUuid);

    boolean existsByEmail(String email);

    boolean existsBySlAvatarUuid(UUID slAvatarUuid);
}
