package com.slparcelauctions.backend.auction.monitoring;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Transitions an ACTIVE auction to {@link AuctionStatus#SUSPENDED} and records
 * a {@link FraudFlag} for admin review. The two public entry points correspond
 * to the two ownership-monitor outcomes from spec §8.2:
 * <ul>
 *   <li>{@link #suspendForOwnershipChange} — World API returned a parcel whose
 *       owner UUID no longer matches the seller's linked avatar. Evidence
 *       captures expected vs. detected owner so the admin dashboard
 *       (Epic 10) can display the swap at a glance.</li>
 *   <li>{@link #suspendForDeletedParcel} — World API returned 404 for the
 *       parcel UUID. Evidence captures the parcel UUID only; no owner
 *       information survives.</li>
 * </ul>
 *
 * <p>{@code FraudFlag.createdAt} is populated by Hibernate's
 * {@code @CreationTimestamp}; callers must NOT pass it to the builder. The
 * service always writes {@code detectedAt} with the injected {@link Clock}
 * so tests can pin monitoring wall-clock behavior deterministically.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SuspensionService {

    private final AuctionRepository auctionRepo;
    private final FraudFlagRepository fraudFlagRepo;
    private final BotMonitorLifecycleService monitorLifecycle;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Transactional
    public void suspendForOwnershipChange(Auction auction, ParcelMetadata evidence) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        auction.setStatus(AuctionStatus.SUSPENDED);
        if (auction.getSuspendedAt() == null) {
            auction.setSuspendedAt(now);
        }
        auction.setLastOwnershipCheckAt(now);
        auctionRepo.save(auction);

        Map<String, Object> ev = new HashMap<>();
        ev.put("expected_owner", auction.getSeller().getSlAvatarUuid() == null
                ? null
                : auction.getSeller().getSlAvatarUuid().toString());
        ev.put("detected_owner", evidence.ownerUuid() == null
                ? null
                : evidence.ownerUuid().toString());
        ev.put("detected_owner_type", evidence.ownerType());
        ev.put("parcel_uuid", auction.getParcel().getSlParcelUuid().toString());

        fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .parcel(auction.getParcel())
                .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
                .detectedAt(now)
                .evidenceJson(ev)
                .resolved(false)
                .build());

        monitorLifecycle.onAuctionClosed(auction);

        notificationPublisher.listingSuspended(
                auction.getSeller().getId(),
                auction.getId(),
                auction.getTitle(),
                FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN.name());

        log.warn("Auction {} SUSPENDED for ownership change: expected={}, detected={}",
                auction.getId(), ev.get("expected_owner"), ev.get("detected_owner"));
    }

    @Transactional
    public void suspendForDeletedParcel(Auction auction) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        auction.setStatus(AuctionStatus.SUSPENDED);
        if (auction.getSuspendedAt() == null) {
            auction.setSuspendedAt(now);
        }
        auction.setLastOwnershipCheckAt(now);
        auctionRepo.save(auction);

        Map<String, Object> ev = new HashMap<>();
        ev.put("parcel_uuid", auction.getParcel().getSlParcelUuid().toString());

        fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .parcel(auction.getParcel())
                .reason(FraudFlagReason.PARCEL_DELETED_OR_MERGED)
                .detectedAt(now)
                .evidenceJson(ev)
                .resolved(false)
                .build());

        monitorLifecycle.onAuctionClosed(auction);

        notificationPublisher.listingSuspended(
                auction.getSeller().getId(),
                auction.getId(),
                auction.getTitle(),
                FraudFlagReason.PARCEL_DELETED_OR_MERGED.name());

        log.warn("Auction {} SUSPENDED: parcel {} no longer exists in-world",
                auction.getId(), auction.getParcel().getSlParcelUuid());
    }

    /**
     * Raises a {@link FraudFlagReason#CANCEL_AND_SELL} flag for a CANCELLED
     * auction whose parcel ownership has flipped to a non-seller avatar
     * within the post-cancel watch window (Epic 08 sub-spec 2 §6). Unlike
     * {@link #suspendForOwnershipChange}, the auction is already CANCELLED
     * so no status transition or {@code monitorLifecycle} hook fires — the
     * flag is for admin review only. The caller is responsible for clearing
     * {@code postCancelWatchUntil} on the auction (preventing re-flag on
     * subsequent ticks during the original watch window).
     *
     * <p>Evidence shape per spec §6.3: snapshot of cancelledAt, expected
     * vs observed owner UUIDs, an exact {@code hoursSinceCancellation}
     * decimal so admin reviewers can score temporal proximity, plus the
     * parcel/auction descriptors for at-a-glance context.
     */
    @Transactional
    public void raiseCancelAndSellFlag(
            Auction auction,
            UUID observedOwnerKey,
            OffsetDateTime cancelledAt) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID expectedSeller = auction.getSeller().getSlAvatarUuid();

        Map<String, Object> ev = new HashMap<>();
        ev.put("cancelledAt", cancelledAt == null ? null : cancelledAt.toString());
        ev.put("expectedSellerKey", expectedSeller == null ? null : expectedSeller.toString());
        ev.put("observedOwnerKey", observedOwnerKey == null ? null : observedOwnerKey.toString());
        // Decimal hours so the admin UI can display "4.2h" / "31.7h" without
        // re-deriving from raw timestamps. Null if the cancellation log is
        // missing — should not happen on a CANCELLED auction with an open
        // watch window, but the JSON schema is permissive.
        if (cancelledAt != null) {
            double hours = (now.toEpochSecond() - cancelledAt.toEpochSecond()) / 3600.0;
            ev.put("hoursSinceCancellation", hours);
        } else {
            ev.put("hoursSinceCancellation", null);
        }
        ev.put("parcelRegion", auction.getParcel().getRegionName());
        // The Parcel entity carries no SL-side "local id" today — surface the
        // database id as a stable handle so admin tools can join back to the
        // parcel without leaking SL implementation details. If a future SL
        // local-id column lands on Parcel, swap this projection in place.
        ev.put("parcelLocalId", auction.getParcel().getId());
        ev.put("auctionTitle", auction.getTitle());

        fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .parcel(auction.getParcel())
                .reason(FraudFlagReason.CANCEL_AND_SELL)
                .detectedAt(now)
                .evidenceJson(ev)
                .resolved(false)
                .build());

        log.warn("Auction {} CANCEL_AND_SELL flag raised: expectedSeller={}, observedOwner={}, hoursSince={}",
                auction.getId(), expectedSeller, observedOwnerKey, ev.get("hoursSinceCancellation"));
    }

    /**
     * Bot-monitor-triggered suspend. Unlike {@link #suspendForOwnershipChange}
     * (which is keyed on a World-API {@link ParcelMetadata} shape), bot
     * observations arrive as a loose evidence map. The caller supplies the
     * specific {@link FraudFlagReason} so the admin dashboard can tell the
     * four bot-detected causes apart. See Epic 06 spec §6.1.
     */
    @Transactional
    public void suspendForBotObservation(
            Auction auction,
            FraudFlagReason reason,
            Map<String, Object> evidence) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        auction.setStatus(AuctionStatus.SUSPENDED);
        if (auction.getSuspendedAt() == null) {
            auction.setSuspendedAt(now);
        }
        auction.setLastOwnershipCheckAt(now);
        auctionRepo.save(auction);

        fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .parcel(auction.getParcel())
                .reason(reason)
                .detectedAt(now)
                .evidenceJson(evidence)
                .resolved(false)
                .build());

        monitorLifecycle.onAuctionClosed(auction);

        notificationPublisher.listingSuspended(
                auction.getSeller().getId(),
                auction.getId(),
                auction.getTitle(),
                reason.name());

        log.warn("Auction {} SUSPENDED by bot monitor: reason={}, evidence={}",
                auction.getId(), reason, evidence);
    }

    /**
     * Admin-driven suspension. No FraudFlag created — admin reason is captured
     * in the admin_actions audit row written by the caller. Sets suspendedAt
     * if currently null, mirroring suspendForOwnershipChange.
     */
    @Transactional
    public void suspendByAdmin(Auction auction, Long adminUserId, String notes) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        auction.setStatus(AuctionStatus.SUSPENDED);
        if (auction.getSuspendedAt() == null) {
            auction.setSuspendedAt(now);
        }
        auctionRepo.save(auction);

        monitorLifecycle.onAuctionClosed(auction);

        notificationPublisher.listingSuspended(
            auction.getSeller().getId(), auction.getId(),
            auction.getTitle(), "Suspended by SLPA staff");
    }
}
