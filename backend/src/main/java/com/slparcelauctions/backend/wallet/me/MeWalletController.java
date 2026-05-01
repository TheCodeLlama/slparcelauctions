package com.slparcelauctions.backend.wallet.me;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;
import com.slparcelauctions.backend.wallet.WalletService;
import com.slparcelauctions.backend.wallet.exception.PenaltyOutstandingException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * User-facing wallet endpoints. JWT-authenticated; user identity is taken
 * from the principal — never client-supplied. Recipient UUID for any
 * outbound L$ is always {@code user.slAvatarUuid} (locked at verification).
 *
 * <p>See spec §4.2.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Slf4j
public class MeWalletController {

    private final WalletService walletService;
    private final UserRepository userRepository;
    private final UserLedgerRepository ledgerRepository;
    private final AuctionRepository auctionRepository;
    private final Clock clock;

    @Value("${slpa.listing-fee.amount-lindens:100}")
    private Long defaultListingFee;

    /* ============================================================ */
    /* GET /me/wallet                                                */
    /* ============================================================ */

    @GetMapping("/wallet")
    @Transactional(readOnly = true)
    public WalletViewResponse view(@AuthenticationPrincipal AuthPrincipal principal) {
        User user = userRepository.findById(principal.userId()).orElseThrow();
        List<UserLedgerEntry> recent = ledgerRepository.findTop50ByUserIdOrderByCreatedAtDesc(principal.userId());
        return new WalletViewResponse(
                user.getBalanceLindens(),
                user.getReservedLindens(),
                user.availableLindens(),
                user.getPenaltyBalanceOwed() == null ? 0L : user.getPenaltyBalanceOwed(),
                user.getWalletTermsAcceptedAt() != null,
                user.getWalletTermsVersion(),
                user.getWalletTermsAcceptedAt(),
                recent.stream().map(MeWalletController::toDto).toList()
        );
    }

    private static WalletViewResponse.LedgerEntryDto toDto(UserLedgerEntry e) {
        return new WalletViewResponse.LedgerEntryDto(
                e.getId(),
                e.getEntryType().name(),
                e.getAmount(),
                e.getBalanceAfter(),
                e.getReservedAfter(),
                e.getRefType(),
                e.getRefId(),
                e.getDescription(),
                e.getCreatedAt()
        );
    }

    /* ============================================================ */
    /* GET /me/wallet/ledger                                         */
    /* ============================================================ */

    @GetMapping("/wallet/ledger")
    @Transactional(readOnly = true)
    public LedgerPageResponse ledger(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(name = "entryType", required = false) List<UserLedgerEntryType> entryTypes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Long amountMin,
            @RequestParam(required = false) Long amountMax) {
        int pageSize = Math.min(Math.max(size, 1), 100);
        LedgerFilter filter = new LedgerFilter(entryTypes, from, to, amountMin, amountMax);
        Pageable p = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UserLedgerEntry> result = ledgerRepository.findAll(
                LedgerSpecifications.forUser(principal.userId(), filter), p);
        List<WalletViewResponse.LedgerEntryDto> dtos =
                result.getContent().stream().map(MeWalletController::toDto).toList();
        return new LedgerPageResponse(
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), dtos);
    }

    /* ============================================================ */
    /* POST /me/wallet/withdraw                                      */
    /* ============================================================ */

    @PostMapping("/wallet/withdraw")
    public ResponseEntity<WithdrawApiResponse> withdraw(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody WithdrawApiRequest req) {
        WalletService.WithdrawQueuedResult result =
                walletService.withdrawSiteInitiated(principal.userId(), req.amount(), req.idempotencyKey());
        Long queueId;
        long newBalance;
        long newAvailable;
        String status;
        if (result instanceof WalletService.WithdrawQueuedResult.Ok ok) {
            queueId = ok.entry().getId();
            newBalance = ok.user().getBalanceLindens();
            newAvailable = ok.user().availableLindens();
            status = "QUEUED";
        } else {
            // Replay
            queueId = result.entry().getId();
            User user = userRepository.findById(principal.userId()).orElseThrow();
            newBalance = user.getBalanceLindens();
            newAvailable = user.availableLindens();
            status = "QUEUED_REPLAY";
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new WithdrawApiResponse(queueId, newBalance, newAvailable, status));
    }

    /* ============================================================ */
    /* POST /me/wallet/pay-penalty                                   */
    /* ============================================================ */

    @PostMapping("/wallet/pay-penalty")
    public PayPenaltyApiResponse payPenalty(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody PayPenaltyApiRequest req) {
        WalletService.PenaltyDebitResult result =
                walletService.payPenalty(principal.userId(), req.amount(), req.idempotencyKey());
        User user = result instanceof WalletService.PenaltyDebitResult.Ok ok
                ? ok.user()
                : userRepository.findById(principal.userId()).orElseThrow();
        return new PayPenaltyApiResponse(
                user.getBalanceLindens(),
                user.availableLindens(),
                user.getPenaltyBalanceOwed() == null ? 0L : user.getPenaltyBalanceOwed()
        );
    }

    /* ============================================================ */
    /* POST /me/wallet/accept-terms                                  */
    /* ============================================================ */

    @PostMapping("/wallet/accept-terms")
    @Transactional
    public ResponseEntity<Void> acceptTerms(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AcceptTermsRequest req) {
        User user = userRepository.findByIdForUpdate(principal.userId()).orElseThrow();
        user.setWalletTermsAcceptedAt(OffsetDateTime.now());
        user.setWalletTermsVersion(req.termsVersion());
        userRepository.save(user);
        log.info("user {} accepted wallet ToU version {}", principal.userId(), req.termsVersion());
        return ResponseEntity.ok().build();
    }

    /* ============================================================ */
    /* POST /me/auctions/{id}/pay-listing-fee                        */
    /* ============================================================ */

    @PostMapping("/auctions/{id}/pay-listing-fee")
    @Transactional
    public PayListingFeeResponse payListingFee(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody PayListingFeeRequest req) {

        Auction auction = auctionRepository.findById(id)
                .orElseThrow(() -> new AuctionNotFoundException(id));
        if (!auction.getSeller().getId().equals(principal.userId())) {
            throw new AuctionNotFoundException(id);  // hide existence to non-owners
        }
        if (auction.getStatus() != AuctionStatus.DRAFT) {
            throw new InvalidAuctionStateException(auction.getId(), auction.getStatus(), "PAY_LISTING_FEE");
        }

        User user = userRepository.findByIdForUpdate(principal.userId()).orElseThrow();

        long owed = user.getPenaltyBalanceOwed() == null ? 0L : user.getPenaltyBalanceOwed();
        if (owed > 0) {
            throw new PenaltyOutstandingException(owed);
        }

        Long amount = auction.getListingFeeAmt() != null ? auction.getListingFeeAmt() : defaultListingFee;

        // WalletService.debitListingFee validates available >= amount.
        walletService.debitListingFee(user, amount, auction.getId());

        auction.setListingFeePaid(true);
        auction.setListingFeeAmt(amount);
        auction.setListingFeeTxn("wallet:" + req.idempotencyKey());
        auction.setListingFeePaidAt(OffsetDateTime.now(clock));
        auction.setStatus(AuctionStatus.DRAFT_PAID);
        auctionRepository.save(auction);

        log.info("listing fee paid from wallet: auctionId={}, userId={}, amount={}",
                auction.getId(), user.getId(), amount);

        return new PayListingFeeResponse(
                user.getBalanceLindens(),
                user.availableLindens(),
                AuctionStatus.DRAFT_PAID.name()
        );
    }
}
