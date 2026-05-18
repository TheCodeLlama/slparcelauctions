package com.slparcelauctions.backend.escrow.scheduler;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the single recurring {@code VERIFY_SELL_TO} {@link BotTask} for an
 * escrow at funding (spec §5.1, plan Task 3.1). The bot teleports to the
 * parcel and reads {@code ParcelSnapshot.AuthBuyerId} / {@code SalePrice} —
 * data the SL World API cannot see — and reports the outcome back via
 * {@code POST /api/v1/bot/tasks/{id}/result}.
 *
 * <p>One open task per escrow: callers invoke this exactly once, in the same
 * transaction that transitions the escrow to {@code TRANSFER_PENDING}, so the
 * "at most one open {@code VERIFY_SELL_TO} per escrow" invariant relied on by
 * {@link BotTaskRepository#findOpenByEscrowAndType} holds. The bot worker
 * never claims the row until the funding transaction commits anyway.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SellToBotTaskFactory {

    private final BotTaskRepository botTaskRepo;
    private final UserRepository userRepo;
    private final EscrowConfigProperties props;

    /**
     * Builds + persists the {@code VERIFY_SELL_TO} task from the auction's
     * parcel snapshot (parcel UUID / region / position) and the winner's SL
     * avatar UUID. {@code nextRunAt = now} so the bot picks it up on its next
     * claim; {@code recurrenceIntervalSeconds} comes from
     * {@code slpa.escrow.sell-to.bot-recurrence} (default {@code PT30M}).
     *
     * @param escrow the escrow that just reached {@code TRANSFER_PENDING}
     * @param now    the funding instant (caller-supplied so the task cadence
     *               anchors to the exact instant the escrow funds)
     */
    public BotTask create(Escrow escrow, OffsetDateTime now) {
        Auction auction = escrow.getAuction();
        AuctionParcelSnapshot snap = auction.getParcelSnapshot();
        UUID parcelUuid = snap != null ? snap.getSlParcelUuid()
                : auction.getSlParcelUuid();
        String regionName = snap != null ? snap.getRegionName() : null;
        Double posX = snap != null ? snap.getPositionX() : null;
        Double posY = snap != null ? snap.getPositionY() : null;
        Double posZ = snap != null ? snap.getPositionZ() : null;

        UUID winnerSlUuid = null;
        Long winnerUserId = auction.getWinnerUserId();
        if (winnerUserId != null) {
            winnerSlUuid = userRepo.findById(winnerUserId)
                    .map(User::getSlAvatarUuid)
                    .orElse(null);
        }

        BotTask task = BotTask.builder()
                .taskType(BotTaskType.VERIFY_SELL_TO)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .escrow(escrow)
                .parcelUuid(parcelUuid)
                .regionName(regionName)
                .positionX(posX)
                .positionY(posY)
                .positionZ(posZ)
                .expectedWinnerUuid(winnerSlUuid)
                .nextRunAt(now)
                .recurrenceIntervalSeconds((int) props.sellToBotRecurrence().getSeconds())
                .sentinelPrice(0L)
                .build();
        BotTask saved = botTaskRepo.save(task);
        log.info("VERIFY_SELL_TO bot task {} created for escrow {} (auction {}, parcel {}, winner {})",
                saved.getId(), escrow.getId(), auction.getId(), parcelUuid, winnerSlUuid);
        return saved;
    }
}
