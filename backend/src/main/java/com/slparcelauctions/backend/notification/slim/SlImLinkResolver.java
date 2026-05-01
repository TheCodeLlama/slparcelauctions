package com.slparcelauctions.backend.notification.slim;

import com.slparcelauctions.backend.config.SlpaWebProperties;
import com.slparcelauctions.backend.notification.NotificationCategory;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps a notification category and its data blob to a deeplink URL for inclusion
 * in the SL IM message. Mirrors the frontend {@code categoryMap.deeplink} logic
 * — the duplication is intentional. Embedding the URL in the in-app row's data
 * blob would couple in-app rows to a specific channel's URL semantics and make
 * URL changes a data migration.
 */
@Component
@RequiredArgsConstructor
public class SlImLinkResolver {

    private final SlpaWebProperties webProps;

    public String resolve(NotificationCategory category, Map<String, Object> data) {
        String base = webProps.baseUrl();
        return switch (category) {
            case OUTBID, PROXY_EXHAUSTED, AUCTION_LOST,
                 AUCTION_ENDED_RESERVE_NOT_MET, AUCTION_ENDED_NO_BIDS,
                 AUCTION_ENDED_BOUGHT_NOW, AUCTION_ENDED_SOLD,
                 LISTING_VERIFIED, LISTING_CANCELLED_BY_SELLER,
                 LISTING_REMOVED_BY_ADMIN, LISTING_WARNED,
                 REVIEW_RECEIVED, REVIEW_RESPONSE_WINDOW_CLOSING ->
                base + "/auction/" + data.get("auctionId");
            case AUCTION_WON ->
                base + "/auction/" + data.get("auctionId") + "/escrow";
            case ESCROW_FUNDED, ESCROW_TRANSFER_CONFIRMED, ESCROW_PAYOUT,
                 ESCROW_EXPIRED, ESCROW_DISPUTED, ESCROW_FROZEN,
                 ESCROW_PAYOUT_STALLED, ESCROW_TRANSFER_REMINDER ->
                base + "/auction/" + data.get("auctionId") + "/escrow";
            case LISTING_SUSPENDED, LISTING_REINSTATED, LISTING_REVIEW_REQUIRED ->
                base + "/dashboard/listings";
            case SYSTEM_ANNOUNCEMENT ->
                base + "/notifications";
            case DISPUTE_FILED_AGAINST_SELLER, DISPUTE_RESOLVED ->
                base + "/auction/" + data.get("auctionId") + "/escrow";
            case RECONCILIATION_MISMATCH, WITHDRAWAL_COMPLETED, WITHDRAWAL_FAILED ->
                base + "/admin/infrastructure";
            case WALLET_WITHDRAWAL_COMPLETED, WALLET_WITHDRAWAL_REVERSED ->
                base + "/wallet";
        };
    }
}
