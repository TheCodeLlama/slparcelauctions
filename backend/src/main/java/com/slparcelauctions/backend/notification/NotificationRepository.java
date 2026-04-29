package com.slparcelauctions.backend.notification;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read/write surface for the {@code notification} table.
 *
 * <p>Query shape follows the project's split-query pattern (see
 * {@code BidRepository}): filtered variants that accept a
 * {@code Collection<NotificationCategory>} are paired with separate
 * unfiltered variants to avoid JPQL's uncertain handling of
 * {@code :collection IS NULL} / HQL-on-Collection-IS-NULL footgun.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Paginated list of notifications for the given user filtered by the
     * categories belonging to a specific {@link NotificationGroup}. Callers
     * pass {@link NotificationGroup#categories()} as the collection.
     *
     * <p>The service calls {@link #findForUserUnfiltered} when no group filter
     * is requested — splitting the two shapes keeps each JPQL literal clean.
     */
    @Query("SELECT n FROM Notification n WHERE n.user.id = :userId " +
           "AND n.category IN :categoriesInGroup " +
           "AND (:unreadOnly = false OR n.read = false) " +
           "ORDER BY n.updatedAt DESC, n.id DESC")
    Page<Notification> findForUserByGroup(
            @Param("userId") Long userId,
            @Param("categoriesInGroup") Collection<NotificationCategory> categoriesInGroup,
            @Param("unreadOnly") boolean unreadOnly,
            Pageable pageable);

    /**
     * Unfiltered variant of {@link #findForUserByGroup} — used when the caller
     * does not supply a group filter. Kept as a separate query literal rather
     * than a nullable parameter to sidestep HQL's uncertain handling of
     * {@code :collection IS NULL}.
     */
    @Query("SELECT n FROM Notification n WHERE n.user.id = :userId " +
           "AND (:unreadOnly = false OR n.read = false) " +
           "ORDER BY n.updatedAt DESC, n.id DESC")
    Page<Notification> findForUserUnfiltered(
            @Param("userId") Long userId,
            @Param("unreadOnly") boolean unreadOnly,
            Pageable pageable);

    long countByUserIdAndReadFalse(Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    @Query("SELECT n.category, COUNT(n) FROM Notification n " +
           "WHERE n.user.id = :userId AND n.read = false GROUP BY n.category")
    List<Object[]> countUnreadByCategoryForUser(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true " +
           "WHERE n.id = :id AND n.user.id = :userId AND n.read = false")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true " +
           "WHERE n.user.id = :userId AND n.read = false " +
           "AND n.category IN :categoriesInGroup")
    int markAllReadByGroup(
            @Param("userId") Long userId,
            @Param("categoriesInGroup") Collection<NotificationCategory> categoriesInGroup);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true " +
           "WHERE n.user.id = :userId AND n.read = false")
    int markAllReadUnfiltered(@Param("userId") Long userId);

    /**
     * All notifications for a user, ordered by id descending (newest first).
     * Used by integration tests that need to assert on a user-scoped set.
     */
    @Query("SELECT n FROM Notification n WHERE n.user.id = :userId ORDER BY n.id DESC")
    List<Notification> findAllByUserId(@Param("userId") Long userId);

    /**
     * Bulk-deletes every notification for the given user. Used by integration-
     * test cleanup to drain the notification FK before deleting the user row.
     */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
