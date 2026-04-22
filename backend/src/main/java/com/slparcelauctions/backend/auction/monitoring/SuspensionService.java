package com.slparcelauctions.backend.auction.monitoring;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
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
    private final Clock clock;

    @Transactional
    public void suspendForOwnershipChange(Auction auction, ParcelMetadata evidence) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        auction.setStatus(AuctionStatus.SUSPENDED);
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

        log.warn("Auction {} SUSPENDED for ownership change: expected={}, detected={}",
                auction.getId(), ev.get("expected_owner"), ev.get("detected_owner"));
    }

    @Transactional
    public void suspendForDeletedParcel(Auction auction) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        auction.setStatus(AuctionStatus.SUSPENDED);
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

        log.warn("Auction {} SUSPENDED: parcel {} no longer exists in-world",
                auction.getId(), auction.getParcel().getSlParcelUuid());
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

        log.warn("Auction {} SUSPENDED by bot monitor: reason={}, evidence={}",
                auction.getId(), reason, evidence);
    }
}
