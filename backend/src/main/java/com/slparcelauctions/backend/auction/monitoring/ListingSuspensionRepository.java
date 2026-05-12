package com.slparcelauctions.backend.auction.monitoring;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link ListingSuspension}.
 *
 * <p>Sub-project F spec §8, §10.
 *
 * <p>JPQL note: {@code Auction} and {@code RealtyGroupSlGroup} both store the parent realty
 * group as a plain {@code Long realtyGroupId} column rather than a {@code @ManyToOne}
 * association, so navigation uses {@code a.realtyGroupId} / {@code rsg.realtyGroupId}
 * rather than {@code a.realtyGroup.id} / {@code rsg.realtyGroup.id}.
 */
public interface ListingSuspensionRepository extends JpaRepository<ListingSuspension, Long> {

    Optional<ListingSuspension> findByPublicId(UUID publicId);

    /**
     * Active group-bulk listing suspensions older than {@code :threshold} (i.e. the bulk
     * 48 h auto-cancel timer has elapsed). Consumed by {@code BulkSuspendedListingExpiryTask}.
     */
    @Query("""
        SELECT ls FROM ListingSuspension ls
         WHERE ls.cause = com.slparcelauctions.backend.auction.monitoring.ListingSuspensionCause.ADMIN_GROUP_BULK
           AND ls.liftedAt IS NULL
           AND ls.cancelledAt IS NULL
           AND ls.suspendedAt < :threshold
    """)
    List<ListingSuspension> findExpiredBulkSuspends(@Param("threshold") OffsetDateTime threshold);

    /**
     * Active group-bulk listing suspensions for the given realty group, joining through
     * both case-1 ({@code a.realtyGroupId}) and case-3 ({@code rsg.realtyGroupId} via
     * {@code RealtyGroupSlGroup}) discriminators. Consumed by the bulk reinstate path.
     */
    @Query("""
        SELECT ls FROM ListingSuspension ls
         JOIN ls.auction a
         LEFT JOIN com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup rsg
                ON rsg.id = a.realtyGroupSlGroupId
         WHERE ls.cause = com.slparcelauctions.backend.auction.monitoring.ListingSuspensionCause.ADMIN_GROUP_BULK
           AND ls.liftedAt IS NULL
           AND ls.cancelledAt IS NULL
           AND (a.realtyGroupId = :groupId OR rsg.realtyGroupId = :groupId)
    """)
    List<ListingSuspension> findActiveBulkSuspensionsForGroup(@Param("groupId") Long groupId);

    List<ListingSuspension> findByAuctionId(Long auctionId);
}
