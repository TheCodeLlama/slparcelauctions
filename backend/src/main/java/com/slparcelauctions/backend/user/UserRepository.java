package com.slparcelauctions.backend.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Projection lookup that returns only the user id for an SL avatar
     * UUID, avoiding hydration of the full {@link User} entity into the
     * persistence context. Used by the SL terminal penalty endpoints
     * (Epic 08 sub-spec 2 §7.5 / §7.6) where the subsequent
     * {@link #findByIdForUpdate(Long)} call must read the freshly
     * locked row's state — Hibernate's session cache would otherwise
     * return the pre-lock entity instance with stale field values
     * and the lock would silently degrade to a no-op for the read
     * path.
     */
    @Query("SELECT u.id FROM User u WHERE u.slAvatarUuid = :slAvatarUuid")
    Optional<Long> findIdBySlAvatarUuid(@Param("slAvatarUuid") UUID slAvatarUuid);

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.role = com.slparcelauctions.backend.user.Role.ADMIN " +
           "WHERE u.email IN :emails AND u.role = com.slparcelauctions.backend.user.Role.USER")
    int bulkPromoteByEmailIfUser(@Param("emails") List<String> emails);
}
