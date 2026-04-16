package com.slparcelauctions.backend.auction;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    List<Auction> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    /**
     * Parcel-locking check. Used by AuctionVerificationService before every
     * VERIFICATION_PENDING → ACTIVE transition.
     */
    boolean existsByParcelIdAndStatusInAndIdNot(
            Long parcelId, Collection<AuctionStatus> statuses, Long excludeAuctionId);

    /** Used by ParcelCodeExpiryJob to find stuck Method B auctions. */
    List<Auction> findByStatusAndVerificationMethod(
            AuctionStatus status, VerificationMethod verificationMethod);

    Optional<Auction> findByIdAndSellerId(Long id, Long sellerId);
}
