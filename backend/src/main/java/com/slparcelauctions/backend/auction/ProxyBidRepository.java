package com.slparcelauctions.backend.auction;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProxyBidRepository extends JpaRepository<ProxyBid, Long> {

    /**
     * Loads the most recent proxy row for a {@code (auction, bidder)} pair
     * regardless of status. Used by the My Bids view and to check whether a
     * user previously cancelled a proxy on the auction.
     */
    Optional<ProxyBid> findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(
            Long auctionId, Long bidderId);

    /**
     * Partial-unique-index companion: {@code true} if the bidder already owns
     * an {@link ProxyBidStatus#ACTIVE} proxy on the auction. Lets the service
     * layer reject create-proxy requests before a DB-level integrity violation.
     */
    boolean existsByAuctionIdAndBidderIdAndStatus(
            Long auctionId, Long bidderId, ProxyBidStatus status);

    /**
     * Finds the single active proxy owned by someone OTHER than the supplied
     * user on the given auction. Used by the proxy engine after a manual bid
     * to decide whether an auto-reply should fire.
     */
    Optional<ProxyBid> findFirstByAuctionIdAndStatusAndBidderIdNot(
            Long auctionId, ProxyBidStatus status, Long excludedBidderId);

    /**
     * Bulk-marks every {@link ProxyBidStatus#ACTIVE} proxy on an auction as
     * {@link ProxyBidStatus#EXHAUSTED}. Called by the auction-end flow so the
     * partial unique index is cleared and no zombie proxies linger. Returns
     * the number of rows updated.
     */
    @Modifying
    @Query("UPDATE ProxyBid p SET p.status = com.slparcelauctions.backend.auction.ProxyBidStatus.EXHAUSTED, "
            + "p.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE p.auction.id = :auctionId AND p.status = com.slparcelauctions.backend.auction.ProxyBidStatus.ACTIVE")
    int exhaustAllActiveByAuctionId(@Param("auctionId") Long auctionId);

    /**
     * Returns the caller's proxy rows of the given {@code status} on every
     * auction in {@code auctionIds}. Used by the My Bids dashboard to hydrate
     * the {@code myProxyMaxAmount} column — only {@code ACTIVE} proxies are
     * surfaced on the dashboard (an EXHAUSTED or CANCELLED proxy doesn't
     * modify the caller's current cap).
     */
    List<ProxyBid> findByBidderIdAndAuctionIdInAndStatus(
            Long bidderId, Collection<Long> auctionIds, ProxyBidStatus status);

    /**
     * Bulk-deletes every proxy row for the given auction. Test-only helper
     * used by integration-test cleanup to avoid raw JDBC {@code DELETE}
     * statements that would silently stop covering new FK-child tables as
     * Epic 05 lands.
     */
    @Modifying
    @Transactional
    int deleteAllByAuctionId(Long auctionId);
}
