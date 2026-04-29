package com.slparcelauctions.backend.notification.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false"
})
class NotificationPreferencesControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepo;
    @Autowired JwtService jwt;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User alice;
    private User bob;
    private String aliceJwt;
    private String bobJwt;

    @BeforeEach
    void seed() {
        alice = userRepo.save(User.builder()
            .email("alice-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());
        bob = userRepo.save(User.builder()
            .email("bob-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());

        aliceJwt = jwt.issueAccessToken(new AuthPrincipal(
            alice.getId(), alice.getEmail(), alice.getTokenVersion(), Role.USER));
        bobJwt = jwt.issueAccessToken(new AuthPrincipal(
            bob.getId(), bob.getEmail(), bob.getTokenVersion(), Role.USER));
    }

    @Test
    void get_returnsClosedShapeMap() throws Exception {
        mvc.perform(get("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.slImMuted").value(false))
            .andExpect(jsonPath("$.slIm.bidding").exists())
            .andExpect(jsonPath("$.slIm.auction_result").exists())
            .andExpect(jsonPath("$.slIm.escrow").exists())
            .andExpect(jsonPath("$.slIm.listing_status").exists())
            .andExpect(jsonPath("$.slIm.reviews").exists())
            .andExpect(jsonPath("$.slIm.system").doesNotExist())
            .andExpect(jsonPath("$.slIm.realty_group").doesNotExist())
            .andExpect(jsonPath("$.slIm.marketing").doesNotExist());
    }

    @Test
    void put_happyPath_persistsAndReturnsNewState() throws Exception {
        Map<String, Object> body = Map.of(
            "slImMuted", false,
            "slIm", Map.of(
                "bidding", true,
                "auction_result", true,
                "escrow", true,
                "listing_status", false,
                "reviews", true
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.slIm.listing_status").value(false))
            .andExpect(jsonPath("$.slIm.reviews").value(true));

        // Persisted on User entity
        User updated = userRepo.findById(alice.getId()).orElseThrow();
        assertThat(updated.getNotifySlIm().get("listing_status")).isEqualTo(false);
        assertThat(updated.getNotifySlIm().get("reviews")).isEqualTo(true);
    }

    @Test
    void put_preservesUnexposedKeys() throws Exception {
        // Pre-set system=true and realty_group=false on alice.
        Map<String, Object> initial = new HashMap<>(alice.getNotifySlIm());
        initial.put("system", true);
        initial.put("realty_group", false);
        alice.setNotifySlIm(initial);
        userRepo.save(alice);

        Map<String, Object> body = Map.of(
            "slImMuted", false,
            "slIm", Map.of(
                "bidding", false,
                "auction_result", true,
                "escrow", true,
                "listing_status", true,
                "reviews", true
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());

        User updated = userRepo.findById(alice.getId()).orElseThrow();
        // Visible keys updated:
        assertThat(updated.getNotifySlIm().get("bidding")).isEqualTo(false);
        // Unexposed keys preserved:
        assertThat(updated.getNotifySlIm().get("system")).isEqualTo(true);
        assertThat(updated.getNotifySlIm().get("realty_group")).isEqualTo(false);
    }

    @Test
    void put_systemKey_returns400() throws Exception {
        Map<String, Object> body = Map.of(
            "slImMuted", false,
            "slIm", Map.of(
                "bidding", true,
                "auction_result", true,
                "escrow", true,
                "listing_status", true,
                "reviews", true,
                "system", false  // rejected
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void put_realtyGroupKey_returns400() throws Exception {
        Map<String, Object> body = Map.of(
            "slImMuted", false,
            "slIm", Map.of(
                "bidding", true,
                "auction_result", true,
                "escrow", true,
                "listing_status", true,
                "reviews", true,
                "realty_group", true
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void put_missingKey_returns400() throws Exception {
        Map<String, Object> body = Map.of(
            "slImMuted", false,
            "slIm", Map.of(
                "bidding", true,
                "auction_result", true,
                "escrow", true,
                "listing_status", true
                // missing "reviews"
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void put_nonBooleanValue_returns400() throws Exception {
        // Send raw JSON with a string where a boolean is expected.
        String rawBody = """
            {
              "slImMuted": false,
              "slIm": {
                "bidding": "true",
                "auction_result": true,
                "escrow": true,
                "listing_status": true,
                "reviews": true
              }
            }""";

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody))
            .andExpect(status().isBadRequest());
    }

    @Test
    void get_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/users/me/notification-preferences"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void put_unauthenticated_returns401() throws Exception {
        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void crossUser_aliceCannotAccessBobsPreferences() throws Exception {
        // Bob's PUT updates Bob's prefs, not Alice's. Same endpoint path; the
        // principal is the discriminator.
        Map<String, Object> body = Map.of(
            "slImMuted", true,
            "slIm", Map.of(
                "bidding", false, "auction_result", false,
                "escrow", false, "listing_status", false, "reviews", false
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + bobJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());

        User aliceFresh = userRepo.findById(alice.getId()).orElseThrow();
        User bobFresh = userRepo.findById(bob.getId()).orElseThrow();
        assertThat(aliceFresh.getNotifySlImMuted()).isFalse();  // unchanged
        assertThat(bobFresh.getNotifySlImMuted()).isTrue();      // updated
    }
}
