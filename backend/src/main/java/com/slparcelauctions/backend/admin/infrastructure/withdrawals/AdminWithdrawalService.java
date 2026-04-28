package com.slparcelauctions.backend.admin.infrastructure.withdrawals;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.infrastructure.withdrawals.exception.InsufficientBalanceException;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandService;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminWithdrawalService {

    private static final Duration BALANCE_STALENESS = Duration.ofHours(2);
    private static final List<EscrowState> LOCKED_STATES = List.of(
            EscrowState.FUNDED, EscrowState.TRANSFER_PENDING,
            EscrowState.DISPUTED, EscrowState.FROZEN);
    private static final List<TerminalCommandStatus> OUTBOUND = List.of(
            TerminalCommandStatus.QUEUED, TerminalCommandStatus.IN_FLIGHT);

    private final WithdrawalRepository withdrawalRepo;
    private final EscrowRepository escrowRepo;
    private final TerminalRepository terminalRepo;
    private final TerminalCommandRepository commandRepo;
    private final AdminActionService adminActionService;
    private final Clock clock;
    private final TerminalCommandService commandService;

    @Transactional(readOnly = true)
    public long availableToWithdraw() {
        long observed = freshestObservedBalance();
        long locked = escrowRepo.sumAmountByStateIn(LOCKED_STATES);
        long pendingWith = withdrawalRepo.sumPending();
        long outbound = commandRepo.sumAmountByActionInAndStatusIn(
                List.of(TerminalCommandAction.PAYOUT, TerminalCommandAction.REFUND),
                OUTBOUND);
        return observed - locked - pendingWith - outbound;
    }

    private long freshestObservedBalance() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(BALANCE_STALENESS);
        return terminalRepo.findAll().stream()
                .filter(t -> t.getLastHeartbeatAt() != null
                        && t.getLastHeartbeatAt().isAfter(cutoff)
                        && t.getLastReportedBalance() != null)
                .max(Comparator.comparing(Terminal::getLastHeartbeatAt))
                .map(Terminal::getLastReportedBalance)
                .orElse(0L);
    }

    @Transactional
    public Withdrawal requestWithdrawal(WithdrawalRequest req, Long adminUserId) {
        if (req.amount() == null || req.amount() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        long available = availableToWithdraw();
        if (req.amount() > available) {
            throw new InsufficientBalanceException(req.amount(), available);
        }

        Withdrawal w = withdrawalRepo.save(Withdrawal.builder()
                .amount(req.amount())
                .recipientUuid(req.recipientUuid())
                .adminUserId(adminUserId)
                .notes(req.notes())
                .status(WithdrawalStatus.PENDING)
                .build());

        var cmd = commandService.queueWithdraw(w.getId(), req.recipientUuid(), req.amount());
        w.setTerminalCommandId(cmd.getId());
        withdrawalRepo.save(w);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("withdrawalId", w.getId());
        details.put("amount", req.amount());
        details.put("recipientUuid", req.recipientUuid());
        details.put("notes", req.notes());
        adminActionService.record(adminUserId,
                AdminActionType.WITHDRAWAL_REQUESTED,
                AdminActionTargetType.WITHDRAWAL,
                w.getId(), req.notes(), details);

        log.info("Withdrawal queued: id={}, amount=L${}, recipient={}, admin={}",
                w.getId(), req.amount(), req.recipientUuid(), adminUserId);
        return w;
    }

    @Transactional(readOnly = true)
    public Page<Withdrawal> list(int page, int size) {
        return withdrawalRepo.findAllByOrderByRequestedAtDesc(PageRequest.of(page, size));
    }
}
