package com.slparcelauctions.backend.auction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auction.dto.CancellationHistoryDto;
import com.slparcelauctions.backend.auction.dto.CancellationStatusResponse;
import com.slparcelauctions.backend.auction.dto.NextConsequenceDto;
import com.slparcelauctions.backend.auction.exception.AuctionExceptionHandler;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;

/**
 * Slice tests for {@link CancellationStatusController}. Filters off (the
 * security filter chain is exercised separately); auth principal injected via
 * {@code @WithMockAuthPrincipal} so the controller can read {@code userId}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>cancellation-status response shape across all four ladder positions
 *       (warning → penalty → penalty + 30d → permanent ban).</li>
 *   <li>currentSuspension echoes the user's three new columns verbatim.</li>
 *   <li>cancellation-history paginated response shape and size clamp.</li>
 * </ul>
 */
@WebMvcTest(controllers = CancellationStatusController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, AuctionExceptionHandler.class})
class CancellationStatusControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CancellationStatusService service;
    @MockitoBean private JwtService jwtService;

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void status_zeroPriorOffenses_returnsWarningAsNextConsequence() throws Exception {
        CancellationStatusResponse resp = new CancellationStatusResponse(
                0L,
                new CancellationStatusResponse.CurrentSuspension(0L, null, false),
                NextConsequenceDto.from(CancellationOffenseKind.WARNING, null));
        when(service.statusFor(42L)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/users/me/cancellation-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorOffensesWithBids").value(0))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.kind").value("WARNING"))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.amountL").doesNotExist())
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.suspends30Days").value(false))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.permanentBan").value(false))
                .andExpect(jsonPath("$.currentSuspension.penaltyBalanceOwed").value(0))
                .andExpect(jsonPath("$.currentSuspension.bannedFromListing").value(false));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void status_onePriorOffense_returnsPenaltyAsNext() throws Exception {
        CancellationStatusResponse resp = new CancellationStatusResponse(
                1L,
                new CancellationStatusResponse.CurrentSuspension(0L, null, false),
                NextConsequenceDto.from(CancellationOffenseKind.PENALTY, 1000L));
        when(service.statusFor(42L)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/users/me/cancellation-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorOffensesWithBids").value(1))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.kind").value("PENALTY"))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.amountL").value(1000))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.suspends30Days").value(false))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.permanentBan").value(false));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void status_twoPriorOffenses_returnsPenaltyAnd30dAsNext() throws Exception {
        CancellationStatusResponse resp = new CancellationStatusResponse(
                2L,
                new CancellationStatusResponse.CurrentSuspension(1000L, null, false),
                NextConsequenceDto.from(CancellationOffenseKind.PENALTY_AND_30D, 2500L));
        when(service.statusFor(42L)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/users/me/cancellation-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorOffensesWithBids").value(2))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.kind").value("PENALTY_AND_30D"))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.amountL").value(2500))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.suspends30Days").value(true))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.permanentBan").value(false));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void status_threePlusOffenses_returnsPermanentBanAsNext() throws Exception {
        CancellationStatusResponse resp = new CancellationStatusResponse(
                3L,
                new CancellationStatusResponse.CurrentSuspension(2500L,
                        OffsetDateTime.now().plusDays(20), false),
                NextConsequenceDto.from(CancellationOffenseKind.PERMANENT_BAN, null));
        when(service.statusFor(42L)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/users/me/cancellation-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorOffensesWithBids").value(3))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.kind").value("PERMANENT_BAN"))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.amountL").doesNotExist())
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.suspends30Days").value(false))
                .andExpect(jsonPath("$.nextConsequenceIfBidsPresent.permanentBan").value(true));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void status_currentSuspensionEchoesUserState() throws Exception {
        OffsetDateTime suspendedUntil = OffsetDateTime.parse("2026-05-24T10:15:00Z");
        CancellationStatusResponse resp = new CancellationStatusResponse(
                2L,
                new CancellationStatusResponse.CurrentSuspension(2500L, suspendedUntil, false),
                NextConsequenceDto.from(CancellationOffenseKind.PENALTY_AND_30D, 2500L));
        when(service.statusFor(42L)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/users/me/cancellation-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentSuspension.penaltyBalanceOwed").value(2500))
                .andExpect(jsonPath("$.currentSuspension.listingSuspensionUntil")
                        .value("2026-05-24T10:15:00Z"))
                .andExpect(jsonPath("$.currentSuspension.bannedFromListing").value(false));
    }

    // -------------------------------------------------------------------------
    // History
    // -------------------------------------------------------------------------

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void history_returnsPagedResponse_shape() throws Exception {
        OffsetDateTime cancelledAt = OffsetDateTime.parse("2026-04-20T10:15:00Z");
        CancellationHistoryDto row = new CancellationHistoryDto(
                1234L, "Aurora Parcel", "https://snap.example/1.png",
                "ACTIVE", true, "Personal reasons.", cancelledAt,
                new CancellationHistoryDto.PenaltyApplied(
                        CancellationOffenseKind.PENALTY, 1000L));
        Page<CancellationHistoryDto> page = new PageImpl<>(
                List.of(row), PageRequest.of(0, 10), 1);
        when(service.historyFor(eq(42L), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/users/me/cancellation-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].auctionId").value(1234))
                .andExpect(jsonPath("$.content[0].auctionTitle").value("Aurora Parcel"))
                .andExpect(jsonPath("$.content[0].cancelledFromStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].hadBids").value(true))
                .andExpect(jsonPath("$.content[0].penaltyApplied.kind").value("PENALTY"))
                .andExpect(jsonPath("$.content[0].penaltyApplied.amountL").value(1000))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void history_penaltyAppliedNullForNoneKind() throws Exception {
        // No-bid cancellations and pre-active cancellations show "No penalty"
        // — backend returns null in penaltyApplied so the frontend renders
        // a neutral badge.
        CancellationHistoryDto row = new CancellationHistoryDto(
                999L, "Cool Parcel", null,
                "DRAFT", false, "Changed my mind", OffsetDateTime.now(),
                null);
        Page<CancellationHistoryDto> page = new PageImpl<>(
                List.of(row), PageRequest.of(0, 10), 1);
        when(service.historyFor(eq(42L), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/users/me/cancellation-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].penaltyApplied").doesNotExist());
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void history_forwardsPagingArgsToService() throws Exception {
        Page<CancellationHistoryDto> empty = new PageImpl<>(
                List.of(), PageRequest.of(2, 5), 0);
        when(service.historyFor(eq(42L), any(Pageable.class))).thenReturn(empty);

        mockMvc.perform(get("/api/v1/users/me/cancellation-history?page=2&size=5"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCap = ArgumentCaptor.forClass(Pageable.class);
        verify(service).historyFor(eq(42L), pageableCap.capture());
        Pageable received = pageableCap.getValue();
        // Controller forwards raw page/size; service applies the size clamp.
        // page=2&size=5 → service receives PageRequest(2, 5).
        org.assertj.core.api.Assertions.assertThat(received.getPageNumber()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(received.getPageSize()).isEqualTo(5);
    }
}
