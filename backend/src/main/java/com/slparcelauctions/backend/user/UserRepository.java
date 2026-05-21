package com.slparcelauctions.backend.user;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    Optional<User> findByPublicId(UUID publicId);

    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:username)")
    Optional<User> findByUsername(@Param("username") String username);

    Optional<User> findByEmail(String email);

    Optional<User> findBySlAvatarUuid(UUID slAvatarUuid);

    List<User> findAllBySlAvatarUuidIn(Set<UUID> uuids);

    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE LOWER(u.username) = LOWER(:username)")
    boolean existsByUsername(@Param("username") String username);

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

    /**
     * Sub-project G section 6.1 -- batch resolver for {@code id -> publicId} used by
     * {@link com.slparcelauctions.backend.auction.AuctionDtoMapper.MapperBatchContext}
     * when projecting the winner's {@code publicId} into many DTOs at once.
     *
     * <p>One query per call regardless of input cardinality. Empty input is
     * accepted and returns an empty map.
     */
    @Query("SELECT u.id AS id, u.publicId AS publicId FROM User u WHERE u.id IN :ids")
    List<UserIdPublicIdProjection> findIdPublicIdProjections(@Param("ids") Set<Long> ids);

    default Map<Long, UUID> findPublicIdsByIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, UUID> out = new HashMap<>(ids.size());
        for (UserIdPublicIdProjection p : findIdPublicIdProjections(ids)) {
            out.put(p.getId(), p.getPublicId());
        }
        return out;
    }

    interface UserIdPublicIdProjection {
        Long getId();
        UUID getPublicId();
    }

    /**
     * Batch display-name lookup keyed by user id. Used by the seller-side
     * auction DTO mapper to populate {@code winnerDisplayName} on ENDED
     * rows so the seller's My Listings can render "Sold to @bob" without a
     * follow-up profile fetch. Falls back to {@code username} when
     * {@code displayName} is null so the projected value is never blank.
     *
     * <p>One query per call regardless of input cardinality. Empty input
     * accepted and returns an empty map.
     */
    @Query("SELECT u.id AS id, u.displayName AS displayName, u.username AS username "
         + "FROM User u WHERE u.id IN :ids")
    List<UserIdDisplayNameProjection> findIdDisplayNameProjections(@Param("ids") Set<Long> ids);

    default Map<Long, String> findDisplayNamesByIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>(ids.size());
        for (UserIdDisplayNameProjection p : findIdDisplayNameProjections(ids)) {
            String name = p.getDisplayName();
            if (name == null || name.isBlank()) {
                name = p.getUsername();
            }
            out.put(p.getId(), name);
        }
        return out;
    }

    interface UserIdDisplayNameProjection {
        Long getId();
        String getDisplayName();
        String getUsername();
    }

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.role = com.slparcelauctions.backend.user.Role.ADMIN " +
           "WHERE LOWER(u.username) IN :usernames AND u.role = com.slparcelauctions.backend.user.Role.USER")
    int bulkPromoteByUsernameIfUser(@Param("usernames") List<String> lowercaseUsernames);

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
        WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(COALESCE(u.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(COALESCE(u.slDisplayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
        ORDER BY u.createdAt DESC
        """)
    Page<User> searchAdminByText(@Param("search") String search, Pageable pageable);

    @Query("""
        SELECT u FROM User u
        WHERE u.slAvatarUuid = :uuid
        ORDER BY u.createdAt DESC
        """)
    Page<User> searchAdminByUuid(@Param("uuid") UUID uuid, Pageable pageable);

    @Query("""
        SELECT u FROM User u
        ORDER BY u.createdAt DESC
        """)
    Page<User> searchAdminAll(Pageable pageable);

    /**
     * Sum of all wallet balances. Used by ReconciliationService to compute the
     * expected SLParcels service avatar balance against the wallet pool.
     */
    @Query("SELECT COALESCE(SUM(u.balanceLindens), 0) FROM User u")
    long sumWalletBalances();

    /**
     * Sum of all reserved-lindens denorms. Used by ReconciliationService for
     * the denorm-drift precheck against {@code SUM(bid_reservations.amount
     * WHERE released_at IS NULL)}.
     */
    @Query("SELECT COALESCE(SUM(u.reservedLindens), 0) FROM User u")
    long sumReservedDenorms();

    /**
     * Users newly eligible for wallet-dormancy flagging: positive balance,
     * no active dormancy phase, and no refresh-token rotation within the
     * inactivity window. Mirrors the realty-group-side query (spec
     * docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md
     * §10.1) and is referenced by the user-wallet dormancy spec
     * (docs/superpowers/specs/2026-05-19-user-wallet-dormancy-design.md §2).
     *
     * <p>Users with no refresh tokens at all are correctly flagged: the NOT
     * EXISTS predicate is true on an empty right-hand side. Reserved L$ are
     * NOT filtered out at this layer -- a user with an active bid reservation
     * is still eligible; the phase-4 auto-return only debits the available
     * balance ({@code balance_lindens - reserved_lindens}).
     */
    @Query(value = """
        SELECT u.* FROM users u
        WHERE u.balance_lindens > 0
          AND u.wallet_dormancy_phase IS NULL
          AND NOT EXISTS (
              SELECT 1
              FROM refresh_tokens rt
              WHERE rt.user_id = u.id
                AND rt.created_at > now() - make_interval(days => :windowDays)
          )
        """, nativeQuery = true)
    List<User> findEligibleForDormancyFlag(@Param("windowDays") int windowDays);

    /**
     * Users whose current dormancy phase is due for the next escalation
     * ({@code wallet_dormancy_started_at} older than
     * {@code phaseDurationDays * wallet_dormancy_phase}). Mirrors the
     * realty-group-side query.
     */
    @Query(value = """
        SELECT u.* FROM users u
        WHERE u.wallet_dormancy_phase BETWEEN 1 AND 4
          AND u.wallet_dormancy_started_at < (now() - make_interval(days => :phaseDurationDays * u.wallet_dormancy_phase))
        """, nativeQuery = true)
    List<User> findDormancyPhaseDue(@Param("phaseDurationDays") int phaseDurationDays);

    /**
     * Users whose {@code createdAt} (cast to a calendar date) falls within
     * the inclusive range {@code [start, end]}. Drives the signup-window
     * backfill performed when an admin saves a coupon whose
     * {@code signupWindowStart}/{@code signupWindowEnd} cover historical
     * signups (spec section 5).
     */
    @Query("SELECT u FROM User u WHERE CAST(u.createdAt AS LocalDate) BETWEEN :start AND :end")
    List<User> findByCreatedAtDateBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
