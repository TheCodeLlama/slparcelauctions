package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.dto.AuctionCancelledEnvelope;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.BrokerCancelNotApplicableException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.monitoring.ListingSuspension;
import com.slparcelauctions.backend.auction.monitoring.ListingSuspensionRepository;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.FreezeReason;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.BidReservationReleaseReason;
import com.slparcelauctions.backend.wallet.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles /cancel transitions. Cancellation is allowed from pre-live and live
 * states; disallowed from ENDED+ (transfer in progress) and terminal states.
 * Refund records are created for any state where listingFeePaid=true. Bid counter
 * increments only when cancelling an ACTIVE auction with bids.
 *
 * <p><strong>Concurrency model.</strong> The method accepts an id rather than a
 * pre-loaded entity and re-fetches under {@link AuctionRepository#findByIdForUpdate}
 * so that a cancellation racing against {@code BidService.placeBid} or
 * {@code AuctionEndTask.closeOne} serialises at the database row lock. The
 * loser sees whichever status the winner committed (ACTIVE -> CANCELLED or
 * ACTIVE -> ENDED) and surfaces a {@link InvalidAuctionStateException}. See
 * {@code BidCancelRaceTest} for the pin.
 *
 * <p><strong>Penalty ladder (Epic 08 sub-spec 2).</strong> When an
 * {@code ACTIVE} auction with bids is cancelled, the seller's prior
 * cancelled-with-bids count drives a four-step ladder
 * (see {@link CancellationOffenseKind}). The seller row is pessimistically
 * locked BEFORE the count read so two concurrent cancellations on the same
 * seller serialise and observe distinct ladder indices. The selected
 * {@link CancellationOffenseKind} and L$ amount are snapshotted onto the
 * {@link CancellationLog} row as immutable historical fact.
 *
 * <p><strong>Wallet reservations.</strong> Every cancel path releases any
 * active {@link com.slparcelauctions.backend.wallet.BidReservation} rows for
 * the auction (via {@link WalletService#releaseReservationsForAuction}) before
 * flipping status. Runs inside the cancellation tx so a rollback restores
 * reservations alongside the status flip. Centralised across every cancel
 * method so bidders never observe a stale "held" L$ row after the listing is
 * gone -- spec §10.2 step 2 / Epic 08 sub-spec 2 acceptance criterion #4.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationService {

    private static final Set<AuctionStatus> CANCELLABLE = Set.of(
            AuctionStatus.DRAFT,
            AuctionStatus.DRAFT_PAID,
            AuctionStatus.VERIFICATION_PENDING,
            AuctionStatus.VERIFICATION_FAILED,
            AuctionStatus.ACTIVE);

    private final AuctionRepository auctionRepo;
    private final BidRepository bidRepo;
    private final CancellationLogRepository logRepo;
    private final ListingFeeRefundRepository refundRepo;
    private final UserRepository userRepo;
    private final AuctionBroadcastPublisher broadcastPublisher;
    private final NotificationPublisher notificationPublisher;
    private final CancellationPenaltyProperties penaltyProps;
    private final BanCheckService banCheckService;
    private final RealtyGroupAuthorizer realtyGroupAuthorizer;
    private final ListingSuspensionRepository listingSuspensionRepo;
    private final WalletService walletService;
    private final EscrowService escrowService;
    private final EscrowRepository escrowRepo;
    private final Clock clock;

    @Transactional
    public Auction cancel(Long auctionId, String reason, String ipAddress) {
        Auction a = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (!CANCELLABLE.contains(a.getStatus())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "CANCEL");
        }
        if (a.getStatus() == AuctionStatus.ACTIVE) {
            if (a.getEndsAt() != null && OffsetDateTime.now(clock).isAfter(a.getEndsAt())) {
                throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "CANCEL_AFTER_END");
            }
        }

        AuctionStatus from = a.getStatus();
        boolean hadBids = a.getBidCount() != null && a.getBidCount() > 0;
        boolean activeWithBids = from == AuctionStatus.ACTIVE && hadBids;

        // Pessimistic lock on the seller row -- must precede the COUNT so two
        // concurrent cancellations on the same seller serialise here and
        // observe distinct prior-offense counts. Pre-active and active-without-
        // bids cancellations also acquire the lock to keep the path uniform;
        // the lock is cheap and avoids branching that would matter only on
        // the cold path.
        User seller = userRepo.findByIdForUpdate(a.getSeller().getId())
                .orElseThrow(); // FK guarantees existence

        banCheckService.assertNotBanned(ipAddress, seller.getSlAvatarUuid());

        // Pre-INSERT count -> ladder index -> consequence snapshot. Indices are
        // clamped at 3 so the 4th-and-beyond offenses all snapshot
        // PERMANENT_BAN. This call MUST run before saving the new log row
        // (off-by-one trap).
        CancellationOffenseKind kind;
        Long amountL;
        if (activeWithBids) {
            long prior = logRepo.countPriorOffensesWithBids(seller.getId());
            int index = (int) Math.min(prior, 3L);
            switch (index) {
                case 0 -> {
                    kind = CancellationOffenseKind.WARNING;
                    amountL = null;
                }
                case 1 -> {
                    kind = CancellationOffenseKind.PENALTY;
                    amountL = penaltyProps.penalty().secondOffenseL();
                }
                case 2 -> {
                    kind = CancellationOffenseKind.PENALTY_AND_30D;
                    amountL = penaltyProps.penalty().thirdOffenseL();
                }
                default -> {
                    kind = CancellationOffenseKind.PERMANENT_BAN;
                    amountL = null;
                }
            }
        } else {
            kind = CancellationOffenseKind.NONE;
            amountL = null;
        }

        // Snapshot the consequence onto the log row -- immutable historical
        // fact, never recomputed from live state.
        logRepo.save(CancellationLog.builder()
                .auction(a)
                .seller(seller)
                .cancelledFromStatus(from.name())
                .hadBids(hadBids)
                .reason(reason)
                .penaltyKind(kind)
                .penaltyAmountL(amountL)
                .build());

        // Apply consequence + arm the post-cancel ownership-watcher window.
        if (activeWithBids) {
            seller.setCancelledWithBids(seller.getCancelledWithBids() + 1);
            OffsetDateTime now = OffsetDateTime.now(clock);
            switch (kind) {
                case PENALTY -> seller.setPenaltyBalanceOwed(
                        seller.getPenaltyBalanceOwed() + amountL);
                case PENALTY_AND_30D -> {
                    seller.setPenaltyBalanceOwed(
                            seller.getPenaltyBalanceOwed() + amountL);
                    seller.setListingSuspensionUntil(
                            now.plusDays(penaltyProps.penalty().thirdOffenseSuspensionDays()));
                }
                case PERMANENT_BAN -> seller.setBannedFromListing(true);
                default -> {
                    // WARNING / NONE -- log only, no consequence.
                }
            }
            userRepo.save(seller);
            a.setPostCancelWatchUntil(now.plusHours(penaltyProps.postCancelWatchHours()));
        }

        // Refund record if fee was paid and this is a pre-live cancellation
        if (Boolean.TRUE.equals(a.getListingFeePaid())
                && from != AuctionStatus.ACTIVE) {
            refundRepo.save(ListingFeeRefund.builder()
                    .auction(a)
                    .amount(a.getListingFeeAmt() == null ? 0L : a.getListingFeeAmt())
                    .status(RefundStatus.PENDING)
                    .build());
            log.info("Listing fee refund (PENDING) created for auction {}", a.getId());
        }

        // Release any active wallet reservations for this auction before
        // flipping status. Centralised across every cancel path so bidders
        // never see a stale "held" L$ row after the listing is gone.
        walletService.releaseReservationsForAuction(a.getId(),
                BidReservationReleaseReason.AUCTION_CANCELLED);

        a.setStatus(AuctionStatus.CANCELLED);
        Auction saved = auctionRepo.save(a);

        // Fan-out LISTING_CANCELLED_BY_SELLER to each bidder who ever bid on
        // this auction. The publisher registers an afterCommit hook internally,
        // so bidder notifications fire only when the cancellation commits.
        // An empty list is a no-op; a stale bidderId causes a contained warning
        // (no FK-violation aborts the remaining recipients).
        List<Long> allBidderIds = bidRepo.findDistinctBidderUserIdsByAuctionId(a.getId());
        notificationPublisher.listingCancelledBySellerFanout(
                a.getId(), allBidderIds, a.getTitle(), reason);

        log.info("Auction {} cancelled from {} (hadBids={}, kind={})",
                a.getId(), from, hadBids, kind);

        // Register the WS broadcast for afterCommit only -- never inside the
        // tx. Subscribers must never observe a cancellation that rolls back
        // on a late DB failure. Mirrors the pattern used by ReviewService.
        AuctionCancelledEnvelope envelope = AuctionCancelledEnvelope.of(
                saved, hadBids, OffsetDateTime.now(clock));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            broadcastPublisher.publishCancelled(envelope);
                        }
                    });
        } else {
            // Slice tests / non-tx callers -- fire immediately.
            broadcastPublisher.publishCancelled(envelope);
        }

        return saved;
    }

    /**
     * Admin-initiated cancellation. Skips the penalty ladder entirely --
     * staff removal is not a seller offense. The {@link CancellationLog} row
     * is written with {@code cancelledByAdminId} set so that
     * {@code countPriorOffensesWithBids} (which filters {@code IS NULL}) does
     * not count it against the seller's ladder.
     *
     * <p>The seller receives a distinct {@code LISTING_REMOVED_BY_ADMIN}
     * notification. Bidders receive the existing
     * {@code listingCancelledBySellerFanout} (whose body copy is cause-neutral
     * as of sub-spec 2 Task 3). {@code seller.cancelledWithBids} is NOT
     * incremented.
     */
    @Transactional
    public Auction cancelByAdmin(Long auctionId, Long adminUserId, String notes) {
        Auction a = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (!CANCELLABLE.contains(a.getStatus())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "ADMIN_CANCEL");
        }

        boolean hadBids = a.getBidCount() != null && a.getBidCount() > 0;
        AuctionStatus from = a.getStatus();

        // No penalty ladder. No seller-row lock -- no seller-side state changes.
        logRepo.save(CancellationLog.builder()
                .auction(a)
                .seller(a.getSeller())
                .cancelledFromStatus(from.name())
                .hadBids(hadBids)
                .reason(notes)
                .penaltyKind(CancellationOffenseKind.NONE)
                .penaltyAmountL(null)
                .cancelledByAdminId(adminUserId)
                .build());

        // Release any active wallet reservations for this auction before
        // flipping status. Centralised across every cancel path so bidders
        // never see a stale "held" L$ row after the listing is gone.
        walletService.releaseReservationsForAuction(a.getId(),
                BidReservationReleaseReason.AUCTION_CANCELLED);

        a.setStatus(AuctionStatus.CANCELLED);
        Auction saved = auctionRepo.save(a);

        notificationPublisher.listingRemovedByAdmin(
                a.getSeller().getId(), a.getId(), a.getTitle(), notes);

        if (hadBids) {
            List<Long> bidderIds = bidRepo.findDistinctBidderUserIdsByAuctionId(a.getId());
            notificationPublisher.listingCancelledBySellerFanout(
                    a.getId(), bidderIds, a.getTitle(), notes);
        }

        log.info("Auction {} admin-cancelled from {} by adminUserId={} (hadBids={})",
                a.getId(), from, adminUserId, hadBids);

        AuctionCancelledEnvelope envelope = AuctionCancelledEnvelope.of(
                saved, hadBids, OffsetDateTime.now(clock));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            broadcastPublisher.publishCancelled(envelope);
                        }
                    });
        } else {
            broadcastPublisher.publishCancelled(envelope);
        }

        return saved;
    }

    /**
     * Admin cancels a listing whose escrow is in TRANSFER_PENDING. Refunds
     * the winner from escrow, marks the escrow EXPIRED with reason
     * ADMIN_CANCEL, and flips the auction to CANCELLED. The seller's
     * penalty ladder is NOT touched -- staff action, not a seller offense.
     *
     * <p>Caller is {@link com.slparcelauctions.backend.admin.listings.AdminListingService#cancel(java.util.UUID, Long, String)};
     * the seller-initiated and pre-active admin paths route through
     * {@link #cancelByAdmin(Long, Long, String)} instead.
     */
    @Transactional
    public Auction cancelByAdminFromEscrow(Long auctionId, Long adminUserId, String notes) {
        Auction a = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (a.getStatus() != AuctionStatus.TRANSFER_PENDING) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "ADMIN_CANCEL_FROM_ESCROW");
        }
        Escrow escrow = escrowRepo.findByAuctionId(a.getId())
                .orElseThrow(() -> new IllegalStateException(
                    "Auction " + a.getId() + " in TRANSFER_PENDING has no escrow row"));
        if (escrow.getState() != EscrowState.TRANSFER_PENDING) {
            throw new IllegalStateException(
                "Escrow " + escrow.getId() + " not in TRANSFER_PENDING (actual=" + escrow.getState() + ")");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);

        // Refund winner. queueRefundIfFunded is idempotent on fundedAt; for
        // wallet-only-escrow rows fundedAt is always set, so the refund fires.
        escrowService.queueRefundIfFunded(escrow);

        // Escrow -> EXPIRED with reason ADMIN_CANCEL.
        EscrowService.enforceTransitionAllowed(escrow.getId(), escrow.getState(), EscrowState.EXPIRED);
        escrow.setState(EscrowState.EXPIRED);
        escrow.setExpiredAt(now);
        escrow.setFreezeReason(FreezeReason.ADMIN_CANCEL.name());
        escrowRepo.save(escrow);

        // Audit trail: cancellation_logs row with cancelledByAdminId set so the
        // penalty-ladder counters don't bill the seller.
        logRepo.save(CancellationLog.builder()
                .auction(a)
                .seller(a.getSeller())
                .cancelledFromStatus(AuctionStatus.TRANSFER_PENDING.name())
                .hadBids(true)
                .reason(notes)
                .penaltyKind(CancellationOffenseKind.NONE)
                .penaltyAmountL(null)
                .cancelledByAdminId(adminUserId)
                .build());

        a.setStatus(AuctionStatus.CANCELLED);
        Auction saved = auctionRepo.save(a);

        notificationPublisher.listingRemovedByAdmin(
                a.getSeller().getId(), a.getId(), a.getTitle(), notes);
        notificationPublisher.listingCancelledDuringEscrow(
                a.getWinnerUserId(),
                a.getId(), escrow.getId(), a.getTitle(), notes);

        final boolean hadBids = true;
        AuctionCancelledEnvelope envelope = AuctionCancelledEnvelope.of(saved, hadBids, now);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override public void afterCommit() {
                            broadcastPublisher.publishCancelled(envelope);
                        }
                    });
        } else {
            broadcastPublisher.publishCancelled(envelope);
        }

        log.info("Auction {} admin-cancelled from TRANSFER_PENDING by adminUserId={} (escrow {} refunded, marked EXPIRED/ADMIN_CANCEL)",
                a.getId(), adminUserId, escrow.getId());

        return saved;
    }

    /**
     * Sub-project E §11.4 -- broker-initiated cancellation of a group-sale
     * (SL-group-owned) listing. The acting broker must hold
     * {@link RealtyGroupPermission#MANAGE_ALL_LISTINGS} on the owning realty
     * group. Skips the seller penalty ladder entirely: a broker acting on
     * behalf of a group is not a seller offense, and the
     * {@code countPriorOffensesWithBids} query excludes
     * {@link CancellationOffenseKind#BROKER_CANCEL} rows belt-and-braces
     * (alongside the {@code cancelledByAdminId} predicate).
     *
     * <p>Only group sales are eligible. Individual listings and legacy
     * auctions with no realty group attached raise
     * {@link BrokerCancelNotApplicableException} so group sales stay the only
     * surface where broker authority overrides seller agency.
     *
     * <p>Listing-fee refund is created in every state when {@code listingFeePaid}
     * is true -- including {@code ACTIVE}. This differs from the seller path
     * (which refunds only on pre-active cancels) because group-sale listing
     * fees are paid out of the group wallet at create-time; the group must
     * be made whole even on an active-state cancel. Existing
     * {@code ListingFeeRefundProcessorJob} routes the refund back to the
     * originating wallet via the ledger row, so group-sale refunds land in
     * the group wallet without a routing flag here.
     */
    @Transactional
    public Auction brokerCancel(Long brokerUserId, Long auctionId, String reason, String ipAddress) {
        Auction a = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (!CANCELLABLE.contains(a.getStatus())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "BROKER_CANCEL");
        }
        if (a.getStatus() == AuctionStatus.ACTIVE
                && a.getEndsAt() != null
                && OffsetDateTime.now(clock).isAfter(a.getEndsAt())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "BROKER_CANCEL_AFTER_END");
        }
        if (a.getRealtyGroupSlGroupId() == null) {
            throw new BrokerCancelNotApplicableException(a.getPublicId(),
                    "Broker-cancel only applies to group sales (SL-group-owned listings).");
        }
        Long groupId = a.getRealtyGroupId();
        if (groupId == null) {
            // Defensive: group-sale auctions must always carry both realty_group_id
            // and realty_group_sl_group_id. If the latter is set but the former
            // is null the row is malformed; fail fast rather than silently
            // skipping the authorization check.
            throw new BrokerCancelNotApplicableException(a.getPublicId(),
                    "Group-sale auction missing realty_group_id; cannot authorize broker.");
        }

        realtyGroupAuthorizer.assertCan(brokerUserId, groupId,
                RealtyGroupPermission.MANAGE_ALL_LISTINGS);

        AuctionStatus from = a.getStatus();
        boolean hadBids = a.getBidCount() != null && a.getBidCount() > 0;

        // Skip the seller penalty ladder. No seller-row lock, no ban check on
        // the seller -- the broker is the actor, not the seller. Snapshot the
        // log row with kind=BROKER_CANCEL, actor_user_id=broker, realty_group_id=group.
        logRepo.save(CancellationLog.builder()
                .auction(a)
                .seller(a.getSeller())
                .cancelledFromStatus(from.name())
                .hadBids(hadBids)
                .reason(reason)
                .penaltyKind(CancellationOffenseKind.BROKER_CANCEL)
                .penaltyAmountL(null)
                .actorUserId(brokerUserId)
                .realtyGroupId(groupId)
                .build());

        // Release any active wallet reservations for this auction before
        // flipping status. Centralised across every cancel path so bidders
        // never see a stale "held" L$ row after the listing is gone.
        walletService.releaseReservationsForAuction(a.getId(),
                BidReservationReleaseReason.AUCTION_CANCELLED);

        a.setStatus(AuctionStatus.CANCELLED);
        Auction saved = auctionRepo.save(a);

        // Listing-fee refund: D's existing ListingFeeRefundProcessorJob routes
        // by originating ledger row, so group-sale refunds credit back to the
        // group wallet without explicit routing here. Issued regardless of
        // from-status because the group paid the fee -- they must be made whole.
        if (Boolean.TRUE.equals(a.getListingFeePaid())) {
            refundRepo.save(ListingFeeRefund.builder()
                    .auction(saved)
                    .amount(a.getListingFeeAmt() == null ? 0L : a.getListingFeeAmt())
                    .status(RefundStatus.PENDING)
                    .build());
            log.info("Listing fee refund (PENDING) created for group-sale auction {} broker-cancel", a.getId());
        }

        // Notify the original listing agent -- the commission recipient, which
        // can differ from the current seller_id over time. Notifies the agent
        // even when they are the broker themselves; the stub is log-only.
        User listingAgent = saved.getListingAgent() != null ? saved.getListingAgent() : saved.getSeller();
        notificationPublisher.brokerCancelled(
                listingAgent.getId(), saved.getId(), saved.getTitle(), brokerUserId, reason);

        // Bidder fan-out: group sales can have live bids same as any other
        // ACTIVE auction. Mirror the seller-cancel fan-out so bidders see the
        // cancellation in their feed.
        if (hadBids) {
            List<Long> bidderIds = bidRepo.findDistinctBidderUserIdsByAuctionId(a.getId());
            notificationPublisher.listingCancelledBySellerFanout(
                    a.getId(), bidderIds, a.getTitle(), reason);
        }

        log.info("Auction {} broker-cancelled from {} by brokerUserId={} group={} (hadBids={})",
                a.getId(), from, brokerUserId, groupId, hadBids);

        AuctionCancelledEnvelope envelope = AuctionCancelledEnvelope.of(
                saved, hadBids, OffsetDateTime.now(clock));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            broadcastPublisher.publishCancelled(envelope);
                        }
                    });
        } else {
            broadcastPublisher.publishCancelled(envelope);
        }

        return saved;
    }

    /**
     * Dispute-resolution-initiated cancellation. Used by
     * {@code AdminDisputeService.resolve} when {@code alsoCancelListing} fires.
     * Skips the CANCELLABLE precondition entirely -- the auction may be in
     * DISPUTED (post-escrow) state, which {@link #cancelByAdmin} rejects.
     * The orchestrator is responsible for validating that the dispute is open
     * before calling this method; no re-validation is performed here.
     *
     * <p>Like {@link #cancelByAdmin}, no penalty ladder is applied and
     * {@code seller.cancelledWithBids} is NOT incremented. The
     * {@link CancellationLog} row records {@code cancelledByAdminId} so that
     * {@code countPriorOffensesWithBids} (which filters {@code IS NULL}) does
     * not count it against the seller.
     */
    @Transactional
    public Auction cancelByDisputeResolution(
            Long auctionId, Long adminUserId, String notes) {
        Auction a = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));

        // No CANCELLABLE precondition check -- this path is reached only via
        // AdminDisputeService.resolve when alsoCancelListing fires, which
        // already validates the dispute is open. Trust the orchestrator.

        boolean hadBids = a.getBidCount() != null && a.getBidCount() > 0;
        AuctionStatus from = a.getStatus();

        logRepo.save(CancellationLog.builder()
                .auction(a)
                .seller(a.getSeller())
                .cancelledFromStatus(from.name())
                .hadBids(hadBids)
                .reason(notes)
                .penaltyKind(CancellationOffenseKind.NONE)
                .penaltyAmountL(null)
                .cancelledByAdminId(adminUserId)
                .build());

        // Release any active wallet reservations for this auction before
        // flipping status. Centralised across every cancel path so bidders
        // never see a stale "held" L$ row after the listing is gone.
        walletService.releaseReservationsForAuction(a.getId(),
                BidReservationReleaseReason.AUCTION_CANCELLED);

        a.setStatus(AuctionStatus.CANCELLED);
        Auction saved = auctionRepo.save(a);

        notificationPublisher.listingRemovedByAdmin(
                a.getSeller().getId(), a.getId(), a.getTitle(), notes);

        if (hadBids) {
            List<Long> bidderIds = bidRepo.findDistinctBidderUserIdsByAuctionId(a.getId());
            notificationPublisher.listingCancelledBySellerFanout(
                    a.getId(), bidderIds, a.getTitle(), notes);
        }

        log.info("Auction {} cancelled via dispute-resolution from {} by adminUserId={} (hadBids={})",
                a.getId(), from, adminUserId, hadBids);

        AuctionCancelledEnvelope envelope = AuctionCancelledEnvelope.of(
                saved, hadBids, OffsetDateTime.now(clock));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            broadcastPublisher.publishCancelled(envelope);
                        }
                    });
        } else {
            broadcastPublisher.publishCancelled(envelope);
        }

        return saved;
    }

    /**
     * Sub-project F §10.2 -- per-row callback invoked by
     * {@code BulkSuspendedListingExpiryTask} for each {@code listing_suspensions}
     * row older than the configured bulk-suspend auto-cancel window. Cancels the
     * still-{@code SUSPENDED} listing administratively: no seller penalty, no
     * ladder bump, no ownership-watcher arm.
     *
     * <p>The method is idempotent: a second call observes {@code status !=
     * SUSPENDED} and returns without writing a log row or flipping the listing.
     * This matches the safety the expiry task needs: a re-run that races with
     * an admin manual reinstate (or another concurrent expiry tick) leaves the
     * auction in whichever state the first writer committed.
     *
     * <p>Bidder fan-out reuses {@link NotificationPublisher#listingCancelledBySellerFanout}
     * with a cause-neutral envelope (no admin attribution leaks to bidders); the
     * seller receives the F-specific
     * {@link NotificationPublisher#listingAutoCancelledFromBulkSuspend} helper
     * which carries the {@code BULK_SUSPEND_TIMER_EXPIRED} reason. The
     * {@link CancellationLog} row is stamped with
     * {@link CancellationOffenseKind#ADMIN_BULK_EXPIRED} -- excluded from
     * {@code countPriorOffensesWithBids} so the seller's penalty ladder never
     * advances.
     */
    @Transactional
    public void adminCancelExpiredBulkSuspend(Long auctionId, Long listingSuspensionId) {
        Auction a = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (a.getStatus() != AuctionStatus.SUSPENDED) {
            log.info("auction {} not SUSPENDED at expiry-cancel time (was {}); skipping",
                    auctionId, a.getStatus());
            return; // idempotent
        }

        AuctionStatus from = a.getStatus();
        boolean hadBids = a.getBidCount() != null && a.getBidCount() > 0;

        // Write the cancellation log row first. ADMIN_BULK_EXPIRED is excluded
        // from countPriorOffensesWithBids so the seller's ladder never advances.
        // No penalty ladder; no seller-row lock; no cancelledWithBids bump.
        logRepo.save(CancellationLog.builder()
                .auction(a)
                .seller(a.getSeller())
                .cancelledFromStatus(from.name())
                .hadBids(hadBids)
                .reason("BULK_SUSPEND_TIMER_EXPIRED")
                .penaltyKind(CancellationOffenseKind.ADMIN_BULK_EXPIRED)
                .penaltyAmountL(null)
                .build());

        // Release any active wallet reservations for this auction before
        // flipping status. Centralised across every cancel path so bidders
        // never see a stale "held" L$ row after the listing is gone.
        walletService.releaseReservationsForAuction(a.getId(),
                BidReservationReleaseReason.AUCTION_CANCELLED);

        a.setStatus(AuctionStatus.CANCELLED);
        Auction saved = auctionRepo.save(a);

        // Stamp the listing_suspensions row so the expiry sweep does not retry
        // it on the next tick and so audit-trail reconciliation reports show
        // the row resolved via auto-cancel rather than admin reinstate.
        ListingSuspension ls = listingSuspensionRepo.findById(listingSuspensionId).orElseThrow();
        ls.setCancelledAt(OffsetDateTime.now(clock));

        // Bidder fan-out -- cause-neutral copy per FOOTGUNS §F.104. Bidders
        // never see admin attribution. Empty list is a no-op; stale bidder ids
        // log a contained warning per the publisher's contract. A non-null
        // cause-neutral reason avoids the publisher's body interpolation
        // rendering the literal string "null" at the end of the bidder body.
        List<Long> bidderIds = bidRepo.findDistinctBidderUserIdsByAuctionId(a.getId());
        notificationPublisher.listingCancelledBySellerFanout(
                a.getId(), bidderIds, a.getTitle(),
                "Suspended too long without admin action");

        // Seller-facing notification with the specific BULK_SUSPEND_TIMER_EXPIRED reason.
        notificationPublisher.listingAutoCancelledFromBulkSuspend(
                a.getSeller().getId(), a.getId(), a.getTitle());

        log.info("Auction {} auto-cancelled from {} via bulk-suspend timer expiry (hadBids={})",
                a.getId(), from, hadBids);

        AuctionCancelledEnvelope envelope = AuctionCancelledEnvelope.of(
                saved, hadBids, OffsetDateTime.now(clock));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            broadcastPublisher.publishCancelled(envelope);
                        }
                    });
        } else {
            broadcastPublisher.publishCancelled(envelope);
        }
    }
}
