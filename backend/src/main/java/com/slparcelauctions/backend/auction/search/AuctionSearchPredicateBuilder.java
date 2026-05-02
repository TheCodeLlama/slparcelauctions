package com.slparcelauctions.backend.auction.search;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.parceltag.ParcelTag;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;

/**
 * Builds a JPA {@link Specification} from a validated
 * {@link AuctionSearchQuery}. Each filter maps to one predicate;
 * predicates AND together. No sorts, no pagination — those live on the
 * {@link org.springframework.data.domain.Pageable} passed to
 * {@link com.slparcelauctions.backend.auction.AuctionRepository#findAll(
 * Specification, org.springframework.data.domain.Pageable)}.
 *
 * <p>Tag membership is resolved via {@code EXISTS} subqueries (one per
 * tag in AND mode, or one with {@code IN} in OR mode) rather than a
 * {@code JOIN FETCH} on the {@code tags} collection. The fetch-join
 * route would force Hibernate to paginate in the JVM
 * (HHH90003004), pulling every matching auction into memory before
 * slicing.
 *
 * <p>Distance search uses {@link #buildWithDistance}: it combines the
 * regular filter predicates with a bounding-box pre-filter
 * ({@code grid_x BETWEEN x0 - r AND x0 + r}, same for {@code grid_y})
 * AND a squared-distance refinement ({@code (dx)² + (dy)² <= r²}). The
 * bounding box is emitted explicitly rather than relying on the planner
 * to derive it from the squared-distance expression — that derivation
 * would not be index-usable.
 */
@Component
@RequiredArgsConstructor
public class AuctionSearchPredicateBuilder {

    private final Clock clock;

    public Specification<Auction> build(AuctionSearchQuery q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Public surface only ever shows ACTIVE listings — DRAFTs,
            // SUSPENDED, and ENDED rows must not leak through search.
            predicates.add(cb.equal(root.get("status"), AuctionStatus.ACTIVE));

            addFilterPredicates(predicates, q, root, query, cb);

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Same filter chain as {@link #build} but without the {@code status =
     * ACTIVE} clamp. Used by the saved-auctions list (Task 7) where
     * {@code statusFilter=ended_only} or {@code all} needs to surface
     * non-ACTIVE rows. Callers that want to scope by status must apply
     * their own status predicate on top.
     */
    public Specification<Auction> buildWithoutStatusClamp(AuctionSearchQuery q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            addFilterPredicates(predicates, q, root, query, cb);
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void addFilterPredicates(
            List<Predicate> predicates, AuctionSearchQuery q,
            Root<Auction> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        Join<Object, Object> parcel = root.join("parcel");
        Join<Object, Object> region = parcel.join("region");

        if (q.region() != null && !q.region().isBlank()) {
            predicates.add(cb.equal(
                    cb.lower(region.get("name")),
                    q.region().toLowerCase()));
        }
        if (q.minArea() != null) {
            predicates.add(cb.greaterThanOrEqualTo(
                    parcel.get("areaSqm"), q.minArea()));
        }
        if (q.maxArea() != null) {
            predicates.add(cb.lessThanOrEqualTo(
                    parcel.get("areaSqm"), q.maxArea()));
        }
        if (q.minPrice() != null) {
            predicates.add(cb.greaterThanOrEqualTo(
                    root.get("currentBid"), q.minPrice()));
        }
        if (q.maxPrice() != null) {
            predicates.add(cb.lessThanOrEqualTo(
                    root.get("currentBid"), q.maxPrice()));
        }
        if (q.maturity() != null && !q.maturity().isEmpty()) {
            predicates.add(region.get("maturityRating").in(q.maturity()));
        }
        if (q.verificationTier() != null && !q.verificationTier().isEmpty()) {
            predicates.add(root.get("verificationTier").in(q.verificationTier()));
        }
        if (q.sellerId() != null) {
            Join<Object, Object> seller = root.join("seller");
            predicates.add(cb.equal(seller.get("id"), q.sellerId()));
        }
        if (q.endingWithinHours() != null) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            OffsetDateTime upper = now.plusHours(q.endingWithinHours());
            predicates.add(cb.greaterThan(root.get("endsAt"), now));
            predicates.add(cb.lessThanOrEqualTo(root.get("endsAt"), upper));
        }
        addReserveFilter(predicates, q, root, cb);
        addSnipeFilter(predicates, q, root, cb);
        addTagsFilter(predicates, q, root, query, cb);
    }

    /**
     * Combine the standard filter predicates with a bounding-box
     * distance filter centered on {@code (x0, y0)} with radius
     * {@code radius} (in regions). The squared-distance refinement is
     * applied so corners of the bounding box that exceed the true
     * Euclidean radius are excluded.
     */
    public Specification<Auction> buildWithDistance(
            AuctionSearchQuery q, double x0, double y0, int radius) {
        Specification<Auction> base = build(q);
        return base.and((root, query, cb) -> {
            Join<Object, Object> parcel = root.join("parcel");
            Join<Object, Object> region = parcel.join("region");

            Expression<Double> dx = cb.diff(region.<Double>get("gridX"), cb.literal(x0));
            Expression<Double> dy = cb.diff(region.<Double>get("gridY"), cb.literal(y0));
            Expression<Double> distSquared = cb.sum(cb.prod(dx, dx), cb.prod(dy, dy));

            List<Predicate> dp = new ArrayList<>();
            dp.add(cb.between(region.<Double>get("gridX"), x0 - radius, x0 + radius));
            dp.add(cb.between(region.<Double>get("gridY"), y0 - radius, y0 + radius));
            dp.add(cb.lessThanOrEqualTo(distSquared, (double) radius * radius));
            return cb.and(dp.toArray(Predicate[]::new));
        });
    }

    private void addReserveFilter(
            List<Predicate> predicates, AuctionSearchQuery q,
            Root<Auction> root, CriteriaBuilder cb) {
        switch (q.reserveStatus()) {
            case NO_RESERVE -> predicates.add(cb.isNull(root.get("reservePrice")));
            case RESERVE_MET -> {
                predicates.add(cb.isNotNull(root.get("reservePrice")));
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("currentBid"), root.<Long>get("reservePrice")));
            }
            case RESERVE_NOT_MET -> {
                predicates.add(cb.isNotNull(root.get("reservePrice")));
                predicates.add(cb.or(
                        cb.isNull(root.get("currentBid")),
                        cb.lessThan(root.get("currentBid"),
                                root.<Long>get("reservePrice"))));
            }
            case ALL -> { }
        }
    }

    private void addSnipeFilter(
            List<Predicate> predicates, AuctionSearchQuery q,
            Root<Auction> root, CriteriaBuilder cb) {
        switch (q.snipeProtection()) {
            case TRUE -> predicates.add(cb.isTrue(root.get("snipeProtect")));
            case FALSE -> predicates.add(cb.isFalse(root.get("snipeProtect")));
            case ANY -> { }
        }
    }

    private void addTagsFilter(
            List<Predicate> predicates, AuctionSearchQuery q,
            Root<Auction> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        if (q.tags() == null || q.tags().isEmpty()) {
            return;
        }

        if (q.tagsMode() == TagsMode.OR) {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<Auction> subRoot = sub.from(Auction.class);
            Join<Auction, ParcelTag> tagJoin = subRoot.joinSet("tags");
            sub.select(subRoot.get("id"));
            sub.where(cb.and(
                    cb.equal(subRoot.get("id"), root.get("id")),
                    tagJoin.in(q.tags())));
            predicates.add(cb.exists(sub));
        } else {
            for (ParcelTag tag : q.tags()) {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<Auction> subRoot = sub.from(Auction.class);
                Join<Auction, ParcelTag> tagJoin = subRoot.joinSet("tags");
                sub.select(subRoot.get("id"));
                sub.where(cb.and(
                        cb.equal(subRoot.get("id"), root.get("id")),
                        cb.equal(tagJoin, tag)));
                predicates.add(cb.exists(sub));
            }
        }
    }
}
