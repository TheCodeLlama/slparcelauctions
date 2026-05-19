package com.slparcelauctions.backend.realty.wallet;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspensionRepository;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWithdrawRecipient;
import com.slparcelauctions.backend.realty.wallet.exception.DepositAmountOutOfRangeException;
import com.slparcelauctions.backend.realty.wallet.exception.SlGroupNotRegisteredException;
import com.slparcelauctions.backend.realty.wallet.exception.SlGroupRegistrationSuspendedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;
import com.slparcelauctions.backend.wallet.broadcast.WalletBroadcastPublisher;
import com.slparcelauctions.backend.wallet.exception.InsufficientAvailableBalanceException;
import com.slparcelauctions.backend.wallet.exception.UserStatusBlockedException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core realty-group wallet operations. All balance-mutating methods run with
 * {@link Propagation#MANDATORY} — callers must have already begun a transaction
 * and hold the relevant locks.
 *
 * <p>See spec docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtyGroupWalletService {

    private final RealtyGroupRepository groupRepository;
    private final RealtyGroupLedgerRepository ledgerRepository;
    private final UserRepository userRepository;
    private final TerminalCommandRepository terminalCommandRepository;
    private final NotificationPublisher notificationPublisher;
    private final GroupWalletBroadcastPublisher broadcastPublisher;
    private final RealtyGroupGuard realtyGroupGuard;
    private final RealtyGroupSlGroupRepository slGroupRepository;
    private final RealtyGroupSuspensionRepository suspensionRepository;
    private final UserLedgerRepository userLedgerRepository;
    private final WalletBroadcastPublisher walletBroadcastPublisher;
    private final Clock clock;

    /**
     * Maximum L$ permitted on a single member-deposit call (app or in-world).
     * Fat-finger guard only; the depositor's personal balance and walking-
     * around SL L$ are the natural ceilings. Configurable per-environment.
     */
    @Value("${slpa.realty.group-deposit-max-l:500000}")
    private long groupDepositMaxL;

    /* ============================================================ */
    /* AGENT FEE CREDIT (legacy pre-G non-group-owned listing path)  */
    /* ============================================================ */

    /**
     * Credit the group wallet with its share of agent_fee_amt. Spec §7.2.
     *
     * <p>The pre-G distributor that drove this method (for non-SL-group-owned
     * group listings) was deleted by sub-project G. The method and the
     * {@code AGENT_FEE_CREDIT} ledger entry type remain for backwards
     * compatibility with historical ledger rows.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void creditAgentFee(Long groupId, Long auctionId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        long newBalance = group.getBalanceLindens() + amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(groupId)
            .entryType(RealtyGroupLedgerEntryType.AGENT_FEE_CREDIT)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .refType("AUCTION")
            .refId(auctionId)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.AGENT_FEE_CREDIT.name(), entry.getPublicId());

        log.info("group agent fee credit: groupId={}, auctionId={}, amount={}, balanceAfter={}",
            groupId, auctionId, amount, newBalance);
    }

    /* ============================================================ */
    /* GROUP-SALE LISTING PAYOUT (called from AgentCommissionDistributor) */
    /* ============================================================ */

    /**
     * Credit the group wallet with its group-sale share of earnings (after
     * platform commission and the listing agent's commission slice). Called
     * from {@code AgentCommissionDistributor} inside the escrow-payout-success
     * transaction for group sales (SL-group-owned auctions). Spec §9.6.
     *
     * <p>Idempotency key {@code LP-{auctionId}} prevents a duplicate credit if
     * the payout callback ever fires twice for the same auction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void creditPayout(Long realtyGroupId, Long auctionId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        String idempotencyKey = "LP-" + auctionId;
        Optional<RealtyGroupLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.debug("group listing payout replay for auctionId={} (existing entry id={})",
                auctionId, existing.get().getId());
            return;
        }
        RealtyGroup group = groupRepository.findByIdForUpdate(realtyGroupId).orElseThrow();
        long newBalance = group.getBalanceLindens() + amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(realtyGroupId)
            .entryType(RealtyGroupLedgerEntryType.LISTING_PAYOUT)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .refType("AUCTION")
            .refId(auctionId)
            .idempotencyKey(idempotencyKey)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.LISTING_PAYOUT.name(), entry.getPublicId());

        log.info("group listing payout credit: groupId={}, auctionId={}, amount={}, balanceAfter={}",
            realtyGroupId, auctionId, amount, newBalance);
    }

    /* ============================================================ */
    /* LISTING FEE DEBIT                                             */
    /* ============================================================ */

    /**
     * Debit a group-listed auction's listing fee from the group wallet.
     * Called from MeWalletController.payListingFee branching on
     * auction.realty_group_id != null. Spec §5.4.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void debitListingFee(Long groupId, Long auctionId, long amount, Long actorUserId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        long available = group.availableLindens();
        if (available < amount) {
            throw new com.slparcelauctions.backend.realty.wallet.exception
                .InsufficientGroupBalanceException(available, amount);
        }
        long newBalance = group.getBalanceLindens() - amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(groupId)
            .entryType(RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .refType("AUCTION")
            .refId(auctionId)
            .actorUserId(actorUserId)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT.name(), entry.getPublicId());
        log.info("group listing fee debit: groupId={}, auctionId={}, amount={}, balanceAfter={}, actor={}",
            groupId, auctionId, amount, newBalance, actorUserId);
    }

    /* ============================================================ */
    /* LISTING FEE REFUND CREDIT                                     */
    /* ============================================================ */

    /**
     * Credit the group wallet with a listing-fee refund.
     * Called from {@code ListingFeeRefundProcessorTask} when a
     * {@code realty_group_ledger.LISTING_FEE_DEBIT} row exists for the auction
     * (i.e. the original fee came from the group wallet). Spec §8.3.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void creditListingFeeRefund(Long groupId, Long auctionId, long amount, Long refundRowId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        if (group.getDissolvedAt() != null) {
            throw new IllegalStateException(
                "cannot credit dissolved group " + group.getPublicId()
                + " for listing-fee refund row " + refundRowId);
        }
        long newBalance = group.getBalanceLindens() + amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(groupId)
            .entryType(RealtyGroupLedgerEntryType.LISTING_FEE_REFUND)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .refType("LISTING_FEE_REFUND")
            .refId(refundRowId)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.LISTING_FEE_REFUND.name(), entry.getPublicId());
        log.info("group listing fee refund credit: groupId={}, refundId={}, amount={}, balanceAfter={}",
            groupId, refundRowId, amount, newBalance);
    }

    /* ============================================================ */
    /* MEMBER DEPOSIT (app flow -- personal wallet -> group wallet)  */
    /* ============================================================ */

    /**
     * Result returned by {@link #depositFromMemberWallet}.
     *
     * @param groupLedgerEntryId    internal id of the appended group-ledger
     *                              {@code MEMBER_DEPOSIT} row
     * @param personalLedgerEntryId internal id of the paired personal-wallet
     *                              {@code GROUP_WALLET_DEPOSIT_DEBIT} row
     * @param newGroupAvailable     group-wallet available balance after the
     *                              transfer (current snapshot on replay)
     * @param newPersonalAvailable  depositor's personal-wallet available
     *                              balance after the transfer (current
     *                              snapshot on replay)
     */
    public record DepositResult(
            Long groupLedgerEntryId,
            Long personalLedgerEntryId,
            long newGroupAvailable,
            long newPersonalAvailable) {}

    /**
     * Atomic personal-wallet -> group-wallet transfer initiated by a member
     * with {@code DEPOSIT_TO_GROUP_WALLET}. Spec §4.1.
     *
     * <p>On success, appends two ledger rows sharing the same
     * {@code idempotencyKey}: a {@code GROUP_WALLET_DEPOSIT_DEBIT} on the
     * depositor's personal wallet and a {@code MEMBER_DEPOSIT} on the
     * group wallet. Both balances are mutated inside a single transaction.
     *
     * <p>Idempotent: a duplicate {@code idempotencyKey} returns the original
     * ledger IDs and current availabilities without re-debiting / re-crediting.
     *
     * @param groupId        internal id of the target realty group
     * @param amount         L$ to transfer; must lie in {@code [1, configMax]}
     * @param userId         internal id of the depositing member
     * @param memo           optional user-supplied memo (max 200 chars,
     *                       trimmed elsewhere); appended to the row's
     *                       {@code description} with a " -- " separator
     * @param idempotencyKey caller-supplied UUID; deduplicates retries
     * @throws DepositAmountOutOfRangeException  amount {@code <= 0} or
     *                                           {@code >} configured max
     * @throws InsufficientAvailableBalanceException  depositor's balance
     *                                                {@code <} amount
     * @throws UserStatusBlockedException        depositor's wallet is frozen
     */
    @Transactional
    public DepositResult depositFromMemberWallet(
            Long groupId, long amount, Long userId, String memo, UUID idempotencyKey) {
        // 1. Range check.
        if (amount < 1 || amount > groupDepositMaxL) {
            throw new DepositAmountOutOfRangeException(1L, groupDepositMaxL, amount);
        }

        // 2. Idempotency check -- replay returns the original IDs + current
        // availabilities without touching balances.
        String idemStr = idempotencyKey.toString();
        Optional<RealtyGroupLedgerEntry> existingGroup =
                ledgerRepository.findByIdempotencyKey(idemStr);
        if (existingGroup.isPresent()) {
            RealtyGroupLedgerEntry priorGroup = existingGroup.get();
            UserLedgerEntry priorUser = userLedgerRepository.findByIdempotencyKey(idemStr)
                .orElseThrow(() -> new IllegalStateException(
                    "group ledger row " + priorGroup.getId()
                    + " exists for idempotency key " + idemStr
                    + " but paired user-ledger row is missing"));
            RealtyGroup curGroup = groupRepository.findById(groupId).orElseThrow();
            User curUser = userRepository.findById(userId).orElseThrow();
            log.debug("group deposit replay: groupId={}, userId={}, idemKey={}",
                groupId, userId, idemStr);
            return new DepositResult(
                priorGroup.getId(),
                priorUser.getId(),
                curGroup.availableLindens(),
                curUser.availableLindens());
        }

        // 3. Suspension / ban gate.
        realtyGroupGuard.requireGroupCanOperate(groupId);

        // 4. Lock both rows.
        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        User user = userRepository.findByIdForUpdate(userId).orElseThrow();

        // 5. Reject wallet-frozen depositor.
        if (user.getWalletFrozenAt() != null) {
            throw new UserStatusBlockedException(user.getId(), "USER_FROZEN");
        }

        // 6. Insufficient-balance gate. Pay from AVAILABLE (balance - reserved),
        // not from total balance: reserved L$ is earmarked for active bids and
        // must remain backed by balance after the debit, or the DB's
        // `balance_lindens >= reserved_lindens` CHECK is violated at COMMIT.
        long available = user.availableLindens();
        if (available < amount) {
            throw new InsufficientAvailableBalanceException(available, amount);
        }

        // 7. Debit personal wallet + append user-ledger row.
        long newUserBalance = user.getBalanceLindens() - amount;
        user.setBalanceLindens(newUserBalance);
        userRepository.save(user);

        String memoSuffix = (memo == null || memo.isBlank()) ? "" : " -- " + memo.trim();
        UserLedgerEntry userEntry = userLedgerRepository.save(UserLedgerEntry.builder()
            .userId(user.getId())
            .entryType(UserLedgerEntryType.GROUP_WALLET_DEPOSIT_DEBIT)
            .amount(amount)
            .balanceAfter(newUserBalance)
            .reservedAfter(user.getReservedLindens())
            .idempotencyKey(idemStr)
            .refType("REALTY_GROUP")
            .refId(group.getId())
            .description("Deposit to " + group.getName() + memoSuffix)
            .build());

        // 8. Credit group wallet + append group-ledger row.
        long newGroupBalance = group.getBalanceLindens() + amount;
        group.setBalanceLindens(newGroupBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry groupEntry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(group.getId())
            .entryType(RealtyGroupLedgerEntryType.MEMBER_DEPOSIT)
            .amount(amount)
            .balanceAfter(newGroupBalance)
            .reservedAfter(group.getReservedLindens())
            .actorUserId(userId)
            .idempotencyKey(idemStr)
            .description("Deposit from app wallet" + memoSuffix)
            .build());

        // 9. Broadcasts -- group topic + depositor's personal queue.
        broadcastPublisher.publish(group.getPublicId(),
            newGroupBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.MEMBER_DEPOSIT.name(), groupEntry.getPublicId());
        walletBroadcastPublisher.publish(user,
            UserLedgerEntryType.GROUP_WALLET_DEPOSIT_DEBIT.name(), userEntry.getPublicId());

        log.info("group member deposit: groupId={}, userId={}, amount={}, "
                + "groupBalanceAfter={}, userBalanceAfter={}",
            groupId, userId, amount, newGroupBalance, newUserBalance);

        // 10. Return both ids + current availabilities.
        return new DepositResult(
            groupEntry.getId(),
            userEntry.getId(),
            group.availableLindens(),
            user.availableLindens());
    }

    /* ============================================================ */
    /* TERMINAL DEPOSIT (in-world flow -- L$ -> group wallet)        */
    /* ============================================================ */

    /**
     * Result returned by {@link #depositFromTerminal}.
     *
     * @param groupLedgerEntryId internal id of the appended group-ledger
     *                           {@code MEMBER_DEPOSIT} row
     * @param newGroupAvailable  group-wallet available balance after the
     *                           credit (current snapshot on replay)
     */
    public record TerminalDepositResult(Long groupLedgerEntryId, long newGroupAvailable) {}

    /**
     * Credit a group wallet from L$ already paid into the in-world SLParcels
     * Terminal script. Called from the SL-headers + shared-secret gated
     * {@code POST /api/v1/sl/wallet/group-deposit} after a {@code money()}
     * event fires with a pending group-deposit context slot. Spec §4.3.
     *
     * <p>Reuses the {@code MEMBER_DEPOSIT} ledger entry type — same business
     * semantics as the app-flow deposit, just a different intake channel.
     *
     * <p>Idempotent on {@code slTransactionKey}: a replay returns the original
     * ledger id and current available balance without re-crediting. The LSL
     * retry chain relies on this — a network blip mid-response must not
     * double-credit the group.
     *
     * @param groupId          internal id of the target realty group
     * @param amount           L$ to credit; must lie in {@code [1, configMax]}
     * @param depositorUserId  internal id of the avatar's linked SLParcels
     *                         user (recorded as {@code actorUserId})
     * @param regionName       SL region name the terminal sits in; folded
     *                         into the row's {@code description}. May be
     *                         {@code null} if the terminal has no region row
     * @param slTransactionKey SL-grid transaction key; doubles as both the
     *                         idempotency key and the recorded
     *                         {@code slTransactionId}
     * @throws DepositAmountOutOfRangeException  amount {@code < 1} or
     *                                           {@code >} configured max
     * @throws com.slparcelauctions.backend.realty.moderation.exception.RealtyGroupSuspendedException
     *                                           group is suspended or banned
     */
    @Transactional
    public TerminalDepositResult depositFromTerminal(
            Long groupId, long amount, Long depositorUserId, String regionName,
            String slTransactionKey) {
        // 1. Range check.
        if (amount < 1 || amount > groupDepositMaxL) {
            throw new DepositAmountOutOfRangeException(1L, groupDepositMaxL, amount);
        }

        // 2. Idempotency on slTransactionKey -- replay returns the original
        // ledger id + current available without touching the balance.
        Optional<RealtyGroupLedgerEntry> prior =
            ledgerRepository.findByIdempotencyKey(slTransactionKey);
        if (prior.isPresent()) {
            RealtyGroup curGroup = groupRepository.findById(groupId).orElseThrow();
            log.debug("group terminal deposit replay: groupId={}, slTxn={}",
                groupId, slTransactionKey);
            return new TerminalDepositResult(prior.get().getId(), curGroup.availableLindens());
        }

        // 3. Suspension / ban gate.
        realtyGroupGuard.requireGroupCanOperate(groupId);

        // 4. Lock + credit.
        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        long newBalance = group.getBalanceLindens() + amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        String description = "Deposit at terminal in "
            + (regionName == null ? "<unknown region>" : regionName);
        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(group.getId())
            .entryType(RealtyGroupLedgerEntryType.MEMBER_DEPOSIT)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .actorUserId(depositorUserId)
            .idempotencyKey(slTransactionKey)
            .description(description)
            .slTransactionId(slTransactionKey)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.MEMBER_DEPOSIT.name(), entry.getPublicId());

        log.info("group terminal deposit: groupId={}, depositorUserId={}, amount={}, slTxn={}",
            group.getId(), depositorUserId, amount, slTransactionKey);
        return new TerminalDepositResult(entry.getId(), group.availableLindens());
    }

    /* ============================================================ */
    /* WITHDRAW                                                      */
    /* ============================================================ */

    /**
     * Result returned by {@link #withdraw}. The caller (controller) uses
     * {@code queueId} as the handle for progress polling and
     * {@code estimatedFulfillmentSeconds} for UX copy.
     */
    public record WithdrawResult(Long queueId, int estimatedFulfillmentSeconds) {}

    /**
     * Initiate a group-wallet withdrawal. Caller is whoever holds
     * WITHDRAW_FROM_GROUP_WALLET (or the leader). Recipient is either the
     * group leader's verified SL avatar ({@link GroupWithdrawRecipient#AVATAR})
     * or the realty group's currently-registered SL group
     * ({@link GroupWithdrawRecipient#SL_GROUP}). Spec §5.3, §7.3.
     *
     * <p>Idempotent: a duplicate {@code idempotencyKey} returns the original
     * result without re-processing.
     */
    @Transactional
    public WithdrawResult withdraw(Long groupId, long amount, UUID idempotencyKey,
            Long callerUserId, GroupWithdrawRecipient recipient) {
        realtyGroupGuard.requireGroupCanOperate(groupId);
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (recipient == null) {
            throw new IllegalArgumentException("recipient must be non-null");
        }
        String idemStr = idempotencyKey.toString();
        Optional<RealtyGroupLedgerEntry> replay = ledgerRepository.findByIdempotencyKey(idemStr);
        if (replay.isPresent()) {
            // Idempotency hit — find the matching TerminalCommand and return the original queueId.
            TerminalCommand prior = terminalCommandRepository
                .findByIdempotencyKey("GWAL-" + replay.get().getId())
                .orElseThrow(() -> new IllegalStateException(
                    "ledger row exists but terminal command missing for GWAL-" + replay.get().getId()));
            return new WithdrawResult(prior.getId(), 60);
        }

        RealtyGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow();
        User leader = userRepository.findById(group.getLeaderId())
            .orElseThrow(() -> new IllegalStateException(
                "group " + group.getPublicId() + " leader id " + group.getLeaderId() + " missing"));

        if (leader.getWalletTermsAcceptedAt() == null) {
            throw new com.slparcelauctions.backend.realty.wallet.exception
                .LeaderTermsNotAcceptedException(leader.getPublicId());
        }
        if ((leader.getWalletFrozenAt() != null)
                || (leader.getBannedFromListing() != null && leader.getBannedFromListing())) {
            throw new com.slparcelauctions.backend.realty.wallet.exception.LeaderFrozenException();
        }
        long available = group.availableLindens();
        if (available < amount) {
            throw new com.slparcelauctions.backend.realty.wallet.exception
                .InsufficientGroupBalanceException(available, amount);
        }

        // §7.3 -- resolve recipient destination + descriptor before balance mutation.
        // The realty-group guard at the top of withdraw already blocks suspended groups
        // for the operate-permission gate -- re-checking explicitly here keeps the
        // semantic ("SL_GROUP path requires the realty group to be operable") legible
        // and survives any future loosening of the guard.
        String recipientUuid;
        TerminalCommandAction action;
        String description;
        if (recipient == GroupWithdrawRecipient.AVATAR) {
            recipientUuid = leader.getSlAvatarUuid().toString();
            action = TerminalCommandAction.WITHDRAW;
            description = "to leader avatar";
        } else {
            List<RealtyGroupSlGroup> regs =
                    slGroupRepository.findCurrentRegisteredForRealtyGroup(groupId);
            if (regs.isEmpty()) {
                throw new SlGroupNotRegisteredException(group.getPublicId());
            }
            if (suspensionRepository.existsActiveForGroup(groupId, OffsetDateTime.now(clock))) {
                throw new SlGroupRegistrationSuspendedException(group.getPublicId());
            }
            RealtyGroupSlGroup reg = regs.get(0);
            recipientUuid = reg.getSlGroupUuid().toString();
            action = TerminalCommandAction.WITHDRAW_GROUP;
            description = "to SL group "
                + (reg.getSlGroupName() == null ? reg.getSlGroupUuid() : reg.getSlGroupName());
        }

        long newBalance = group.getBalanceLindens() - amount;
        group.setBalanceLindens(newBalance);
        clearDormancyOnActivity(group);
        groupRepository.save(group);

        RealtyGroupLedgerEntry queuedEntry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(groupId)
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_QUEUED)
            .amount(amount)
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .actorUserId(callerUserId)
            .idempotencyKey(idemStr)
            .refType("TERMINAL_COMMAND")
            .description(description)
            .build());

        TerminalCommand cmd = terminalCommandRepository.save(TerminalCommand.builder()
            .action(action)
            .purpose(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL)
            .recipientUuid(recipientUuid)
            .amount(amount)
            .status(TerminalCommandStatus.QUEUED)
            .idempotencyKey("GWAL-" + queuedEntry.getId())
            .realtyGroupId(groupId)
            .nextAttemptAt(OffsetDateTime.now(clock))
            .attemptCount(0)
            .requiresManualReview(false)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.WITHDRAW_QUEUED.name(), queuedEntry.getPublicId());

        log.info("group withdraw queued: groupId={}, amount={}, recipient={}, action={}, "
                + "callerUserId={}, queueId={}",
            groupId, amount, recipient, action, callerUserId, cmd.getId());
        return new WithdrawResult(cmd.getId(), 60);
    }

    /**
     * Called by {@link com.slparcelauctions.backend.realty.wallet.GroupWalletWithdrawalCallbackHandler}
     * when the terminal confirms the L$ transfer. No balance change — balance was
     * decremented at queue time. Spec §5.3.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithdrawalSuccess(Long queuedLedgerId, String slTransactionKey) {
        RealtyGroupLedgerEntry queued = ledgerRepository.findById(queuedLedgerId).orElseThrow();
        ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(queued.getGroupId())
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_COMPLETED)
            .amount(queued.getAmount())
            .balanceAfter(queued.getBalanceAfter())
            .reservedAfter(queued.getReservedAfter())
            .slTransactionId(slTransactionKey)
            .refType("REALTY_GROUP_LEDGER_ENTRY")
            .refId(queuedLedgerId)
            .build());
        log.info("group withdraw completed: ledgerId={}, slTxn={}", queuedLedgerId, slTransactionKey);
    }

    /**
     * Called by {@link com.slparcelauctions.backend.realty.wallet.GroupWalletWithdrawalCallbackHandler}
     * when the terminal fails after retry exhaustion. Credits the L$ back to the
     * group balance and appends a WITHDRAW_REVERSED row. Spec §5.3.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithdrawalReversal(Long queuedLedgerId, String reason) {
        RealtyGroupLedgerEntry queued = ledgerRepository.findById(queuedLedgerId).orElseThrow();
        RealtyGroup group = groupRepository.findByIdForUpdate(queued.getGroupId()).orElseThrow();
        long newBalance = group.getBalanceLindens() + queued.getAmount();
        group.setBalanceLindens(newBalance);
        groupRepository.save(group);

        RealtyGroupLedgerEntry reversed = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(queued.getGroupId())
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_REVERSED)
            .amount(queued.getAmount())
            .balanceAfter(newBalance)
            .reservedAfter(group.getReservedLindens())
            .description(reason == null ? "transport failure" : reason)
            .refType("REALTY_GROUP_LEDGER_ENTRY")
            .refId(queuedLedgerId)
            .build());

        broadcastPublisher.publish(group.getPublicId(),
            newBalance, group.getReservedLindens(), group.availableLindens(),
            RealtyGroupLedgerEntryType.WITHDRAW_REVERSED.name(), reversed.getPublicId());
        log.warn("group withdraw reversed: ledgerId={}, reason={}, balanceAfter={}",
            queuedLedgerId, reason, newBalance);
    }

    /**
     * Returns the {@code groupId} of a realty_group_ledger row, or {@code null}
     * if the row does not exist. Used by callback handlers to route notifications.
     */
    public Long findGroupIdForLedgerEntry(Long ledgerEntryId) {
        return ledgerRepository.findById(ledgerEntryId)
            .map(RealtyGroupLedgerEntry::getGroupId)
            .orElse(null);
    }

    /* ============================================================ */
    /* INTERNAL HELPERS                                              */
    /* ============================================================ */

    /**
     * Clear dormancy phase and start timestamp if a group is in an active
     * dormancy phase (1–4). Phase 99 (COMPLETED — auto-return already fired)
     * is intentionally preserved. Spec §10.4.
     */
    private void clearDormancyOnActivity(RealtyGroup group) {
        if (group.getWalletDormancyPhase() != null && group.getWalletDormancyPhase() != 99) {
            log.info("clearing group dormancy on activity: groupId={}, priorPhase={}",
                group.getId(), group.getWalletDormancyPhase());
            group.setWalletDormancyPhase(null);
            group.setWalletDormancyStartedAt(null);
        }
    }
}
