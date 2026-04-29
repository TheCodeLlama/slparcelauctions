package com.slparcelauctions.backend.user;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    List<User> findAllBySlAvatarUuidIn(Set<UUID> uuids);

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

    /**
     * Atomically increments {@code tokenVersion} for the given user, invalidating
     * all live access tokens within their 15-minute window. Called on AVATAR/BOTH
     * ban creation to force re-authentication of the banned user's active sessions.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.tokenVersion = u.tokenVersion + 1 WHERE u.id = :userId")
    int bumpTokenVersion(@Param("userId") Long userId);

    /**
     * Returns every user with the given role. Used by {@code ReconciliationService}
     * to fan out {@code RECONCILIATION_MISMATCH} notifications to all admins.
     */
    List<User> findByRole(Role role);

    @Query("""
        SELECT u FROM User u
        WHERE (:uuid IS NOT NULL AND u.slAvatarUuid = :uuid)
           OR (:uuid IS NULL AND :search IS NOT NULL AND
               (LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(COALESCE(u.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(COALESCE(u.slDisplayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))))
           OR (:search IS NULL AND :uuid IS NULL)
        ORDER BY u.createdAt DESC
        """)
    Page<User> searchAdmin(@Param("search") String search, @Param("uuid") UUID uuidOrNull, Pageable pageable);
}
