package com.slparcelauctions.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.JwtAuthenticationEntryPoint;
import com.slparcelauctions.backend.auth.JwtAuthenticationFilter;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.bot.BotSharedSecretAuthorizer;

import static org.hamcrest.Matchers.isA;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the public listing-fee config endpoint. Epic 03 sub-spec 2 §7.6
 * requires this to be reachable without authentication so the browse page can
 * render the platform cost badge without a JWT.
 */
@WebMvcTest(PublicConfigController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@TestPropertySource(properties = "slpa.listing-fee.amount-lindens=150")
class PublicConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    // SecurityConfig depends on BotSharedSecretAuthorizer (Epic 06 Task 3).
    @MockitoBean
    private BotSharedSecretAuthorizer botSharedSecretAuthorizer;

    @Test
    void listingFee_isPublic_andReturnsConfiguredAmount() throws Exception {
        mockMvc.perform(get("/api/v1/config/listing-fee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountLindens").value(150))
                .andExpect(jsonPath("$.amountLindens").value(isA(Number.class)));
    }
}
