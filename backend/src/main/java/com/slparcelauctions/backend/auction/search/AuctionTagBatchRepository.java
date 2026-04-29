package com.slparcelauctions.backend.auction.search;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import com.slparcelauctions.backend.parceltag.ParcelTag;

/**
 * Loads {@link ParcelTag} sets keyed by auction id in a single batched
 * query, sidestepping the N+1 that a {@code @ManyToMany} fetch on a
 * paginated result page would otherwise produce. Hibernate cannot
 * paginate in SQL when collection joins are eagerly fetched
 * (HHH90003004) — this repo lets the page query stay collection-free
 * and rehydrates tags as a separate IN-list lookup.
 *
 * <p>Auctions with no tags are absent from the returned map; callers
 * merge with an empty set.
 */
public interface AuctionTagBatchRepository {

    Map<Long, Set<ParcelTag>> findTagsGrouped(Collection<Long> auctionIds);
}
