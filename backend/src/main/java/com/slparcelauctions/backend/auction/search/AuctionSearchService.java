package com.slparcelauctions.backend.auction.search;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.parceltag.ParcelTag;

import lombok.RequiredArgsConstructor;

/**
 * Orchestrates the three-query search hydration. Cache-wrapped at the
 * edge by {@link SearchResponseCache}.
 *
 * <p>Sort handling splits across two layers. {@link AuctionSearchSortSpec}
 * covers the sorts expressible as a Spring Data {@link Sort}
 * (NEWEST, ENDING_SOONEST, LARGEST_AREA). The other three —
 * LOWEST_PRICE (COALESCE), MOST_BIDS (DESC over the denormalized
 * {@code bid_count} column), and NEAREST (Task 4 distance column) —
 * cannot be expressed declaratively, so {@link #withComputedSort}
 * appends an {@code orderBy} via the {@link Specification} query
 * builder. {@code MOST_BIDS} uses {@code Auction.bidCount} directly
 * since the entity already persists a denormalized count; no subquery
 * against {@code Bid} is required.
 */
@Service
@RequiredArgsConstructor
public class AuctionSearchService {

    private final AuctionRepository auctionRepo;
    private final AuctionSearchPredicateBuilder predicateBuilder;
    private final AuctionTagBatchRepository tagBatchRepo;
    private final AuctionPhotoBatchRepository photoBatchRepo;
    private final AuctionSearchResultMapper mapper;
    private final SearchResponseCache cache;
    private final AuctionSearchQueryValidator validator;

    @Transactional(readOnly = true)
    public SearchPagedResponse<AuctionSearchResultDto> search(AuctionSearchQuery rawQuery) {
        AuctionSearchQuery query = validator.validate(rawQuery);
        return cache.getOrCompute(query, () -> executeSearch(query));
    }

    private SearchPagedResponse<AuctionSearchResultDto> executeSearch(AuctionSearchQuery query) {
        Specification<Auction> spec = predicateBuilder.build(query);
        Sort sort = AuctionSearchSortSpec.toSort(query.sort());
        Pageable pageable = PageRequest.of(query.page(), query.size(), sort);

        Specification<Auction> finalSpec = withComputedSort(query.sort(), spec);
        Page<Auction> page = auctionRepo.findAll(finalSpec, pageable);

        List<Long> pageIds = page.stream().map(Auction::getId).toList();
        Map<Long, Set<ParcelTag>> tags = tagBatchRepo.findTagsGrouped(pageIds);
        Map<Long, String> photos = photoBatchRepo.findPrimaryPhotoUrls(pageIds);

        List<AuctionSearchResultDto> dtos = mapper.toDtos(page.getContent(), tags, photos, null);
        Page<AuctionSearchResultDto> dtoPage = new PageImpl<>(dtos, pageable, page.getTotalElements());

        SearchMeta meta = new SearchMeta(query.sort().name().toLowerCase(), null);
        return SearchPagedResponse.from(dtoPage, meta);
    }

    private Specification<Auction> withComputedSort(AuctionSearchSort sortKey, Specification<Auction> base) {
        return switch (sortKey) {
            case LOWEST_PRICE -> base.and((root, q, cb) -> {
                q.orderBy(
                        cb.asc(cb.coalesce(root.get("currentBid"), root.get("startingBid"))),
                        cb.asc(root.get("id")));
                return cb.conjunction();
            });
            case MOST_BIDS -> base.and((root, q, cb) -> {
                q.orderBy(
                        cb.desc(root.get("bidCount")),
                        cb.asc(root.get("endsAt")),
                        cb.desc(root.get("id")));
                return cb.conjunction();
            });
            case NEAREST -> base;  // Task 4 wraps for distance.
            default -> base;
        };
    }
}
