package com.slparcelauctions.backend.admin.ban;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.ban.dto.AdminBanRowDto;
import com.slparcelauctions.backend.admin.ban.dto.CreateBanRequest;
import com.slparcelauctions.backend.admin.ban.exception.BanAlreadyLiftedException;
import com.slparcelauctions.backend.admin.ban.exception.BanNotFoundException;
import com.slparcelauctions.backend.admin.ban.exception.BanTypeFieldMismatchException;
import com.slparcelauctions.backend.admin.users.dto.UserIpProjection;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminBanService {

    private final BanRepository banRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BanCacheInvalidator cacheInvalidator;
    private final AdminActionService adminActionService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PagedResponse<AdminBanRowDto> list(String status, BanType type, Pageable pageable) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Page<Ban> page;
        if ("history".equalsIgnoreCase(status)) {
            page = banRepository.findHistory(now, pageable);
        } else {
            page = banRepository.findActive(now, pageable);
        }
        return PagedResponse.from(page.map(this::toRow));
    }

    @Transactional
    public AdminBanRowDto create(CreateBanRequest req, Long adminUserId) {
        validateTypeMatchesFields(req);
        User admin = userRepository.findById(adminUserId)
            .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));

        Ban ban = banRepository.save(Ban.builder()
            .banType(req.banType())
            .ipAddress(req.ipAddress())
            .slAvatarUuid(req.slAvatarUuid())
            .reasonCategory(req.reasonCategory())
            .notes(req.reasonText())
            .adminUser(admin)
            .expiresAt(req.expiresAt())
            .build());

        // tv-bump only for AVATAR/BOTH bans where the avatar maps to a registered user.
        if ((req.banType() == BanType.AVATAR || req.banType() == BanType.BOTH)
                && req.slAvatarUuid() != null) {
            userRepository.findBySlAvatarUuid(req.slAvatarUuid())
                .ifPresent(u -> userRepository.bumpTokenVersion(u.getId()));
        }

        cacheInvalidator.invalidate(req.ipAddress(), req.slAvatarUuid());

        adminActionService.record(adminUserId, AdminActionType.CREATE_BAN,
            AdminActionTargetType.BAN, ban.getId(), req.reasonText(),
            Map.of("banType", req.banType().name()));

        return toRow(ban);
    }

    @Transactional
    public AdminBanRowDto lift(Long banId, Long adminUserId, String liftedReason) {
        Ban ban = banRepository.findById(banId)
            .orElseThrow(() -> new BanNotFoundException(banId));
        if (ban.getLiftedAt() != null) {
            throw new BanAlreadyLiftedException(banId);
        }
        User admin = userRepository.findById(adminUserId)
            .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));
        OffsetDateTime now = OffsetDateTime.now(clock);

        ban.setLiftedAt(now);
        ban.setLiftedByUser(admin);
        ban.setLiftedReason(liftedReason);
        banRepository.save(ban);

        cacheInvalidator.invalidate(ban.getIpAddress(), ban.getSlAvatarUuid());

        adminActionService.record(adminUserId, AdminActionType.LIFT_BAN,
            AdminActionTargetType.BAN, banId, liftedReason, null);

        return toRow(ban);
    }

    private void validateTypeMatchesFields(CreateBanRequest req) {
        switch (req.banType()) {
            case IP -> {
                if (req.ipAddress() == null || req.ipAddress().isBlank()
                        || req.slAvatarUuid() != null) {
                    throw new BanTypeFieldMismatchException(
                        "IP ban requires ipAddress and no slAvatarUuid");
                }
            }
            case AVATAR -> {
                if (req.slAvatarUuid() == null
                        || (req.ipAddress() != null && !req.ipAddress().isBlank())) {
                    throw new BanTypeFieldMismatchException(
                        "AVATAR ban requires slAvatarUuid and no ipAddress");
                }
            }
            case BOTH -> {
                if (req.ipAddress() == null || req.ipAddress().isBlank()
                        || req.slAvatarUuid() == null) {
                    throw new BanTypeFieldMismatchException(
                        "BOTH ban requires both ipAddress and slAvatarUuid");
                }
            }
        }
    }

    private AdminBanRowDto toRow(Ban ban) {
        Long avatarLinkedUserId = null;
        String avatarLinkedDisplayName = null;
        String firstSeenIp = null;
        if (ban.getSlAvatarUuid() != null) {
            Optional<User> linkedOpt = userRepository.findBySlAvatarUuid(ban.getSlAvatarUuid());
            if (linkedOpt.isPresent()) {
                User linked = linkedOpt.get();
                avatarLinkedUserId = linked.getId();
                avatarLinkedDisplayName = linked.getDisplayName();
                List<UserIpProjection> ips = refreshTokenRepository.findIpSummaryByUserId(linked.getId());
                if (!ips.isEmpty()) {
                    firstSeenIp = ips.get(0).ipAddress();
                }
            }
        }
        return new AdminBanRowDto(
            ban.getId(),
            ban.getBanType(),
            ban.getIpAddress(),
            ban.getSlAvatarUuid(),
            avatarLinkedUserId,
            avatarLinkedDisplayName,
            firstSeenIp,
            ban.getReasonCategory(),
            ban.getNotes(),
            ban.getAdminUser().getId(),
            ban.getAdminUser().getDisplayName(),
            ban.getExpiresAt(),
            ban.getCreatedAt(),
            ban.getLiftedAt(),
            ban.getLiftedByUser() == null ? null : ban.getLiftedByUser().getId(),
            ban.getLiftedByUser() == null ? null : ban.getLiftedByUser().getDisplayName(),
            ban.getLiftedReason());
    }
}
