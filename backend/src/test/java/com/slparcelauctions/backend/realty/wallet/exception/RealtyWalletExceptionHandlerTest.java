package com.slparcelauctions.backend.realty.wallet.exception;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.realty.exception.RealtyExceptionHandler;

class RealtyWalletExceptionHandlerTest {

    private final RealtyExceptionHandler handler = new RealtyExceptionHandler();
    private final MockHttpServletRequest req = new MockHttpServletRequest("POST", "/realty/groups/test/wallet");

    @Test
    void mapsInsufficientGroupBalance() {
        ProblemDetail pd = handler.handleInsufficientGroupBalance(
            new InsufficientGroupBalanceException(100L, 500L), req);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(pd.getProperties()).containsKey("code");
        assertThat(pd.getProperties().get("code")).isEqualTo("INSUFFICIENT_GROUP_BALANCE");
        assertThat(pd.getProperties()).containsEntry("available", 100L);
        assertThat(pd.getProperties()).containsEntry("requested", 500L);
    }

    @Test
    void mapsLeaderTermsNotAccepted() {
        UUID leader = UUID.randomUUID();
        ProblemDetail pd = handler.handleLeaderTermsNotAccepted(
            new LeaderTermsNotAcceptedException(leader), req);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(pd.getProperties().get("code")).isEqualTo("LEADER_TERMS_NOT_ACCEPTED");
        assertThat(pd.getProperties().get("leaderPublicId")).isEqualTo(leader.toString());
    }

    @Test
    void mapsLeaderFrozen() {
        ProblemDetail pd = handler.handleLeaderFrozen(new LeaderFrozenException(), req);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(pd.getProperties().get("code")).isEqualTo("LEADER_FROZEN");
    }

    @Test
    void mapsGroupHasNonzeroBalance() {
        ProblemDetail pd = handler.handleGroupHasNonzeroBalance(
            new GroupHasNonzeroBalanceException(), req);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties().get("code")).isEqualTo("GROUP_HAS_NONZERO_BALANCE");
    }

    @Test
    void mapsGroupHasInFlightEscrows() {
        ProblemDetail pd = handler.handleGroupHasInFlightEscrows(
            new GroupHasInFlightEscrowsException(), req);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties().get("code")).isEqualTo("GROUP_HAS_INFLIGHT_ESCROWS");
    }
}
