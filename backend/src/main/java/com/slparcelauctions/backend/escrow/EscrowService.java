package com.slparcelauctions.backend.escrow;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.escrow.exception.IllegalEscrowTransitionException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Central orchestrator for escrow lifecycle transitions (spec §4.2). Tasks
 * 2-9 progressively add methods — this task ships the static allowed-
 * transitions table and the isAllowed / enforceTransitionAllowed helpers
 * plus the collaborators later tasks will use.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscrowService {

    static final Map<EscrowState, Set<EscrowState>> ALLOWED_TRANSITIONS = Map.of(
            EscrowState.ESCROW_PENDING,
                    Set.of(EscrowState.FUNDED, EscrowState.EXPIRED, EscrowState.DISPUTED),
            EscrowState.FUNDED,
                    Set.of(EscrowState.TRANSFER_PENDING, EscrowState.DISPUTED),
            EscrowState.TRANSFER_PENDING,
                    Set.of(EscrowState.COMPLETED, EscrowState.EXPIRED,
                           EscrowState.FROZEN, EscrowState.DISPUTED),
            EscrowState.COMPLETED, Set.of(),
            EscrowState.EXPIRED, Set.of(),
            EscrowState.DISPUTED, Set.of(),
            EscrowState.FROZEN, Set.of()
    );

    private final EscrowRepository escrowRepo;
    private final EscrowTransactionRepository ledgerRepo;
    private final EscrowCommissionCalculator commission;
    private final Clock clock;

    public static boolean isAllowed(EscrowState from, EscrowState to) {
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static void enforceTransitionAllowed(Long escrowId, EscrowState from, EscrowState to) {
        if (!isAllowed(from, to)) {
            throw new IllegalEscrowTransitionException(escrowId, from, to);
        }
    }
}
