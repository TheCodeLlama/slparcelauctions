package com.slparcelauctions.backend.escrow.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.slparcelauctions.backend.config.SecurityConfig;
import com.slparcelauctions.backend.escrow.exception.EscrowExceptionHandler;
import com.slparcelauctions.backend.escrow.exception.TerminalAuthException;
import com.slparcelauctions.backend.escrow.payment.dto.ListingFeePaymentRequest;
import com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse;
import com.slparcelauctions.backend.sl.SlHeaderValidator;
import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;

/**
 * Slice tests for {@link ListingFeePaymentController}. Stubs
 * {@link ListingFeePaymentService} + {@link SlHeaderValidator} and
 * exercises the real Spring Security filter chain plus the
 * {@link EscrowExceptionHandler} so the 200/400/403 paths render the
 * advertised ProblemDetail / {@link SlCallbackResponse} shapes. Covers
 * the seven scenarios from spec §10.3: happy path, wrong payer, wrong
 * amount, already paid, unknown auction, bad headers, bad secret.
 */
@WebMvcTest(ListingFeePaymentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class, EscrowExceptionHandler.class})
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdA==",
        "jwt.access-token-lifetime=PT15M",
        "jwt.refresh-token-lifetime=P7D"
})
class ListingFeePaymentControllerSliceTest {

    private static final String SHARD = "Production";
    private static final String OWNER_KEY = "00000000-0000-0000-0000-000000000001";
    private static final String TERMINAL_ID = "11111111-1111-1111-1111-111111111111";
    private static final String PAYER_UUID = "22222222-2222-2222-2222-222222222222";
    private static final String TXN_KEY = "sl-txn-lf-abc123";
    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";

    @Autowired MockMvc mockMvc;
    @MockitoBean ListingFeePaymentService listingFeePaymentService;
    @MockitoBean SlHeaderValidator headerValidator;
    @MockitoBean JwtService jwtService;
    @MockitoBean JwtConfig jwtConfig;

    private static String body(long auctionId, long amount) {
        return body(auctionId, PAYER_UUID, amount, TXN_KEY, TERMINAL_ID, SHARED_SECRET);
    }

    private static String body(long auctionId, String payerUuid, long amount,
                               String txnKey, String terminalId, String secret) {
        return String.format("""
                {
                  "auctionId":%d,
                  "payerUuid":"%s",
                  "amount":%d,
                  "slTransactionKey":"%s",
                  "terminalId":"%s",
                  "sharedSecret":"%s"
                }
                """, auctionId, payerUuid, amount, txnKey, terminalId, secret);
    }

    @Test
    void validPayment_returns200Ok() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(listingFeePaymentService.acceptPayment(any(ListingFeePaymentRequest.class)))
                .thenReturn(SlCallbackResponse.ok());

        mockMvc.perform(post("/api/v1/sl/listing-fee/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(42L, 100L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void wrongPayer_returns200RefundWithWrongPayerReason() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(listingFeePaymentService.acceptPayment(any(ListingFeePaymentRequest.class)))
                .thenReturn(SlCallbackResponse.refund(
                        EscrowCallbackResponseReason.WRONG_PAYER,
                        "Payer does not match seller"));

        mockMvc.perform(post("/api/v1/sl/listing-fee/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(42L, 100L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND"))
                .andExpect(jsonPath("$.reason").value("WRONG_PAYER"))
                .andExpect(jsonPath("$.message").value("Payer does not match seller"));
    }

    @Test
    void wrongAmount_returns200RefundWithWrongAmountReason() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(listingFeePaymentService.acceptPayment(any(ListingFeePaymentRequest.class)))
                .thenReturn(SlCallbackResponse.refund(
                        EscrowCallbackResponseReason.WRONG_AMOUNT,
                        "Expected L$100, got L$50"));

        mockMvc.perform(post("/api/v1/sl/listing-fee/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(42L, 50L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND"))
                .andExpect(jsonPath("$.reason").value("WRONG_AMOUNT"))
                .andExpect(jsonPath("$.message").value("Expected L$100, got L$50"));
    }

    @Test
    void alreadyPaid_returns200ErrorWithAlreadyPaidReason() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(listingFeePaymentService.acceptPayment(any(ListingFeePaymentRequest.class)))
                .thenReturn(SlCallbackResponse.error(
                        EscrowCallbackResponseReason.ALREADY_PAID,
                        "Auction 42 already paid"));

        mockMvc.perform(post("/api/v1/sl/listing-fee/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(42L, 100L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.reason").value("ALREADY_PAID"))
                .andExpect(jsonPath("$.message").value("Auction 42 already paid"));
    }

    @Test
    void unknownAuction_returns200ErrorWithUnknownAuctionReason() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(listingFeePaymentService.acceptPayment(any(ListingFeePaymentRequest.class)))
                .thenReturn(SlCallbackResponse.error(
                        EscrowCallbackResponseReason.UNKNOWN_AUCTION,
                        "Auction 999 not found"));

        mockMvc.perform(post("/api/v1/sl/listing-fee/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(999L, 100L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.reason").value("UNKNOWN_AUCTION"))
                .andExpect(jsonPath("$.message").value("Auction 999 not found"));
    }

    @Test
    void badSlHeaders_returns403WithInvalidHeadersCode() throws Exception {
        doThrow(new InvalidSlHeadersException("Owner key missing or malformed"))
                .when(headerValidator).validate(any(), any());

        mockMvc.perform(post("/api/v1/sl/listing-fee/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(42L, 100L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SL_INVALID_HEADERS"));

        verifyNoInteractions(listingFeePaymentService);
    }

    @Test
    void badSharedSecret_returns403WithSecretMismatch() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(listingFeePaymentService.acceptPayment(any(ListingFeePaymentRequest.class)))
                .thenThrow(new TerminalAuthException());

        mockMvc.perform(post("/api/v1/sl/listing-fee/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(42L, PAYER_UUID, 100L, TXN_KEY, TERMINAL_ID, "wrong-secret")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECRET_MISMATCH"));
    }

    @Test
    void malformedBody_returns400() throws Exception {
        // Missing required fields fail bean-validation before reaching
        // either the header validator or the service.
        doNothing().when(headerValidator).validate(anyString(), anyString());

        mockMvc.perform(post("/api/v1/sl/listing-fee/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content("""
                                {
                                  "auctionId":42,
                                  "payerUuid":"",
                                  "amount":100,
                                  "slTransactionKey":"tx",
                                  "terminalId":"term",
                                  "sharedSecret":"secret"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(listingFeePaymentService);
    }
}
