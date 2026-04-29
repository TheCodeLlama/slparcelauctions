package com.slparcelauctions.backend.user.deletion;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.deletion.exception.ActiveAuctionsException;
import com.slparcelauctions.backend.user.deletion.exception.ActiveHighBidsException;
import com.slparcelauctions.backend.user.deletion.exception.ActiveProxyBidsException;
import com.slparcelauctions.backend.user.deletion.exception.InvalidPasswordException;
import com.slparcelauctions.backend.user.deletion.exception.OpenEscrowsException;
import com.slparcelauctions.backend.user.deletion.exception.UserAlreadyDeletedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserDeletionService {

    private static final List<AuctionStatus> BLOCKING_AUCTION_STATUSES = List.of(
            AuctionStatus.ACTIVE,
            AuctionStatus.ESCROW_PENDING,
            AuctionStatus.ESCROW_FUNDED,
            AuctionStatus.TRANSFER_PENDING);

    private static final List<EscrowState> OPEN_ESCROW_STATES = List.of(
            EscrowState.FUNDED,
            EscrowState.TRANSFER_PENDING,
            EscrowState.DISPUTED,
            EscrowState.FROZEN);

    private final UserRepository userRepo;
    private final AuctionRepository auctionRepo;
    private final EscrowRepository escrowRepo;
    private final ProxyBidRepository proxyBidRepo;
    private final PasswordEncoder passwordEncoder;
    private final AdminActionService adminActionService;
    private final Clock clock;

    /**
     * Self-service account deletion. Verifies the supplied password before
     * running the precondition matrix and cascade.
     *
     * @param userId   the authenticated user's ID
     * @param password the plaintext password supplied by the user for confirmation
     * @throws UserAlreadyDeletedException if the account is already soft-deleted
     * @throws InvalidPasswordException    if the password does not match
     * @throws ActiveAuctionsException     if the user has active/in-flight auctions as seller
     * @throws OpenEscrowsException        if the user has open escrows as seller or winner
     * @throws ActiveHighBidsException     if the user is the current high bidder on any ACTIVE auction
     * @throws ActiveProxyBidsException    if the user has any ACTIVE proxy bids
     */
    @Transactional
    public void deleteSelf(Long userId, String password) {
        User user = loadUserOrThrow(userId);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidPasswordException();
        }
        checkPreconditions(user);
        runCascade(user);
        log.info("User {} deleted their account via self-service", userId);
    }

    /**
     * Admin-initiated account deletion. Skips the password check and records
     * an audit action under the admin's identity.
     *
     * @param targetUserId the ID of the user to delete
     * @param adminUserId  the ID of the admin performing the deletion
     * @param adminNote    the admin's reason / note (recorded in the audit log)
     * @throws UserAlreadyDeletedException if the account is already soft-deleted
     * @throws ActiveAuctionsException     if the user has active/in-flight auctions as seller
     * @throws OpenEscrowsException        if the user has open escrows as seller or winner
     * @throws ActiveHighBidsException     if the user is the current high bidder on any ACTIVE auction
     * @throws ActiveProxyBidsException    if the user has any ACTIVE proxy bids
     */
    @Transactional
    public void deleteByAdmin(Long targetUserId, Long adminUserId, String adminNote) {
        User user = loadUserOrThrow(targetUserId);
        checkPreconditions(user);
        runCascade(user);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetUserId", targetUserId);
        details.put("adminNote", adminNote);
        adminActionService.record(
                adminUserId,
                AdminActionType.USER_DELETED_BY_ADMIN,
                AdminActionTargetType.USER,
                targetUserId,
                adminNote,
                details);
        log.info("User {} deleted by admin {}: {}", targetUserId, adminUserId, adminNote);
    }

    // ------------------------------------------------------------------ //
    //  Internals                                                          //
    // ------------------------------------------------------------------ //

    private User loadUserOrThrow(Long userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (user.getDeletedAt() != null) {
            throw new UserAlreadyDeletedException(userId);
        }
        return user;
    }

    private void checkPreconditions(User user) {
        List<Long> activeAuctionIds = auctionRepo.findIdsBySellerAndStatusIn(user, BLOCKING_AUCTION_STATUSES);
        if (!activeAuctionIds.isEmpty()) {
            throw new ActiveAuctionsException(activeAuctionIds);
        }

        List<Long> openEscrowIds = escrowRepo.findIdsByUserInvolvedAndStateIn(user.getId(), OPEN_ESCROW_STATES);
        if (!openEscrowIds.isEmpty()) {
            throw new OpenEscrowsException(openEscrowIds);
        }

        List<Long> highBidAuctionIds = auctionRepo.findIdsByCurrentBidderIdAndActive(user.getId());
        if (!highBidAuctionIds.isEmpty()) {
            throw new ActiveHighBidsException(highBidAuctionIds);
        }

        List<Long> activeProxyBidIds = proxyBidRepo.findActiveIdsByBidder(user);
        if (!activeProxyBidIds.isEmpty()) {
            throw new ActiveProxyBidsException(activeProxyBidIds);
        }
    }

    /**
     * Scrubs PII from the User row and marks the account deleted. The
     * {@code tokenVersion} bump invalidates all outstanding access tokens
     * immediately.
     *
     * <p>Fields retained for fraud/audit purposes:
     * <ul>
     *   <li>{@code slAvatarUuid} — cross-references with Second Life for
     *       post-deletion fraud investigations.</li>
     *   <li>{@code id}, {@code createdAt} — immutable audit anchors.</li>
     * </ul>
     *
     * <p>Additional cascade steps (refresh tokens, saved searches,
     * watchlists, notification preferences, in-app notifications, pending
     * review obligations) are added in subsequent tasks once the
     * corresponding repository methods exist.
     */
    private void runCascade(User user) {
        user.setEmail(null);
        user.setDisplayName("Deleted user #" + user.getId());
        user.setBio(null);
        user.setProfilePicUrl(null);
        user.setSlAvatarName(null);
        user.setSlDisplayName(null);
        user.setSlUsername(null);
        user.setDeletedAt(OffsetDateTime.now(clock));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepo.save(user);
    }
}
