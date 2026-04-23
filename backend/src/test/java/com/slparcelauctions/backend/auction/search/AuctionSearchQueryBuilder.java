package com.slparcelauctions.backend.auction.search;

import java.util.Set;

import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parceltag.ParcelTag;

class AuctionSearchQueryBuilder {
    private String region;
    private Integer minArea, maxArea;
    private Long minPrice, maxPrice;
    private Set<String> maturity;
    private Set<ParcelTag> tags;
    private TagsMode tagsMode = TagsMode.OR;
    private ReserveStatusFilter reserveStatus = ReserveStatusFilter.ALL;
    private SnipeProtectionFilter snipeProtection = SnipeProtectionFilter.ANY;
    private Set<VerificationTier> verificationTier;
    private Integer endingWithinHours;
    private String nearRegion;
    private Integer distance;
    private Long sellerId;
    private AuctionSearchSort sort = AuctionSearchSort.NEWEST;
    private int page = 0;
    private int size = 24;

    static AuctionSearchQueryBuilder newBuilder() { return new AuctionSearchQueryBuilder(); }

    AuctionSearchQueryBuilder region(String v) { this.region = v; return this; }
    AuctionSearchQueryBuilder minArea(Integer v) { this.minArea = v; return this; }
    AuctionSearchQueryBuilder maxArea(Integer v) { this.maxArea = v; return this; }
    AuctionSearchQueryBuilder minPrice(Long v) { this.minPrice = v; return this; }
    AuctionSearchQueryBuilder maxPrice(Long v) { this.maxPrice = v; return this; }
    AuctionSearchQueryBuilder maturity(Set<String> v) { this.maturity = v; return this; }
    AuctionSearchQueryBuilder tags(Set<ParcelTag> v) { this.tags = v; return this; }
    AuctionSearchQueryBuilder tagsMode(TagsMode v) { this.tagsMode = v; return this; }
    AuctionSearchQueryBuilder reserveStatus(ReserveStatusFilter v) { this.reserveStatus = v; return this; }
    AuctionSearchQueryBuilder snipeProtection(SnipeProtectionFilter v) { this.snipeProtection = v; return this; }
    AuctionSearchQueryBuilder verificationTier(Set<VerificationTier> v) { this.verificationTier = v; return this; }
    AuctionSearchQueryBuilder endingWithinHours(Integer v) { this.endingWithinHours = v; return this; }
    AuctionSearchQueryBuilder nearRegion(String v) { this.nearRegion = v; return this; }
    AuctionSearchQueryBuilder distance(Integer v) { this.distance = v; return this; }
    AuctionSearchQueryBuilder sellerId(Long v) { this.sellerId = v; return this; }
    AuctionSearchQueryBuilder sort(AuctionSearchSort v) { this.sort = v; return this; }
    AuctionSearchQueryBuilder page(int v) { this.page = v; return this; }
    AuctionSearchQueryBuilder size(int v) { this.size = v; return this; }

    AuctionSearchQuery build() {
        return new AuctionSearchQuery(region, minArea, maxArea, minPrice, maxPrice,
                maturity, tags, tagsMode, reserveStatus, snipeProtection,
                verificationTier, endingWithinHours, nearRegion, distance, sellerId,
                sort, page, size);
    }
}
