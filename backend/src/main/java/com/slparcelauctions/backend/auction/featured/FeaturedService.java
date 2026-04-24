package com.slparcelauctions.backend.auction.featured;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.search.AuctionPhotoBatchRepository;
import com.slparcelauctions.backend.auction.search.AuctionSearchResultDto;
import com.slparcelauctions.backend.auction.search.AuctionSearchResultMapper;
import com.slparcelauctions.backend.auction.search.AuctionTagBatchRepository;
import com.slparcelauctions.backend.parceltag.ParcelTag;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

/**
 * Hydrates the three featured rows. The cache fronts the entire pipeline:
 * on hit nothing below this method runs. On miss the per-category SQL
 * loads up to six auctions, then tags + photos are batch-loaded in two
 * extra queries to avoid the {@code @ManyToMany} N+1 the search service
 * already documents.
 *
 * <p>Miss-path duration is recorded as {@code slpa.featured.<cat>.duration}
 * on the injected {@link MeterRegistry}. The fallback registry from
 * {@code MetricsConfig} keeps timing live in dev/test even without the
 * actuator starter.
 */
@Service
@RequiredArgsConstructor
public class FeaturedService {

    private final FeaturedRepository featuredRepo;
    private final AuctionTagBatchRepository tagBatchRepo;
    private final AuctionPhotoBatchRepository photoBatchRepo;
    private final AuctionSearchResultMapper mapper;
    private final FeaturedCache cache;
    private final MeterRegistry meterRegistry;

    @Transactional(readOnly = true)
    public FeaturedResponse get(FeaturedCategory category) {
        return cache.getOrCompute(category,
                () -> recordLatency(category, () -> hydrate(category)));
    }

    private FeaturedResponse hydrate(FeaturedCategory category) {
        List<Auction> rows = switch (category) {
            case ENDING_SOON -> featuredRepo.endingSoon();
            case JUST_LISTED -> featuredRepo.justListed();
            case MOST_ACTIVE -> featuredRepo.mostActive();
        };

        List<Long> ids = rows.stream().map(Auction::getId).toList();
        Map<Long, Set<ParcelTag>> tags = tagBatchRepo.findTagsGrouped(ids);
        Map<Long, String> photos = photoBatchRepo.findPrimaryPhotoUrls(ids);

        List<AuctionSearchResultDto> dtos = mapper.toDtos(rows, tags, photos, null);
        return FeaturedResponse.of(dtos);
    }

    private FeaturedResponse recordLatency(FeaturedCategory category,
                                           Supplier<FeaturedResponse> work) {
        Timer timer = Timer.builder("slpa.featured." + category.name().toLowerCase() + ".duration")
                .description("Featured endpoint miss-path query duration")
                .register(meterRegistry);
        return timer.record(work);
    }
}
