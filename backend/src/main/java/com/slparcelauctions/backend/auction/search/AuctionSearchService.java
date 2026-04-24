package com.slparcelauctions.backend.auction.search;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.slparcelauctions.backend.auction.search.exception.RegionNotFoundException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.sl.CachedRegionResolver;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import lombok.RequiredArgsConstructor;

/**
 * Orchestrates the three-query search hydration. Cache-wrapped at the
 * edge by {@link SearchResponseCache}.
 *
 * <p>Sort handling splits across two layers. {@link AuctionSearchSortSpec}
 * covers the sorts expressible as a Spring Data {@link Sort}
 * (NEWEST, ENDING_SOONEST, LARGEST_AREA). The other three —
 * LOWEST_PRICE (COALESCE), MOST_BIDS (DESC over the denormalized
 * {@code bid_count} column), and NEAREST (squared-distance from the
 * resolved {@code near_region} anchor) — cannot be expressed
 * declaratively, so {@link #withComputedSort} appends an {@code orderBy}
 * via the {@link Specification} query builder. {@code MOST_BIDS} uses
 * {@code Auction.bidCount} directly since the entity already persists a
 * denormalized count; no subquery against {@code Bid} is required.
 *
 * <p>Distance search: when {@code near_region} is supplied, the service
 * resolves the region name to grid coordinates via
 * {@link CachedRegionResolver}, builds a bounding-box + squared-distance
 * predicate, computes per-row distances in memory after pagination
 * (cheap — at most {@code size} rows), and surfaces the resolved
 * region in {@link SearchMeta#nearRegionResolved} so the frontend can
 * confirm which anchor was used.
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
    private final CachedRegionResolver regionResolver;

    @Transactional(readOnly = true)
    public SearchPagedResponse<AuctionSearchResultDto> search(AuctionSearchQuery rawQuery) {
        AuctionSearchQuery query = validator.validate(rawQuery);
        return cache.getOrCompute(query, () -> executeSearch(query));
    }

    private SearchPagedResponse<AuctionSearchResultDto> executeSearch(AuctionSearchQuery query) {
        ResolvedRegion resolvedRegion = null;
        Specification<Auction> spec;

        if (query.nearRegion() != null && !query.nearRegion().isBlank()) {
            Optional<GridCoordinates> coord = regionResolver.resolve(query.nearRegion());
            if (coord.isEmpty()) {
                throw new RegionNotFoundException(query.nearRegion());
            }
            double x0 = coord.get().gridX();
            double y0 = coord.get().gridY();
            int radius = query.distance() != null
                    ? query.distance()
                    : AuctionSearchQuery.DEFAULT_DISTANCE;
            spec = predicateBuilder.buildWithDistance(query, x0, y0, radius);
            resolvedRegion = new ResolvedRegion(query.nearRegion(), x0, y0);
        } else {
            spec = predicateBuilder.build(query);
        }

        Sort sort = AuctionSearchSortSpec.toSort(query.sort());
        Pageable pageable = PageRequest.of(query.page(), query.size(), sort);
        Specification<Auction> finalSpec = withComputedSort(query.sort(), spec, resolvedRegion);

        Page<Auction> page = auctionRepo.findAll(finalSpec, pageable);

        List<Long> pageIds = page.stream().map(Auction::getId).toList();
        Map<Long, Set<ParcelTag>> tags = tagBatchRepo.findTagsGrouped(pageIds);
        Map<Long, String> photos = photoBatchRepo.findPrimaryPhotoUrls(pageIds);
        Map<Long, BigDecimal> distances = computeDistances(page.getContent(), resolvedRegion);

        List<AuctionSearchResultDto> dtos = mapper.toDtos(page.getContent(), tags, photos, distances);
        Page<AuctionSearchResultDto> dtoPage = new PageImpl<>(dtos, pageable, page.getTotalElements());

        SearchMeta meta = new SearchMeta(query.sort().name().toLowerCase(), resolvedRegion);
        return SearchPagedResponse.from(dtoPage, meta);
    }

    private Map<Long, BigDecimal> computeDistances(
            List<Auction> rows, ResolvedRegion resolvedRegion) {
        if (resolvedRegion == null || rows.isEmpty()) {
            return null;
        }
        double x0 = resolvedRegion.gridX();
        double y0 = resolvedRegion.gridY();
        Map<Long, BigDecimal> out = new HashMap<>(rows.size());
        for (Auction a : rows) {
            Parcel p = a.getParcel();
            if (p == null || p.getGridX() == null || p.getGridY() == null) {
                continue;
            }
            double dx = p.getGridX() - x0;
            double dy = p.getGridY() - y0;
            double dist = Math.sqrt(dx * dx + dy * dy);
            out.put(a.getId(), BigDecimal.valueOf(dist).setScale(1, RoundingMode.HALF_UP));
        }
        return out;
    }

    private Specification<Auction> withComputedSort(
            AuctionSearchSort sortKey,
            Specification<Auction> base,
            ResolvedRegion resolvedRegion) {
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
            case NEAREST -> {
                if (resolvedRegion == null) {
                    yield base;
                }
                final double x0 = resolvedRegion.gridX();
                final double y0 = resolvedRegion.gridY();
                yield base.and((root, q, cb) -> {
                    Join<Object, Object> parcel = root.join("parcel");
                    Expression<Double> dx = cb.diff(parcel.<Double>get("gridX"), cb.literal(x0));
                    Expression<Double> dy = cb.diff(parcel.<Double>get("gridY"), cb.literal(y0));
                    Expression<Double> distSquared = cb.sum(cb.prod(dx, dx), cb.prod(dy, dy));
                    q.orderBy(cb.asc(distSquared), cb.asc(root.get("id")));
                    return cb.conjunction();
                });
            }
            default -> base;
        };
    }
}
