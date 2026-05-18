package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;
import com.slparcelauctions.backend.escrow.scheduler.SellToBotTaskFactory;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit coverage for {@link SellToBotTaskFactory#create} — the
 * {@code VERIFY_SELL_TO} bot task created when an escrow reaches
 * {@code TRANSFER_PENDING} at funding (spec §5.1, plan Task 3.1).
 */
@ExtendWith(MockitoExtension.class)
class SellToBotTaskCreationTest {

    private static final Long ESCROW_ID = 501L;
    private static final Long AUCTION_ID = 42L;
    private static final Long SELLER_ID = 7L;
    private static final Long WINNER_ID = 8L;
    private static final UUID PARCEL_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WINNER_SL_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock BotTaskRepository botTaskRepo;
    @Mock UserRepository userRepo;
    @Mock EscrowConfigProperties props;

    SellToBotTaskFactory factory;
    Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-05-17T12:00:00Z"), ZoneOffset.UTC);
        factory = new SellToBotTaskFactory(botTaskRepo, userRepo, props);
        lenient().when(props.sellToBotRecurrence()).thenReturn(Duration.ofMinutes(30));
        lenient().when(botTaskRepo.save(any(BotTask.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_buildsVerifySellToTask_fromSnapshotAndWinner() {
        Escrow escrow = buildPending();
        when(userRepo.findById(WINNER_ID)).thenReturn(Optional.of(
                User.builder().id(WINNER_ID).email("w@example.com").username("w")
                        .verified(true).slAvatarUuid(WINNER_SL_UUID).build()));

        OffsetDateTime now = OffsetDateTime.now(fixed);
        BotTask task = factory.create(escrow, now);

        assertThat(task.getTaskType()).isEqualTo(BotTaskType.VERIFY_SELL_TO);
        assertThat(task.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        assertThat(task.getEscrow()).isSameAs(escrow);
        assertThat(task.getAuction()).isSameAs(escrow.getAuction());
        assertThat(task.getParcelUuid()).isEqualTo(PARCEL_UUID);
        assertThat(task.getRegionName()).isEqualTo("EscrowRegion");
        assertThat(task.getPositionX()).isEqualTo(128.0);
        assertThat(task.getPositionY()).isEqualTo(64.0);
        assertThat(task.getPositionZ()).isEqualTo(22.0);
        assertThat(task.getExpectedWinnerUuid()).isEqualTo(WINNER_SL_UUID);
        assertThat(task.getNextRunAt()).isEqualTo(now);
        assertThat(task.getRecurrenceIntervalSeconds()).isEqualTo(1800);
        assertThat(task.getSentinelPrice()).isEqualTo(0L);
    }

    private Escrow buildPending() {
        User seller = User.builder().id(SELLER_ID).email("seller@example.com").username("seller")
                .verified(true).build();
        Auction auction = Auction.builder()
                .title("Test listing")
                .id(AUCTION_ID).seller(seller)
                .status(AuctionStatus.TRANSFER_PENDING)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).listingFeePaid(true)
                .currentBid(5000L).bidCount(2)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .tags(new HashSet<>())
                .finalBidAmount(5000L)
                .endOutcome(AuctionEndOutcome.SOLD)
                .winnerUserId(WINNER_ID)
                .build();
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(PARCEL_UUID)
                .parcelName("Test Parcel")
                .regionName("EscrowRegion")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return Escrow.builder()
                .id(ESCROW_ID)
                .auction(auction)
                .state(EscrowState.TRANSFER_PENDING)
                .finalBidAmount(5000L)
                .commissionAmt(250L)
                .payoutAmt(4750L)
                .transferDeadline(OffsetDateTime.now(fixed).plusHours(72))
                .fundedAt(OffsetDateTime.now(fixed))
                .consecutiveWorldApiFailures(0)
                .build();
    }
}
