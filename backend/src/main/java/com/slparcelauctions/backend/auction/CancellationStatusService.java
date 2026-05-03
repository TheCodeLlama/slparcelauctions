package com.slparcelauctions.backend.auction;

import java.util.Comparator;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.dto.CancellationHistoryDto;
import com.slparcelauctions.backend.auction.dto.CancellationStatusResponse;
import com.slparcelauctions.backend.auction.dto.NextConsequenceDto;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Read-only service for the seller-facing cancellation surfaces (Epic 08
 * sub-spec 2 §7.3 / §7.4). Two responsibilities:
 * <ul>
 *   <li>{@link #statusFor(Long)} — assembles the cancel-modal preview: prior
 *       offense count, current suspension state, and the next-consequence
 *       derivation using the same ladder logic as
 *       {@link CancellationService}. The next-consequence preview is purely
 *       hypothetical ("what would happen IF this cancel had bids") so the
 *       kind is never {@link CancellationOffenseKind#NONE}.</li>
 *   <li>{@link #historyFor(Long, Pageable)} — paginated read of
 *       {@link CancellationLog} rows for the caller, sorted
 *       {@code cancelledAt DESC}. Page size is clamped at 50 to prevent
 *       abuse.</li>
 * </ul>
 *
 * <p>Photo-URL resolution uses the flat {@code GET /api/v1/photos/{id}} endpoint
 * — first sort-order photo by sort_order; null when no listing photos exist.
 */
@Service
@RequiredArgsConstructor
public class CancellationStatusService {

    /** Page size cap so callers can't request unbounded history pages. */
    static final int MAX_PAGE_SIZE = 50;

    private final CancellationLogRepository logRepo;
    private final UserRepository userRepo;
    private final CancellationPenaltyProperties penaltyProps;

    @Transactional(readOnly = true)
    public CancellationStatusResponse statusFor(Long userId) {
        User u = userRepo.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        long prior = logRepo.countPriorOffensesWithBids(userId);
        // Indices 0..3 → WARNING, PENALTY, PENALTY_AND_30D, PERMANENT_BAN.
        // Anything beyond 3 collapses onto PERMANENT_BAN — a banned account
        // shouldn't be hitting this preview at all (the gate already blocks
        // listing creation), but the ladder math stays well-defined.
        int index = (int) Math.min(prior, 3L);
        CancellationOffenseKind nextKind;
        Long nextAmount;
        switch (index) {
            case 0 -> {
                nextKind = CancellationOffenseKind.WARNING;
                nextAmount = null;
            }
            case 1 -> {
                nextKind = CancellationOffenseKind.PENALTY;
                nextAmount = penaltyProps.penalty().secondOffenseL();
            }
            case 2 -> {
                nextKind = CancellationOffenseKind.PENALTY_AND_30D;
                nextAmount = penaltyProps.penalty().thirdOffenseL();
            }
            default -> {
                nextKind = CancellationOffenseKind.PERMANENT_BAN;
                nextAmount = null;
            }
        }

        return new CancellationStatusResponse(
                prior,
                new CancellationStatusResponse.CurrentSuspension(
                        u.getPenaltyBalanceOwed(),
                        u.getListingSuspensionUntil(),
                        u.getBannedFromListing()),
                NextConsequenceDto.from(nextKind, nextAmount));
    }

    @Transactional(readOnly = true)
    public Page<CancellationHistoryDto> historyFor(Long userId, Pageable page) {
        // Caller's Sort is ignored — spec §7.4 pins cancelledAt DESC. Size
        // is clamped at MAX_PAGE_SIZE on the way in.
        Pageable sorted = PageRequest.of(
                page.getPageNumber(),
                Math.min(page.getPageSize(), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "cancelledAt"));
        Page<CancellationLog> logs = logRepo.findBySellerId(userId, sorted);
        return logs.map(log -> CancellationHistoryDto.from(
                log, resolvePrimaryPhotoUrl(log.getAuction())));
    }

    private String resolvePrimaryPhotoUrl(Auction auction) {
        // Photos are eagerly hydrated by the {@code @EntityGraph} on
        // {@link CancellationLogRepository#findBySellerId} (one LEFT JOIN per
        // page, not per row), so we read straight off the entity collection
        // and project to the flat photo URL served by {@link PhotoController}.
        return auction.getPhotos().stream()
                .min(Comparator.comparing(AuctionPhoto::getSortOrder))
                .map(p -> "/api/v1/photos/" + p.getId())
                .orElse(null);
    }
}
