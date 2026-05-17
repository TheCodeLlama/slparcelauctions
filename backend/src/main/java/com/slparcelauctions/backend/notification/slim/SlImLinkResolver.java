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
                base + "/auction/" + auctionUrlId(data);
            case AUCTION_WON ->
                base + "/auction/" + auctionUrlId(data) + "/escrow";
            case ESCROW_FUNDED, ESCROW_TRANSFER_CONFIRMED, ESCROW_PAYOUT,
                 ESCROW_EXPIRED, ESCROW_DISPUTED, ESCROW_FROZEN,
                 ESCROW_PAYOUT_STALLED, ESCROW_TRANSFER_REMINDER ->
                base + "/auction/" + auctionUrlId(data) + "/escrow";
            case LISTING_SUSPENDED, LISTING_REINSTATED, LISTING_REVIEW_REQUIRED ->
                base + "/dashboard/listings";
            case SYSTEM_ANNOUNCEMENT ->
                base + "/notifications";
            case DISPUTE_FILED_AGAINST_SELLER, DISPUTE_RESOLVED ->
                base + "/auction/" + auctionUrlId(data) + "/escrow";
            case RECONCILIATION_MISMATCH, WITHDRAWAL_COMPLETED, WITHDRAWAL_FAILED ->
                base + "/admin/infrastructure";
            case WALLET_WITHDRAWAL_COMPLETED, WALLET_WITHDRAWAL_REVERSED,
                 WALLET_ADJUSTED, WALLET_FROZEN, WALLET_UNFROZEN,
                 WALLET_PENALTY_FORGIVEN, WALLET_DORMANCY_RESET, WALLET_TERMS_CLEARED,
                 WITHDRAWAL_FORCE_COMPLETED, WITHDRAWAL_FORCE_FAILED ->
                base + "/wallet";
            case REALTY_GROUP_INVITATION_SENT ->
                // Recipient-side row: target the invited user's own inbox where
                // accept/decline lives. /groups namespace migration -- the SL IM
                // body is assembled as title + body + this deeplink, so appending
                // it here is what realises the "View at {appBaseUrl}/groups/invitations/me"
                // text the plan's Task 31 calls for.
                base + "/groups/invitations/me";
            case REALTY_GROUP_INVITATION_ACCEPTED, REALTY_GROUP_INVITATION_DECLINED,
                 REALTY_GROUP_INVITATION_EXPIRED, REALTY_GROUP_MEMBER_LEFT,
                 REALTY_GROUP_LEADERSHIP_TRANSFERRED, REALTY_GROUP_PERMISSIONS_CHANGED,
                 REALTY_GROUP_SUSPENDED, REALTY_GROUP_UNSUSPENDED,
                 REALTY_GROUP_SL_GROUP_DRIFT_DETECTED ->
                base + "/group/" + data.get("groupSlug");
            case REALTY_GROUP_MEMBER_REMOVED, REALTY_GROUP_DISSOLVED ->
                base + "/dashboard/groups";
            // Sub-project G section 12 -- admin-side fan-out. Deeplink to the
            // per-group admin reports queue using groupPublicId from the data blob.
            case GROUP_REPORT_THRESHOLD_REACHED ->
                base + "/admin/realty-groups/" + data.get("groupPublicId") + "/reports";
        };
    }

    /**
     * Pulls the URL-safe auction identifier from the data blob, preferring
     * the wire-stable {@code auctionPublicId} (UUID, written by
     * {@code NotificationPublisherImpl.withAuctionPublicId}) and falling
     * back to the historical {@code auctionId} (internal {@code Long}) for
     * notification rows persisted before the publicId backfill. Frontend
     * routes live at {@code /auction/[publicId]}; an integer fallback
     * resolves to a 404, but that beats a NullPointerException at SL IM
     * dispatch time.
     */
    private static Object auctionUrlId(Map<String, Object> data) {
        Object publicId = data.get("auctionPublicId");
        return publicId != null ? publicId : data.get("auctionId");
    }
}
