package com.slparcelauctions.backend.auction.search;

import java.util.Collection;
import java.util.Map;

/**
 * Loads the primary-photo light + dark variant URL pair per auction in
 * one batched query. "Primary" is the lowest {@code sort_order}
 * (tie-broken by {@code id} ascending). Auctions with no photos are
 * absent from the returned map.
 *
 * <p>Exists for the same N+1 reason as
 * {@link AuctionTagBatchRepository}: keeping the search page query free
 * of any collection joins so SQL pagination stays correct.
 */
public interface AuctionPhotoBatchRepository {

    Map<Long, PrimaryPhotoUrls> findPrimaryPhotoUrls(Collection<Long> auctionIds);
}
