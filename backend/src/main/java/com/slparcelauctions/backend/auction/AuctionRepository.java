package com.slparcelauctions.backend.auction;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    /**
     * Eagerly fetches {@code parcel} + {@code tags} so {@code AuctionDtoMapper}
     * calls downstream of a {@code @Transactional} boundary do not trip
     * {@link org.hibernate.LazyInitializationException} under
     * {@code spring.jpa.open-in-view=false}.
     */
    @EntityGraph(attributePaths = {"parcel", "tags"})
    List<Auction> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    /**
     * Parcel-locking check. Used by AuctionVerificationService before every
     * VERIFICATION_PENDING → ACTIVE transition.
     */
    boolean existsByParcelIdAndStatusInAndIdNot(
            Long parcelId, Collection<AuctionStatus> statuses, Long excludeAuctionId);

    /**
     * Identifies the auction currently holding the parcel-lock on a parcel so
     * {@code ParcelAlreadyListedException} can surface its ID in the 409
     * response. Paired with {@link #existsByParcelIdAndStatusInAndIdNot}.
     */
    Optional<Auction> findFirstByParcelIdAndStatusIn(
            Long parcelId, Collection<AuctionStatus> statuses);

    /** Used by ParcelCodeExpiryJob to find stuck Method B auctions. */
    List<Auction> findByStatusAndVerificationMethod(
            AuctionStatus status, VerificationMethod verificationMethod);

    /**
     * Eagerly fetches {@code parcel} + {@code tags} — see class-level note on
     * {@link #findBySellerIdOrderByCreatedAtDesc}.
     */
    @EntityGraph(attributePaths = {"parcel", "tags"})
    Optional<Auction> findByIdAndSellerId(Long id, Long sellerId);

    /**
     * Eagerly fetches {@code parcel} + {@code tags} — see class-level note on
     * {@link #findBySellerIdOrderByCreatedAtDesc}. Overrides the inherited
     * {@link JpaRepository#findById} so every load path — including seller,
     * public, and service-layer lookups — returns a fully-initialized aggregate.
     */
    @EntityGraph(attributePaths = {"parcel", "tags"})
    @Override
    Optional<Auction> findById(Long id);
}
