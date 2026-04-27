package com.slparcelauctions.backend.sl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.JwtAuthenticationEntryPoint;
import com.slparcelauctions.backend.auth.JwtAuthenticationFilter;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.bot.BotSharedSecretAuthorizer;
import com.slparcelauctions.backend.notification.slim.internal.SlImInternalConfig;
import com.slparcelauctions.backend.config.SecurityConfig;
import com.slparcelauctions.backend.sl.dto.PenaltyLookupResponse;
import com.slparcelauctions.backend.sl.dto.PenaltyPaymentRequest;
import com.slparcelauctions.backend.sl.dto.PenaltyPaymentResponse;
import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;
import com.slparcelauctions.backend.sl.exception.PenaltyOverpaymentException;
import com.slparcelauctions.backend.user.UserNotFoundException;

/**
 * Slice tests for {@link PenaltyTerminalController}. Stubs
 * {@link PenaltyTerminalService} + {@link SlHeaderValidator} and
 * exercises the real Spring Security filter chain plus
 * {@link SlExceptionHandler} so the 200/400/403/404/422 paths render
 * the advertised ProblemDetail / DTO shapes. Covers the scenarios in
 * plan Step 3.
 */
@WebMvcTest(PenaltyTerminalController.class)
@Import({SlImInternalConfig.class, SecurityConfig.class, JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class, SlExceptionHandler.class})
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdA==",
        "jwt.access-token-lifetime=PT15M",
        "jwt.refresh-token-lifetime=P7D",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class PenaltyTerminalControllerSliceTest {

    private static final String SHARD = "Production";
    private static final String OWNER_KEY = "00000000-0000-0000-0000-000000000001";
    private static final String AVATAR_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String TERMINAL_ID = "terminal-7";
    private static final String SL_TXN = "sl-txn-pen-abc123";

    @Autowired MockMvc mockMvc;
    @MockitoBean PenaltyTerminalService service;
    @MockitoBean SlHeaderValidator headerValidator;
    @MockitoBean JwtService jwtService;
    @MockitoBean JwtConfig jwtConfig;
    // SecurityConfig depends on BotSharedSecretAuthorizer (Epic 06 Task 3).
    @MockitoBean BotSharedSecretAuthorizer botSharedSecretAuthorizer;

    private static String lookupBody(String avatar, String terminal) {
        return String.format("""
                { "slAvatarUuid":"%s", "terminalId":"%s" }
                """, avatar, terminal);
    }

    private static String paymentBody(String avatar, String slTxn, long amount, String terminal) {
        return String.format("""
                {
                  "slAvatarUuid":"%s",
                  "slTransactionId":"%s",
                  "amount":%d,
                  "terminalId":"%s"
                }
                """, avatar, slTxn, amount, terminal);
    }

    // -----------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------

    @Test
    void lookup_returnsBalance_whenUserOwes() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(service.lookup(any(UUID.class)))
                .thenReturn(new PenaltyLookupResponse(42L, "Alice", 1000L));

        mockMvc.perform(post("/api/v1/sl/penalty-lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(lookupBody(AVATAR_UUID, TERMINAL_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.penaltyBalanceOwed").value(1000));
    }

    @Test
    void lookup_returns404_whenAvatarUnknown() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(service.lookup(any(UUID.class)))
                .thenThrow(new UserNotFoundException(
                        "No SLPA user found for avatar " + AVATAR_UUID));

        mockMvc.perform(post("/api/v1/sl/penalty-lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(lookupBody(AVATAR_UUID, TERMINAL_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void lookup_returns404_whenBalanceIsZero() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(service.lookup(any(UUID.class)))
                .thenThrow(new UserNotFoundException(
                        "No outstanding penalty balance for avatar " + AVATAR_UUID));

        mockMvc.perform(post("/api/v1/sl/penalty-lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(lookupBody(AVATAR_UUID, TERMINAL_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void lookup_returns403_withoutSlOwnerKeyHeader() throws Exception {
        doThrow(new InvalidSlHeadersException("Owner key missing or malformed"))
                .when(headerValidator).validate(any(), any());

        mockMvc.perform(post("/api/v1/sl/penalty-lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lookupBody(AVATAR_UUID, TERMINAL_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SL_INVALID_HEADERS"));

        verifyNoInteractions(service);
    }

    @Test
    void lookup_returns400_whenAvatarUuidMalformed() throws Exception {
        doNothing().when(headerValidator).validate(anyString(), anyString());

        mockMvc.perform(post("/api/v1/sl/penalty-lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content("""
                                { "slAvatarUuid":"not-a-uuid", "terminalId":"t" }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    // -----------------------------------------------------------------
    // Payment
    // -----------------------------------------------------------------

    @Test
    void payment_partialClear_returns200WithRemainingBalance() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(service.pay(any(PenaltyPaymentRequest.class)))
                .thenReturn(new PenaltyPaymentResponse(400L));

        mockMvc.perform(post("/api/v1/sl/penalty-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(paymentBody(AVATAR_UUID, SL_TXN, 600L, TERMINAL_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingBalance").value(400));
    }

    @Test
    void payment_fullClear_returnsZeroBalance() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(service.pay(any(PenaltyPaymentRequest.class)))
                .thenReturn(new PenaltyPaymentResponse(0L));

        mockMvc.perform(post("/api/v1/sl/penalty-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(paymentBody(AVATAR_UUID, SL_TXN, 1000L, TERMINAL_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingBalance").value(0));
    }

    @Test
    void payment_overpayment_returns422() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(service.pay(any(PenaltyPaymentRequest.class)))
                .thenThrow(new PenaltyOverpaymentException(1000L, 500L));

        mockMvc.perform(post("/api/v1/sl/penalty-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(paymentBody(AVATAR_UUID, SL_TXN, 1000L, TERMINAL_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SL_PENALTY_OVERPAYMENT"))
                .andExpect(jsonPath("$.requested").value(1000))
                .andExpect(jsonPath("$.available").value(500));
    }

    @Test
    void payment_unknownAvatar_returns404() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(service.pay(any(PenaltyPaymentRequest.class)))
                .thenThrow(new UserNotFoundException(
                        "No SLPA user found for avatar " + AVATAR_UUID));

        mockMvc.perform(post("/api/v1/sl/penalty-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(paymentBody(AVATAR_UUID, SL_TXN, 100L, TERMINAL_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void payment_amountZero_returns400() throws Exception {
        doNothing().when(headerValidator).validate(anyString(), anyString());

        mockMvc.perform(post("/api/v1/sl/penalty-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(paymentBody(AVATAR_UUID, SL_TXN, 0L, TERMINAL_ID)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void payment_negativeAmount_returns400() throws Exception {
        doNothing().when(headerValidator).validate(anyString(), anyString());

        mockMvc.perform(post("/api/v1/sl/penalty-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(paymentBody(AVATAR_UUID, SL_TXN, -100L, TERMINAL_ID)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void payment_blankSlTransactionId_returns400() throws Exception {
        doNothing().when(headerValidator).validate(anyString(), anyString());

        mockMvc.perform(post("/api/v1/sl/penalty-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(paymentBody(AVATAR_UUID, "", 100L, TERMINAL_ID)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void payment_returns403_withoutSlOwnerKeyHeader() throws Exception {
        doThrow(new InvalidSlHeadersException("Owner key missing or malformed"))
                .when(headerValidator).validate(any(), any());

        mockMvc.perform(post("/api/v1/sl/penalty-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(AVATAR_UUID, SL_TXN, 100L, TERMINAL_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SL_INVALID_HEADERS"));

        verifyNoInteractions(service);
    }
}
