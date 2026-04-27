package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.AdminAuctionService.AdminAuctionReinstateResult;
import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;

@ExtendWith(MockitoExtension.class)
class AdminAuctionServiceTest {

    @Mock AuctionRepository auctionRepo;
    @Mock BotMonitorLifecycleService botMonitorLifecycleService;
    @Mock NotificationPublisher notificationPublisher;

    // Fixed clock: "now" = 2025-01-01T12:00:00Z
    private static final Instant NOW_INSTANT = Instant.parse("2025-01-01T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);

    private AdminAuctionService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
        service = new AdminAuctionService(auctionRepo, botMonitorLifecycleService, notificationPublisher, fixed);
    }

    private User seller() {
        return User.builder().id(7L).email("seller@x.com").passwordHash("x").displayName("Seller").build();
    }

    @Test
    void reinstate_happyPath_extendsEndsAt_withSuspendedAt() {
        // suspendedAt = now - 6h, endsAt = now + 2h → new endsAt = now + 8h
        OffsetDateTime suspendedAt = NOW.minusHours(6);
        OffsetDateTime originalEndsAt = NOW.plusHours(2);
        Auction auction = Auction.builder()
            .id(100L)
            .status(AuctionStatus.SUSPENDED)
            .suspendedAt(suspendedAt)
            .endsAt(originalEndsAt)
            .title("Test Auction")
            .seller(seller())
            .consecutiveWorldApiFailures(0)
            .build();

        when(auctionRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(auction));
        when(auctionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdminAuctionReinstateResult result = service.reinstate(100L, Optional.empty());

        assertThat(result.auction().getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(result.auction().getSuspendedAt()).isNull();
        // suspension was 6h, so endsAt extends by 6h: now+2h + 6h = now+8h
        assertThat(result.newEndsAt()).isEqualTo(NOW.plusHours(8));
        assertThat(result.suspensionDuration().toHours()).isEqualTo(6L);

        verify(botMonitorLifecycleService).onAuctionResumed(auction);
        verify(notificationPublisher).listingReinstated(eq(7L), eq(100L), eq("Test Auction"), eq(NOW.plusHours(8)));
    }

    @Test
    void reinstate_fallbackUsedWhenSuspendedAtNull() {
        // suspendedAt = null, fallback = now - 3h, endsAt = now + 4h → new endsAt = now + 7h
        OffsetDateTime fallback = NOW.minusHours(3);
        OffsetDateTime originalEndsAt = NOW.plusHours(4);
        Auction auction = Auction.builder()
            .id(101L)
            .status(AuctionStatus.SUSPENDED)
            .suspendedAt(null)
            .endsAt(originalEndsAt)
            .title("Fallback Auction")
            .seller(seller())
            .consecutiveWorldApiFailures(0)
            .build();

        when(auctionRepo.findByIdForUpdate(101L)).thenReturn(Optional.of(auction));
        when(auctionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdminAuctionReinstateResult result = service.reinstate(101L, Optional.of(fallback));

        // suspensionDuration = 3h, endsAt = now+4h + 3h = now+7h
        assertThat(result.newEndsAt()).isEqualTo(NOW.plusHours(7));
        assertThat(result.suspensionDuration().toHours()).isEqualTo(3L);
        assertThat(result.auction().getStatus()).isEqualTo(AuctionStatus.ACTIVE);
    }

    @Test
    void reinstate_zeroExtensionWhenBothNull() {
        // suspendedAt = null, fallback = empty → suspendedFrom = now → duration = 0 → endsAt unchanged
        OffsetDateTime originalEndsAt = NOW.plusHours(10);
        Auction auction = Auction.builder()
            .id(102L)
            .status(AuctionStatus.SUSPENDED)
            .suspendedAt(null)
            .endsAt(originalEndsAt)
            .title("Zero Extension Auction")
            .seller(seller())
            .consecutiveWorldApiFailures(0)
            .build();

        when(auctionRepo.findByIdForUpdate(102L)).thenReturn(Optional.of(auction));
        when(auctionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdminAuctionReinstateResult result = service.reinstate(102L, Optional.empty());

        // duration = 0, newEndsAt = originalEndsAt (not before now, so no clamp)
        assertThat(result.suspensionDuration().toSeconds()).isEqualTo(0L);
        assertThat(result.newEndsAt()).isEqualTo(originalEndsAt);
    }

    @Test
    void reinstate_clampsPastEndsAt_toNowPlus1h() {
        // suspendedAt = now - 6h, endsAt = now - 8h → newEndsAt = now-8h+6h = now-2h (past) → clamp to now+1h
        OffsetDateTime suspendedAt = NOW.minusHours(6);
        OffsetDateTime originalEndsAt = NOW.minusHours(8);
        Auction auction = Auction.builder()
            .id(103L)
            .status(AuctionStatus.SUSPENDED)
            .suspendedAt(suspendedAt)
            .endsAt(originalEndsAt)
            .title("Clamp Auction")
            .seller(seller())
            .consecutiveWorldApiFailures(0)
            .build();

        when(auctionRepo.findByIdForUpdate(103L)).thenReturn(Optional.of(auction));
        when(auctionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AdminAuctionReinstateResult result = service.reinstate(103L, Optional.empty());

        // newEndsAt = now-8h + 6h = now-2h → before now → clamp to now+1h
        assertThat(result.newEndsAt()).isEqualTo(NOW.plusHours(1));
    }

    @Test
    void reinstate_throwsWhenNotSuspended() {
        Auction auction = Auction.builder()
            .id(104L)
            .status(AuctionStatus.CANCELLED)
            .endsAt(NOW.plusHours(1))
            .title("Cancelled Auction")
            .seller(seller())
            .consecutiveWorldApiFailures(0)
            .build();

        when(auctionRepo.findByIdForUpdate(104L)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> service.reinstate(104L, Optional.empty()))
            .isInstanceOf(AuctionNotSuspendedException.class)
            .hasMessageContaining("CANCELLED");

        verify(auctionRepo, never()).save(any());
        verify(botMonitorLifecycleService, never()).onAuctionResumed(any());
        verify(notificationPublisher, never()).listingReinstated(anyLong(), anyLong(), any(), any());
    }
}
