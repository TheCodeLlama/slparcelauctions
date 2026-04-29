package com.slparcelauctions.backend.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
import com.slparcelauctions.backend.notification.NotificationPublisher;

import lombok.RequiredArgsConstructor;

/**
 * Shared primitive for "flip SUSPENDED auction back to ACTIVE." Called
 * from sub-spec 1's fraud-flag reinstate path AND from sub-spec 2's
 * standalone /admin/auctions/{id}/reinstate endpoint. Auction-state-only
 * — caller is responsible for any flag/report state changes and audit
 * row writing (different callers want different audit semantics).
 */
@Service
@RequiredArgsConstructor
public class AdminAuctionService {

    private final AuctionRepository auctionRepo;
    private final BotMonitorLifecycleService botMonitorLifecycleService;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Transactional
    public AdminAuctionReinstateResult reinstate(
            Long auctionId,
            Optional<OffsetDateTime> fallbackSuspendedFrom) {

        Auction auction = auctionRepo.findByIdForUpdate(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (auction.getStatus() != AuctionStatus.SUSPENDED) {
            throw new AuctionNotSuspendedException(auction.getStatus());
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime suspendedFrom = auction.getSuspendedAt() != null
            ? auction.getSuspendedAt()
            : fallbackSuspendedFrom.orElse(now); // 0-extension if no record

        Duration suspensionDuration = Duration.between(suspendedFrom, now);
        OffsetDateTime newEndsAt = auction.getEndsAt().plus(suspensionDuration);
        if (newEndsAt.isBefore(now)) {
            newEndsAt = now.plusHours(1);
        }

        auction.setStatus(AuctionStatus.ACTIVE);
        auction.setSuspendedAt(null);
        auction.setEndsAt(newEndsAt);
        auctionRepo.save(auction);

        botMonitorLifecycleService.onAuctionResumed(auction);

        notificationPublisher.listingReinstated(
            auction.getSeller().getId(), auction.getId(),
            auction.getTitle(), newEndsAt);

        return new AdminAuctionReinstateResult(auction, suspensionDuration, newEndsAt);
    }

    public record AdminAuctionReinstateResult(
        Auction auction, Duration suspensionDuration, OffsetDateTime newEndsAt
    ) {}
}
