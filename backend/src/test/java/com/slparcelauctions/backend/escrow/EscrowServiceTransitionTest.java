package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.escrow.exception.IllegalEscrowTransitionException;

class EscrowServiceTransitionTest {

    @Test
    void validTransitionsFromEscrowPending() {
        assertThat(EscrowService.isAllowed(EscrowState.ESCROW_PENDING, EscrowState.FUNDED)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.ESCROW_PENDING, EscrowState.EXPIRED)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.ESCROW_PENDING, EscrowState.DISPUTED)).isTrue();
    }

    @Test
    void validTransitionsFromFunded() {
        assertThat(EscrowService.isAllowed(EscrowState.FUNDED, EscrowState.TRANSFER_PENDING)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.FUNDED, EscrowState.DISPUTED)).isTrue();
    }

    @Test
    void validTransitionsFromTransferPending() {
        assertThat(EscrowService.isAllowed(EscrowState.TRANSFER_PENDING, EscrowState.COMPLETED)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.TRANSFER_PENDING, EscrowState.EXPIRED)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.TRANSFER_PENDING, EscrowState.FROZEN)).isTrue();
        assertThat(EscrowService.isAllowed(EscrowState.TRANSFER_PENDING, EscrowState.DISPUTED)).isTrue();
    }

    @Test
    void terminalStatesAllowNoTransitions() {
        for (EscrowState target : EscrowState.values()) {
            assertThat(EscrowService.isAllowed(EscrowState.COMPLETED, target)).isFalse();
            assertThat(EscrowService.isAllowed(EscrowState.EXPIRED, target)).isFalse();
            assertThat(EscrowService.isAllowed(EscrowState.DISPUTED, target)).isFalse();
            assertThat(EscrowService.isAllowed(EscrowState.FROZEN, target)).isFalse();
        }
    }

    @Test
    void rejectsBackwardsTransitions() {
        assertThat(EscrowService.isAllowed(EscrowState.TRANSFER_PENDING, EscrowState.ESCROW_PENDING)).isFalse();
        assertThat(EscrowService.isAllowed(EscrowState.FUNDED, EscrowState.ESCROW_PENDING)).isFalse();
    }

    @Test
    void rejectsSkippingStates() {
        assertThat(EscrowService.isAllowed(EscrowState.ESCROW_PENDING, EscrowState.COMPLETED)).isFalse();
        assertThat(EscrowService.isAllowed(EscrowState.ESCROW_PENDING, EscrowState.TRANSFER_PENDING)).isFalse();
    }

    @Test
    void enforceTransitionThrowsForInvalidMoves() {
        assertThatThrownBy(() ->
                EscrowService.enforceTransitionAllowed(42L, EscrowState.COMPLETED, EscrowState.FUNDED))
                .isInstanceOf(IllegalEscrowTransitionException.class)
                .hasMessageContaining("42")
                .hasMessageContaining("COMPLETED")
                .hasMessageContaining("FUNDED");
    }

    @Test
    void enforceTransitionPassesThroughForValidMoves() {
        EscrowService.enforceTransitionAllowed(42L, EscrowState.ESCROW_PENDING, EscrowState.FUNDED);
    }
}
