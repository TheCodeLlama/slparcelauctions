package com.slparcelauctions.backend.admin.disputes;

import com.slparcelauctions.backend.admin.disputes.exception.DisputeNotFoundException;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowDisputeReasonCategory;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.EscrowTransaction;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.dispute.EvidenceImage;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminDisputeQueryService {

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(5);

    private final EscrowRepository escrowRepo;
    private final EscrowTransactionRepository escrowTxRepo;
    private final UserRepository userRepo;
    private final ObjectStorageService storage;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<AdminDisputeQueueRow> list(
            EscrowState statusFilter,
            String reasonFilter,   // accepted but not yet wired into query
            int page, int size) {
        Page<Escrow> escrows = statusFilter != null
                ? escrowRepo.findByStateOrderByDisputedAtAsc(
                        statusFilter, PageRequest.of(page, size))
                : escrowRepo.findByStateInOrderByDisputedAtAsc(
                        List.of(EscrowState.DISPUTED, EscrowState.FROZEN),
                        PageRequest.of(page, size));
        return escrows.map(this::toQueueRow);
    }

    @Transactional(readOnly = true)
    public AdminDisputeDetail detail(Long escrowId) {
        Escrow e = escrowRepo.findById(escrowId)
                .orElseThrow(() -> new DisputeNotFoundException(escrowId));
        return toDetail(e);
    }

    private AdminDisputeQueueRow toQueueRow(Escrow e) {
        long ageMinutes = e.getDisputedAt() == null ? 0
                : ChronoUnit.MINUTES.between(e.getDisputedAt(), OffsetDateTime.now(clock));
        String winnerEmail = resolveEmail(e.getAuction().getWinnerUserId());
        return new AdminDisputeQueueRow(
                e.getId(),
                e.getAuction().getId(),
                e.getAuction().getTitle(),
                e.getAuction().getSeller().getEmail(),
                winnerEmail,
                e.getFinalBidAmount(),
                e.getState(),
                parseReason(e.getDisputeReasonCategory()),
                e.getDisputedAt(),
                ageMinutes,
                e.getWinnerEvidenceImages().size(),
                e.getSellerEvidenceImages().size());
    }

    private AdminDisputeDetail toDetail(Escrow e) {
        List<DisputeEvidenceImageDto> winnerImgs = presignAll(e.getWinnerEvidenceImages());
        List<DisputeEvidenceImageDto> sellerImgs = presignAll(e.getSellerEvidenceImages());
        long winnerUserId = e.getAuction().getWinnerUserId() == null ? 0L
                : e.getAuction().getWinnerUserId();
        String winnerEmail = resolveEmail(e.getAuction().getWinnerUserId());
        List<AdminDisputeDetail.EscrowLedgerEntry> ledger = buildLedger(e.getId());
        return new AdminDisputeDetail(
                e.getId(),
                e.getAuction().getId(),
                e.getAuction().getTitle(),
                e.getAuction().getSeller().getEmail(),
                e.getAuction().getSeller().getId(),
                winnerEmail,
                winnerUserId,
                e.getFinalBidAmount(),
                e.getState(),
                parseReason(e.getDisputeReasonCategory()),
                e.getDisputeDescription(),
                e.getSlTransactionKey(),
                winnerImgs,
                e.getSellerEvidenceText(),
                e.getSellerEvidenceSubmittedAt(),
                sellerImgs,
                e.getDisputedAt(),
                ledger);
    }

    /**
     * Builds the ledger from {@code EscrowTransaction} rows ordered by
     * {@code createdAt} ascending. The {@code detail} column surfaces the
     * SL transaction ID when present; otherwise the status string.
     */
    private List<AdminDisputeDetail.EscrowLedgerEntry> buildLedger(Long escrowId) {
        List<EscrowTransaction> txns = escrowTxRepo.findByEscrowIdOrderByCreatedAtAsc(escrowId);
        return txns.stream().map(tx -> new AdminDisputeDetail.EscrowLedgerEntry(
                tx.getCreatedAt(),
                tx.getType().name(),
                tx.getAmount(),
                tx.getSlTransactionId() != null ? tx.getSlTransactionId()
                        : tx.getStatus().name())).toList();
    }

    private List<DisputeEvidenceImageDto> presignAll(List<EvidenceImage> imgs) {
        OffsetDateTime expiry = OffsetDateTime.now(clock).plus(PRESIGN_TTL);
        return imgs.stream().map(img -> new DisputeEvidenceImageDto(
                img.s3Key(), img.contentType(), img.size(), img.uploadedAt(),
                storage.presignGet(img.s3Key(), PRESIGN_TTL), expiry)).toList();
    }

    private String resolveEmail(Long userId) {
        if (userId == null) return null;
        return userRepo.findById(userId).map(User::getEmail).orElse(null);
    }

    private static EscrowDisputeReasonCategory parseReason(String s) {
        if (s == null) return null;
        try {
            return EscrowDisputeReasonCategory.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
