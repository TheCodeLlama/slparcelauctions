package com.slparcelauctions.backend.admin.users;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminAction;
import com.slparcelauctions.backend.admin.audit.AdminActionRepository;
import com.slparcelauctions.backend.admin.ban.Ban;
import com.slparcelauctions.backend.admin.ban.BanRepository;
import com.slparcelauctions.backend.admin.reports.ListingReport;
import com.slparcelauctions.backend.admin.reports.ListingReportRepository;
import com.slparcelauctions.backend.admin.users.dto.AdminUserBidRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserCancellationRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserDetailDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserFraudFlagRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserListingRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserModerationRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserReportRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserSummaryDto;
import com.slparcelauctions.backend.admin.users.dto.UserIpProjection;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.Bid;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.CancellationLog;
import com.slparcelauctions.backend.auction.CancellationLogRepository;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepo;
    private final BanRepository banRepo;
    private final AuctionRepository auctionRepo;
    private final BidRepository bidRepo;
    private final CancellationLogRepository cancellationLogRepo;
    private final ListingReportRepository listingReportRepo;
    private final FraudFlagRepository fraudFlagRepo;
    private final AdminActionRepository adminActionRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PagedResponse<AdminUserSummaryDto> search(String input, Pageable pageable) {
        UUID uuid = tryParseUuid(input);
        String search = uuid != null ? null
            : (input == null || input.isBlank() ? null : input.trim());
        Page<User> page = userRepo.searchAdmin(search, uuid, pageable);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return PagedResponse.from(page.map(u -> toSummary(u, now)));
    }

    @Transactional(readOnly = true)
    public AdminUserDetailDto detail(Long userId) {
        User u = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
        OffsetDateTime now = OffsetDateTime.now(clock);
        AdminUserDetailDto.ActiveBanSummary activeBan = null;
        if (u.getSlAvatarUuid() != null) {
            List<Ban> hits = banRepo.findActiveByAvatar(u.getSlAvatarUuid(), now);
            if (!hits.isEmpty()) {
                Ban b = hits.get(0);
                activeBan = new AdminUserDetailDto.ActiveBanSummary(
                    b.getId(), b.getBanType(), b.getNotes(), b.getExpiresAt());
            }
        }
        return new AdminUserDetailDto(
            u.getId(), u.getEmail(), u.getDisplayName(),
            u.getSlAvatarUuid(), u.getSlDisplayName(),
            u.getRole(), Boolean.TRUE.equals(u.getVerified()), u.getVerifiedAt(),
            u.getCreatedAt(),
            u.getCompletedSales(), u.getCancelledWithBids(),
            u.getEscrowExpiredUnfulfilled(), u.getDismissedReportsCount(),
            u.getPenaltyBalanceOwed(), u.getListingSuspensionUntil(),
            Boolean.TRUE.equals(u.getBannedFromListing()),
            activeBan);
    }

    @Transactional(readOnly = true)
    public PagedResponse<AdminUserListingRowDto> listings(Long userId, Pageable pageable) {
        Page<Auction> page = auctionRepo.findBySellerIdOrderByCreatedAtDesc(userId, pageable);
        return PagedResponse.from(page.map(a -> new AdminUserListingRowDto(
            a.getId(), a.getTitle(),
            a.getParcel() != null ? a.getParcel().getRegionName() : null,
            a.getStatus(), a.getEndsAt(), a.getFinalBidAmount())));
    }

    @Transactional(readOnly = true)
    public PagedResponse<AdminUserBidRowDto> bids(Long userId, Pageable pageable) {
        Page<Bid> page = bidRepo.findByBidderIdOrderByCreatedAtDesc(userId, pageable);
        return PagedResponse.from(page.map(b -> new AdminUserBidRowDto(
            b.getId(), b.getAuction().getId(), b.getAuction().getTitle(),
            b.getAmount(), b.getCreatedAt(), b.getAuction().getStatus())));
    }

    @Transactional(readOnly = true)
    public PagedResponse<AdminUserCancellationRowDto> cancellations(Long userId, Pageable pageable) {
        Page<CancellationLog> page = cancellationLogRepo.findBySellerIdOrderByCancelledAtDesc(userId, pageable);
        return PagedResponse.from(page.map(c -> new AdminUserCancellationRowDto(
            c.getId(), c.getAuction().getId(), c.getAuction().getTitle(),
            c.getCancelledFromStatus(), Boolean.TRUE.equals(c.getHadBids()),
            c.getReason(),
            c.getPenaltyKind() != null ? c.getPenaltyKind().name() : null,
            c.getPenaltyAmountL(), c.getCancelledByAdminId(), c.getCancelledAt())));
    }

    @Transactional(readOnly = true)
    public PagedResponse<AdminUserReportRowDto> reports(Long userId, Pageable pageable) {
        Page<ListingReport> page = listingReportRepo.findByUserAsReporterOrSeller(userId, pageable);
        return PagedResponse.from(page.map(r -> {
            String direction = r.getReporter().getId().equals(userId) ? "FILED_BY" : "AGAINST_LISTING";
            return new AdminUserReportRowDto(
                r.getId(), r.getAuction().getId(), r.getAuction().getTitle(),
                r.getReason().name(), r.getStatus().name(),
                direction, r.getCreatedAt(), r.getUpdatedAt());
        }));
    }

    @Transactional(readOnly = true)
    public PagedResponse<AdminUserFraudFlagRowDto> fraudFlags(Long userId, Pageable pageable) {
        Page<FraudFlag> page = fraudFlagRepo.findByAuctionSellerId(userId, pageable);
        return PagedResponse.from(page.map(f -> new AdminUserFraudFlagRowDto(
            f.getId(),
            f.getAuction() != null ? f.getAuction().getId() : null,
            f.getAuction() != null ? f.getAuction().getTitle() : null,
            f.getReason().name(), f.isResolved(), f.getDetectedAt())));
    }

    @Transactional(readOnly = true)
    public PagedResponse<AdminUserModerationRowDto> moderation(Long userId, Pageable pageable) {
        Page<AdminAction> page = adminActionRepo.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            com.slparcelauctions.backend.admin.audit.AdminActionTargetType.USER, userId, pageable);
        return PagedResponse.from(page.map(a -> new AdminUserModerationRowDto(
            a.getId(), a.getActionType().name(),
            a.getAdminUser().getDisplayName(),
            a.getNotes(), a.getCreatedAt())));
    }

    @Transactional(readOnly = true)
    public List<UserIpProjection> ips(Long userId) {
        return refreshTokenRepo.findIpSummaryByUserId(userId);
    }

    private AdminUserSummaryDto toSummary(User u, OffsetDateTime now) {
        boolean banned = u.getSlAvatarUuid() != null
            && !banRepo.findActiveByAvatar(u.getSlAvatarUuid(), now).isEmpty();
        return new AdminUserSummaryDto(
            u.getId(), u.getEmail(), u.getDisplayName(),
            u.getSlAvatarUuid(), u.getSlDisplayName(),
            u.getRole(), Boolean.TRUE.equals(u.getVerified()), banned,
            u.getCompletedSales(), u.getCancelledWithBids(),
            u.getCreatedAt());
    }

    private UUID tryParseUuid(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            return UUID.fromString(input.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
