package com.slparcelauctions.backend.realty.slgroup;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;
import com.slparcelauctions.backend.realty.exception.RealtyExceptionHandler;
import com.slparcelauctions.backend.realty.slgroup.dto.SlGroupVerifyRequest;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupFounderMismatchException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupVerificationExpiredException;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice tests for {@link SlGroupVerifyController}. Mirrors the
 * {@link RealtyGroupSlGroupControllerTest} shape: security filters disabled
 * (the real LSL trust gate -- shared-secret HMAC + {@code X-SecondLife-Owner-Key}
 * header validation -- lives in the in-world request filter and is covered by
 * integration tests for other LSL endpoints), service mocked, and the realty +
 * global exception handlers imported so status / {@code code} extension
 * assertions resolve.
 */
@WebMvcTest(controllers = SlGroupVerifyController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, RealtyExceptionHandler.class})
class SlGroupVerifyControllerTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private RealtyGroupSlGroupService service;
    // Bean-graph fillers — the slice loader would otherwise scan widely for these.
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;

    private static final String CODE = "ABC123";
    private static final UUID FOUNDER_AVATAR =
            UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID PAGE_FOUNDER =
            UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Test
    void verify_happyPath_returns200AndFlipsRow() throws Exception {
        RealtyGroupSlGroup verified = RealtyGroupSlGroup.builder()
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .verifiedVia(SlGroupVerifyMethod.FOUNDER_TERMINAL)
                .founderAvatarUuid(FOUNDER_AVATAR)
                .build();
        when(service.handleTerminalCallback(eq(CODE), eq(FOUNDER_AVATAR))).thenReturn(verified);

        SlGroupVerifyRequest req = new SlGroupVerifyRequest(CODE, FOUNDER_AVATAR);
        mockMvc.perform(post("/api/v1/sl/sl-group/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }

    @Test
    void verify_codeNotFound_410() throws Exception {
        when(service.handleTerminalCallback(eq(CODE), eq(FOUNDER_AVATAR)))
                .thenThrow(new SlGroupVerificationExpiredException(null));

        SlGroupVerifyRequest req = new SlGroupVerifyRequest(CODE, FOUNDER_AVATAR);
        mockMvc.perform(post("/api/v1/sl/sl-group/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SL_GROUP_VERIFICATION_EXPIRED"));
    }

    @Test
    void verify_founderMismatch_422() throws Exception {
        when(service.handleTerminalCallback(eq(CODE), eq(FOUNDER_AVATAR)))
                .thenThrow(new SlGroupFounderMismatchException(FOUNDER_AVATAR, PAGE_FOUNDER));

        SlGroupVerifyRequest req = new SlGroupVerifyRequest(CODE, FOUNDER_AVATAR);
        mockMvc.perform(post("/api/v1/sl/sl-group/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SL_GROUP_FOUNDER_MISMATCH"))
                .andExpect(jsonPath("$.reportedAvatarUuid").value(FOUNDER_AVATAR.toString()))
                .andExpect(jsonPath("$.expectedFounderUuid").value(PAGE_FOUNDER.toString()));
    }

    @Test
    void verify_missingCode_400() throws Exception {
        // Bean Validation @NotBlank should reject the blank code before the service runs.
        String body = String.format(
                "{\"verificationCode\":\"\",\"founderAvatarUuid\":\"%s\"}", FOUNDER_AVATAR);
        mockMvc.perform(post("/api/v1/sl/sl-group/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
