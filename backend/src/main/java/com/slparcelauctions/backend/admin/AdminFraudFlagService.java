package com.slparcelauctions.backend.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto.AuctionContextDto;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto.LinkedUserDto;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagSummaryDto;
import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.admin.exception.FraudFlagAlreadyResolvedException;
import com.slparcelauctions.backend.admin.exception.FraudFlagNotFoundException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminFraudFlagService {

    private final FraudFlagRepository fraudFlagRepository;
    private final UserRepository userRepository;
    private final AuctionRepository auctionRepository;
    private final BotMonitorLifecycleService botMonitorLifecycleService;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PagedResponse<AdminFraudFlagSummaryDto> list(
            String status,
            List<FraudFlagReason> reasons,
            Pageable pageable) {

        Specification<FraudFlag> spec = (root, query, cb) -> cb.conjunction();

        if ("open".equalsIgnoreCase(status)) {
            spec = spec.and((root, query, cb) -> cb.isFalse(root.get("resolved")));
        } else if ("resolved".equalsIgnoreCase(status)) {
            spec = spec.and((root, query, cb) -> cb.isTrue(root.get("resolved")));
        }
        // "all" — no extra predicate

        if (reasons != null && !reasons.isEmpty()) {
            List<FraudFlagReason> reasonsCopy = reasons;
            spec = spec.and((root, query, cb) -> root.get("reason").in(reasonsCopy));
        }

        Page<FraudFlag> page = fraudFlagRepository.findAll(spec, pageable);
        return PagedResponse.from(page.map(this::toSummary));
    }

    @Transactional(readOnly = true)
    public AdminFraudFlagDetailDto detail(Long flagId) {
        FraudFlag flag = fraudFlagRepository.findById(flagId)
            .orElseThrow(() -> new FraudFlagNotFoundException(flagId));

        Map<String, Object> evidenceJson = flag.getEvidenceJson() != null
            ? flag.getEvidenceJson()
            : Map.of();

        // Scan evidence values for UUID strings — batch-resolve once
        Set<UUID> uuids = new HashSet<>();
        for (Object val : evidenceJson.values()) {
            if (val instanceof String s) {
                try {
                    uuids.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                    // not a UUID string
                }
            }
        }

        Map<String, LinkedUserDto> linkedUsers = new HashMap<>();
        if (!uuids.isEmpty()) {
            List<User> users = userRepository.findAllBySlAvatarUuidIn(uuids);
            for (User u : users) {
                linkedUsers.put(u.getSlAvatarUuid().toString(), new LinkedUserDto(u.getId(), u.getDisplayName()));
            }
        }

        Auction auction = flag.getAuction();
        AuctionContextDto auctionCtx = null;
        long siblingOpenFlagCount = 0L;
        if (auction != null) {
            User seller = auction.getSeller();
            auctionCtx = new AuctionContextDto(
                auction.getId(),
                auction.getTitle(),
                auction.getStatus(),
                auction.getEndsAt(),
                auction.getSuspendedAt(),
                seller != null ? seller.getId() : null,
                seller != null ? seller.getDisplayName() : null
            );
            siblingOpenFlagCount = fraudFlagRepository
                .countByAuctionIdAndResolvedFalseAndIdNot(auction.getId(), flagId);
        }

        User resolvedBy = flag.getResolvedBy();

        return new AdminFraudFlagDetailDto(
            flag.getId(),
            flag.getReason(),
            flag.getDetectedAt(),
            flag.getResolvedAt(),
            resolvedBy != null ? resolvedBy.getDisplayName() : null,
            flag.getAdminNotes(),
            auctionCtx,
            evidenceJson,
            linkedUsers,
            siblingOpenFlagCount
        );
    }

    @Transactional
    public AdminFraudFlagDetailDto dismiss(Long flagId, Long adminUserId, String adminNotes) {
        FraudFlag flag = fraudFlagRepository.findById(flagId)
            .orElseThrow(() -> new FraudFlagNotFoundException(flagId));
        if (flag.isResolved()) {
            throw new FraudFlagAlreadyResolvedException(flagId);
        }
        User admin = userRepository.findById(adminUserId)
            .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));
        flag.setResolved(true);
        flag.setResolvedAt(OffsetDateTime.now(clock));
        flag.setResolvedBy(admin);
        flag.setAdminNotes(adminNotes);
        fraudFlagRepository.save(flag);
        return detail(flagId);
    }

    @Transactional
    public AdminFraudFlagDetailDto reinstate(Long flagId, Long adminUserId, String adminNotes) {
        FraudFlag flag = fraudFlagRepository.findById(flagId)
            .orElseThrow(() -> new FraudFlagNotFoundException(flagId));
        if (flag.isResolved()) {
            throw new FraudFlagAlreadyResolvedException(flagId);
        }
        Auction auction = flag.getAuction();
        if (auction == null || auction.getStatus() != AuctionStatus.SUSPENDED) {
            throw new AuctionNotSuspendedException(auction == null ? null : auction.getStatus());
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime suspendedFrom = auction.getSuspendedAt() != null
            ? auction.getSuspendedAt()
            : flag.getDetectedAt();
        Duration suspensionDuration = Duration.between(suspendedFrom, now);
        OffsetDateTime newEndsAt = auction.getEndsAt().plus(suspensionDuration);
        if (newEndsAt.isBefore(now)) {
            newEndsAt = now.plusHours(1);
        }

        auction.setStatus(AuctionStatus.ACTIVE);
        auction.setSuspendedAt(null);
        auction.setEndsAt(newEndsAt);
        auctionRepository.save(auction);

        botMonitorLifecycleService.onAuctionResumed(auction);

        User admin = userRepository.findById(adminUserId)
            .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));
        flag.setResolved(true);
        flag.setResolvedAt(now);
        flag.setResolvedBy(admin);
        flag.setAdminNotes(adminNotes);
        fraudFlagRepository.save(flag);

        notificationPublisher.listingReinstated(
            auction.getSeller().getId(), auction.getId(),
            auction.getTitle(), newEndsAt);

        return detail(flagId);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private AdminFraudFlagSummaryDto toSummary(FraudFlag flag) {
        Auction auction = flag.getAuction();
        User resolvedBy = flag.getResolvedBy();

        Long auctionId = null;
        String auctionTitle = null;
        com.slparcelauctions.backend.auction.AuctionStatus auctionStatus = null;
        String parcelRegionName = null;

        if (auction != null) {
            auctionId = auction.getId();
            auctionTitle = auction.getTitle();
            auctionStatus = auction.getStatus();
        }

        if (flag.getParcel() != null) {
            parcelRegionName = flag.getParcel().getRegionName();
        }

        return new AdminFraudFlagSummaryDto(
            flag.getId(),
            flag.getReason(),
            flag.getDetectedAt(),
            auctionId,
            auctionTitle,
            auctionStatus,
            parcelRegionName,
            null, // parcelLocalId — not stored in Parcel entity (SL concept not mapped)
            flag.isResolved(),
            flag.getResolvedAt(),
            resolvedBy != null ? resolvedBy.getDisplayName() : null
        );
    }
}
