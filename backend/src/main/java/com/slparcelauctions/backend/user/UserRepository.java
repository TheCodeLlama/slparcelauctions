package com.slparcelauctions.backend.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findBySlAvatarUuid(UUID slAvatarUuid);

    boolean existsByEmail(String email);

    boolean existsBySlAvatarUuid(UUID slAvatarUuid);

    /**
     * Acquires a {@code PESSIMISTIC_WRITE} (i.e. {@code SELECT ... FOR UPDATE})
     * row lock on the user inside the caller's transaction. Used by the
     * cancellation flow so two concurrent cancellations on the same seller
     * serialise on the User row before reading
     * {@code CancellationLogRepository#countPriorOffensesWithBids} —
     * preventing two callers from each seeing the same prior count and
     * landing on the same ladder index.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
