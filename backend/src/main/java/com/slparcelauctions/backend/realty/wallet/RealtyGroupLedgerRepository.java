package com.slparcelauctions.backend.realty.wallet;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RealtyGroupLedgerRepository
        extends JpaRepository<RealtyGroupLedgerEntry, Long> {

    Optional<RealtyGroupLedgerEntry> findByPublicId(UUID publicId);

    Optional<RealtyGroupLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    /**
     * Used by ListingFeeRefundProcessor to determine the origin wallet
     * for a listing-fee refund. The partial index
     * realty_group_ledger_listing_fee_lookup_idx makes this O(log n).
     */
    @Query("""
        SELECT e FROM RealtyGroupLedgerEntry e
        WHERE e.entryType = com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT
          AND e.refType = 'AUCTION'
          AND e.refId = :auctionId
        """)
    Optional<RealtyGroupLedgerEntry> findListingFeeDebitForAuction(@Param("auctionId") Long auctionId);

    @Query("""
        SELECT e FROM RealtyGroupLedgerEntry e
        WHERE e.groupId = :groupId
        ORDER BY e.createdAt DESC
        """)
    List<RealtyGroupLedgerEntry> findRecentForGroup(
        @Param("groupId") Long groupId, Pageable page);

    @Query("""
        SELECT e FROM RealtyGroupLedgerEntry e
        WHERE e.groupId = :groupId AND e.createdAt < :before
        ORDER BY e.createdAt DESC
        """)
    List<RealtyGroupLedgerEntry> findOlderForGroup(
        @Param("groupId") Long groupId,
        @Param("before") OffsetDateTime before,
        Pageable page);
}
