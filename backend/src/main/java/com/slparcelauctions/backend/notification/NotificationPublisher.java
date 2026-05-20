package com.slparcelauctions.backend.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupInvitation;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;

public interface NotificationPublisher {

    // Bidding
    void outbid(long bidderUserId, long auctionId, String parcelName,
                long currentBidL, boolean isProxyOutbid, OffsetDateTime endsAt);
    void proxyExhausted(long bidderUserId, long auctionId, String parcelName,
                        long proxyMaxL, OffsetDateTime endsAt);

    // Auction result -- winner/loser
    void auctionWon(long winnerUserId, long auctionId, String parcelName, long winningBidL);
    void auctionLost(long bidderUserId, long auctionId, String parcelName, long winningBidL);

    // Auction result -- seller's perspective
    void auctionEndedSold(long sellerUserId, long auctionId, String parcelName, long winningBidL);
    void auctionEndedReserveNotMet(long sellerUserId, long auctionId, String parcelName, long highestBidL);
    void auctionEndedNoBids(long sellerUserId, long auctionId, String parcelName);
    void auctionEndedBoughtNow(long sellerUserId, long auctionId, String parcelName, long buyNowL);

    // Escrow lifecycle
    /**
     * Seller-facing notification fired when the winner's escrow is funded.
     * Body carries the Set-Sell-To recipe summary and the winner's SL
     * avatar name ({@code winnerSlAvatarName}, may be {@code null} if the
     * winner has no SL name resolved) so the seller can complete the
     * in-world "Sell To" step without leaving the message. SLURL is
     * injected centrally for every escrow category.
     */
    void escrowFunded(long sellerUserId, long auctionId, long escrowId,
                      String parcelName, OffsetDateTime transferDeadline,
                      String winnerSlAvatarName);

    /**
     * Buyer-facing notification fired when the bot (or an admin force-confirm)
     * stamps {@code sellToConfirmedAt} on the escrow. Tells the winning buyer
     * the seller has set the parcel "Sell To" them and to purchase it now,
     * but only if the in-world price is L$0. The data blob carries the parcel
     * SLURL (injected centrally for every escrow category) so the SL IM body
     * and in-app row both link straight to the parcel.
     */
    void escrowSellToSet(long buyerUserId, long auctionId, long escrowId, String parcelName);

    void escrowTransferConfirmed(long userId, long auctionId, long escrowId, String parcelName);

    /**
     * Seller-facing payout-completed notification.
     *
     * <p>Sub-project G section 8.3: copy differs by sale type. For group sales
     * (SL-group-owned; {@code groupName != null}) the body surfaces the
     * commission slice and group slice instead of "L$0 payout received". For
     * individual sales the body says the L$ was credited to the seller's
     * SLParcels wallet (post wallet-first cutover, sale conclusion no longer
     * dispatches L$ to the seller's avatar; the seller withdraws separately).
     * Subject ("Auction payout processed") is identical for both sale types.
     *
     * @param payoutL           L$ paid to the seller (0 for group sales)
     * @param groupName         realty group display name, or {@code null} for
     *                          individual sales
     * @param commissionAmt     L$ credited to the listing agent's wallet
     *                          (group sales only; ignored when
     *                          {@code groupName == null})
     * @param groupSliceAmt     L$ credited to the group wallet (group sales
     *                          only; ignored when {@code groupName == null})
     */
    void escrowPayout(long sellerUserId, long auctionId, long escrowId,
                      String parcelName, long payoutL,
                      String groupName, long commissionAmt, long groupSliceAmt);

    /**
     * Backwards-compatible overload for individual-sale callers. Delegates to
     * the group-sale-aware variant with {@code groupName=null} so the body
     * composes the individual-sale "credited to your SLParcels wallet" copy.
     */
    default void escrowPayout(long sellerUserId, long auctionId, long escrowId,
                              String parcelName, long payoutL) {
        escrowPayout(sellerUserId, auctionId, escrowId, parcelName, payoutL,
                /* groupName */ null, /* commissionAmt */ 0L, /* groupSliceAmt */ 0L);
    }

    void escrowExpired(long userId, long auctionId, long escrowId, String parcelName);
    void escrowDisputed(long userId, long auctionId, long escrowId,
                        String parcelName, String reasonCategory);
    void escrowFrozen(long userId, long auctionId, long escrowId,
                      String parcelName, String reason);
    void escrowPayoutStalled(long sellerUserId, long auctionId, long escrowId, String parcelName);
    void escrowTransferReminder(long sellerUserId, long auctionId, long escrowId,
                                 String parcelName, OffsetDateTime transferDeadline);

    // Listing status -- seller-facing
    void listingVerified(long sellerUserId, long auctionId, String parcelName);
    void listingSuspended(long sellerUserId, long auctionId, String parcelName, String reason);
    void listingReinstated(long sellerUserId, long auctionId, String parcelName, OffsetDateTime newEndsAt);
    void listingReviewRequired(long sellerUserId, long auctionId, String parcelName, String reason);

    // Listing status -- admin-facing actions
    void listingRemovedByAdmin(long sellerUserId, long auctionId, String parcelName, String reason);
    void listingWarned(long sellerUserId, long auctionId, String parcelName, String notes);

    /**
     * Winner-facing notification fired when an admin cancels a listing whose
     * escrow is in TRANSFER_PENDING. The winner's escrow has already been
     * refunded to their SLParcels wallet by
     * {@code CancellationService.cancelByAdminFromEscrow} before this method
     * runs.
     */
    void listingCancelledDuringEscrow(long winnerUserId, long auctionId, long escrowId,
                                       String parcelName, String adminNote);

    /**
     * Sub-project E section 11.5 -- the broker cancelled the listing on behalf of the
     * realty group. Notifies the original listing agent (commission recipient,
     * may differ from current seller_id). Body copy + fan-out will be fleshed
     * out in E section 11 follow-on tasks; the Phase 4 stub logs only.
     */
    void brokerCancelled(Long listingAgentUserId, Long auctionId, String auctionTitle,
                         Long brokerUserId, String reason);

    // Reviews
    void reviewReceived(long revieweeUserId, long reviewId, long auctionId,
                        String parcelName, int rating);
    void reviewResponseWindowClosing(long revieweeUserId, long reviewId, long auctionId,
                                      String parcelName, OffsetDateTime responseDeadline);

    // Dispute
    void disputeFiledAgainstSeller(long sellerUserId, long auctionId, long escrowId,
                                    String parcelName, long amountL,
                                    String reasonCategory);

    // Dispute resolution
    void disputeResolved(long recipientUserId, String role,
                          long auctionId, long escrowId,
                          String parcelName, long amountL,
                          com.slparcelauctions.backend.admin.disputes.AdminDisputeAction action,
                          boolean alsoCancelListing);

    // Fan-out (afterCommit batch -- see section 3.9)
    void listingCancelledBySellerFanout(long auctionId, List<Long> activeBidderUserIds,
                                         String parcelName, String reason);

    /**
     * Sub-project F section 10.2 -- seller-facing notification fired when the
     * bulk-suspend auto-cancel timer (default 48 h) expires without admin
     * reinstatement and the parent listing is auto-cancelled. Carries the
     * specific {@code BULK_SUSPEND_TIMER_EXPIRED} reason; bidders receive the
     * cause-neutral {@link #listingCancelledBySellerFanout} envelope instead so
     * admin attribution does not leak.
     */
    void listingAutoCancelledFromBulkSuspend(long sellerUserId, long auctionId, String parcelName);

    // Admin infrastructure
    void reconciliationMismatch(List<Long> adminUserIds, long drift, String date);

    // Admin withdrawals
    void withdrawalCompleted(long adminUserId, long amountL, String recipientUuid);
    void withdrawalFailed(long adminUserId, long amountL, String recipientUuid, String reason);

    // User wallet withdrawals (terminal-fulfilled)
    void walletWithdrawalCompleted(long userId, long amountL, Long ledgerEntryId);
    void walletWithdrawalReversed(long userId, long amountL, Long ledgerEntryId, String reason);

    // Admin wallet ops -- material decisions about a user's funds. Always-on (SYSTEM group).
    void walletAdjusted(long userId, long deltaL, String notes);
    void walletFrozen(long userId, String notes);
    void walletUnfrozen(long userId, String notes);
    void walletPenaltyForgiven(long userId, long amountL, String notes);
    void walletDormancyReset(long userId, String notes);
    void walletTermsCleared(long userId, String notes);
    void walletWithdrawalForceCompleted(long userId, long amountL, Long ledgerEntryId, String notes);
    void walletWithdrawalForceFailed(long userId, long amountL, Long ledgerEntryId, String notes);

    // -- Realty groups -- group wallet notifications (stub; Epic 09 dispatcher fanout wired later).
    void groupWalletWithdrawalCompleted(Long groupId, long amount, Long ledgerId);
    void groupWalletWithdrawalReversed(Long groupId, long amount, Long ledgerId, String reason);

    // -- Realty groups -- dormancy notifications (stub; Epic 09 will add SL IM body).
    void groupWalletDormancyFlagged(Long groupId, int phase, long balance);
    void groupWalletDormancyAutoReturned(Long groupId, long amount);

    // -- Realty groups -- lifecycle events (Phase 6 fleshes out fan-out + body copy).
    // Stubs land here so Phase 4 services can call them while compiling.
    void realtyGroupInvitationSent(RealtyGroupInvitation invitation);
    void realtyGroupInvitationAccepted(RealtyGroupInvitation invitation);
    void realtyGroupInvitationDeclined(RealtyGroupInvitation invitation);
    void realtyGroupInvitationExpired(RealtyGroupInvitation invitation);
    void realtyGroupMemberRemoved(RealtyGroup group, User removedUser);
    void realtyGroupMemberLeft(RealtyGroup group, User leftUser);
    void realtyGroupLeadershipTransferred(RealtyGroup group, User oldLeader, User newLeader, boolean oldLeaderStayed);
    void realtyGroupDissolved(RealtyGroup group, List<User> formerMembers);
    void realtyGroupPermissionsChanged(RealtyGroup group, RealtyGroupMember member,
                                       Set<RealtyGroupPermission> added,
                                       Set<RealtyGroupPermission> removed);

    // -- Realty groups -- admin moderation (sub-project F section 8, section 9). Phase 4 stubs
    // route through the existing in-app notification mechanism; Task 28 will
    // refine the body copy and add SL IM integration.

    /**
     * Notify every current member of {@code group} that an admin has issued a
     * suspension (or permanent ban when {@code expiresAt} is null). {@code reason}
     * is the audit-facing {@code SuspensionReason}; copy is generated from it.
     */
    void realtyGroupSuspended(RealtyGroup group, String reason,
                              java.time.OffsetDateTime expiresAt);

    /**
     * Notify every current member of {@code group} that an admin (or the expiry
     * sweep) has lifted an active suspension.
     */
    void realtyGroupUnsuspended(RealtyGroup group);

    /**
     * Sub-project G section 12 -- fan-out to every admin when a realty group's
     * open-report count crosses {@code slpa.reports.group-alert-threshold} for the
     * first time in the current cycle. One-shot per cycle: the caller
     * ({@link com.slparcelauctions.backend.realty.reports.RealtyGroupReportService})
     * gates by checking {@code group.isReportsThresholdNotified()} and sets the
     * flag before invoking this method. {@code threshold} is the value that
     * actually fired so the body copy doesn't drift if config is changed
     * between submissions.
     */
    void groupReportThresholdReached(RealtyGroup group, int threshold);

    /**
     * Sub-project F section 13.2 -- the periodic reverify task detected that an SL
     * group registration has drifted from its claimed state on the SL side.
     * Routes to {@code leaderUserId} (the realty group's current leader) so
     * they can re-register or contact admin. Body copy + SL IM channel
     * dispatch are refined in Task 28; the Phase 4 wiring routes through the
     * existing in-app notification publish primitive.
     *
     * @param leaderUserId  recipient: the realty group's leader
     * @param groupId       internal id of the realty group (used for log /
     *                      audit context; the data blob carries the public id
     *                      for the frontend)
     * @param slGroupName   display name of the SL group that drifted (may be
     *                      {@code null} if it was never parsed onto the row)
     * @param driftReason   {@link com.slparcelauctions.backend.realty.slgroup
     *                       .SlGroupDriftReason} name -- one of
     *                       {@code FOUNDER_CHANGED}, {@code GROUP_NOT_FOUND},
     *                       {@code FETCH_FAILED_REPEATEDLY}
     */
    void realtyGroupSlGroupDriftDetected(long leaderUserId, long groupId,
                                         String slGroupName, String driftReason);
}
