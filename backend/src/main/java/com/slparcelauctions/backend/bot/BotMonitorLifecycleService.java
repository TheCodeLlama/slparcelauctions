package com.slparcelauctions.backend.bot;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates and cancels {@link BotTask} monitor rows in sync with auction
 * and escrow lifecycle transitions. Every hook is a no-op if no matching
 * row exists, so call sites can invoke unconditionally without branching
 * on verification tier.
 *
 * <p>Callers pass the entity they already have in scope; all persistence
 * runs inside the caller's transaction so the monitor row write and the
 * entity state flip succeed or roll back together.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotMonitorLifecycleService {

    private static final List<BotTaskType> AUCTION_MONITOR_TYPES =
            List.of(BotTaskType.MONITOR_AUCTION);

    private final BotTaskRepository botTaskRepo;
    private final UserRepository userRepo;
    private final BotTaskConfigProperties props;
    private final Clock clock;

    /**
     * Creates a MONITOR_AUCTION row for a freshly-activated BOT-tier auction.
     * Called from {@link BotTaskService#complete} SUCCESS path. No-op for
     * non-BOT verification tiers.
     */
    @Transactional
    public void onAuctionActivatedBot(Auction auction) {
        if (auction.getVerificationTier() != VerificationTier.BOT) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        Duration interval = props.bot().monitorAuctionInterval();
        Parcel parcel = auction.getParcel();
        BotTask row = BotTask.builder()
                .taskType(BotTaskType.MONITOR_AUCTION)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(parcel.getSlParcelUuid())
                .regionName(parcel.getRegionName())
                .positionX(parcel.getPositionX())
                .positionY(parcel.getPositionY())
                .positionZ(parcel.getPositionZ())
                .sentinelPrice(props.botTask().sentinelPriceLindens())
                .expectedOwnerUuid(parcel.getOwnerUuid())
                .expectedAuthBuyerUuid(props.botTask().primaryEscrowUuid())
                .expectedSalePriceLindens(props.botTask().sentinelPriceLindens())
                .nextRunAt(now.plus(interval))
                .recurrenceIntervalSeconds((int) interval.getSeconds())
                .build();
        botTaskRepo.save(row);
        log.info("MONITOR_AUCTION row created for auction {}: nextRunAt={}",
                auction.getId(), row.getNextRunAt());
    }

    /**
     * Re-engage MONITOR_AUCTION on a previously-suspended BOT-tier auction
     * after admin reinstate. Mirrors {@link #onAuctionActivatedBot} since the
     * spawn shape is identical; kept as a separate entry point so the call
     * site reads correctly. No-op for non-BOT tiers.
     */
    @Transactional
    public void onAuctionResumed(Auction auction) {
        onAuctionActivatedBot(auction);
    }

    /**
     * Cancels live MONITOR_AUCTION rows on ended / suspended / cancelled
     * auctions. Safe to call unconditionally — no-op when no rows match.
     */
    @Transactional
    public void onAuctionClosed(Auction auction) {
        int cancelled = botTaskRepo.cancelLiveByAuctionIdAndTypes(
                auction.getId(), AUCTION_MONITOR_TYPES, OffsetDateTime.now(clock));
        if (cancelled > 0) {
            log.info("MONITOR_AUCTION rows cancelled for auction {}: count={}",
                    auction.getId(), cancelled);
        }
    }

    /**
     * Creates a MONITOR_ESCROW row for a freshly-created escrow on a
     * BOT-tier auction. Called from {@link com.slparcelauctions.backend.escrow.EscrowService#createForEndedAuction}
     * at the end of the create transaction. No-op for non-BOT tiers.
     */
    @Transactional
    public void onEscrowCreatedBot(Escrow escrow) {
        Auction auction = escrow.getAuction();
        if (auction.getVerificationTier() != VerificationTier.BOT) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        Duration interval = props.bot().monitorEscrowInterval();

        User winner = userRepo.findById(auction.getWinnerUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "Winner user not found for auction " + auction.getId()));
        UUID winnerUuid = winner.getSlAvatarUuid();
        UUID sellerUuid = auction.getSeller().getSlAvatarUuid();
        Parcel parcel = auction.getParcel();

        BotTask row = BotTask.builder()
                .taskType(BotTaskType.MONITOR_ESCROW)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .escrow(escrow)
                .parcelUuid(parcel.getSlParcelUuid())
                .regionName(parcel.getRegionName())
                .positionX(parcel.getPositionX())
                .positionY(parcel.getPositionY())
                .positionZ(parcel.getPositionZ())
                .sentinelPrice(props.botTask().sentinelPriceLindens())
                .expectedSellerUuid(sellerUuid)
                .expectedWinnerUuid(winnerUuid)
                .expectedMaxSalePriceLindens(1L)
                .nextRunAt(now.plus(interval))
                .recurrenceIntervalSeconds((int) interval.getSeconds())
                .build();
        botTaskRepo.save(row);
        log.info("MONITOR_ESCROW row created for escrow {}: nextRunAt={}",
                escrow.getId(), row.getNextRunAt());
    }

    /**
     * Cancels live MONITOR_ESCROW rows on terminal escrow states
     * (COMPLETED, EXPIRED, DISPUTED, FROZEN). Safe to call unconditionally.
     */
    @Transactional
    public void onEscrowTerminal(Escrow escrow) {
        int cancelled = botTaskRepo.cancelLiveByEscrowId(
                escrow.getId(), OffsetDateTime.now(clock));
        if (cancelled > 0) {
            log.info("MONITOR_ESCROW rows cancelled for escrow {}: count={}",
                    escrow.getId(), cancelled);
        }
    }
}
