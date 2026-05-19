package com.slparcelauctions.backend.realty.rating;

/**
 * Spring application event fired by {@code ReviewService.submit} after a
 * new {@code Review} row is persisted (regardless of visibility — the
 * cache invalidator does not care whether the simultaneous-reveal branch
 * has flipped the review to visible yet, because the cached aggregate is
 * keyed off the {@code reviews} table and would be stale either way).
 *
 * <p>Consumed by {@link GroupRatingCacheInvalidator} to evict the Redis
 * entry at {@code realty_groups_rating:{groupId}} for whichever realty
 * group the underlying auction belongs to (direct via
 * {@code Auction.realtyGroupId}, group sale via the
 * {@code RealtyGroupSlGroup} indirection).
 *
 * <p>Carries internal numeric ids only — the listener resolves the
 * auction and SL-group rows by primary key. {@code reviewId} and
 * {@code starRating} are included for log/audit context; the invalidator
 * does not need them, but downstream listeners (if any are added later)
 * may want them without re-querying.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-12-realty-groups-admin-moderation-design.md} §16.2.
 */
public record ReviewCreatedEvent(Long auctionId, Long reviewId, int starRating) {}
