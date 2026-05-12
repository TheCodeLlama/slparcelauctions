package com.slparcelauctions.backend.parceltag;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full-stack coverage for {@code GET /api/v1/parcel-tags}. There is no
 * first-boot seed — admins create the catalogue from scratch via
 * {@code /api/v1/admin/parcel-tag-categories} and
 * {@code /api/v1/admin/parcel-tags}. The auth'd test below verifies the
 * endpoint shape and bearer-token path; an empty catalogue returns an empty
 * array, which is correct.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class ParcelTagControllerIntegrationTest {

    @Autowired MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void list_unauthenticated_returnsPublicTagCatalogue() throws Exception {
        // GET /api/v1/parcel-tags is intentionally public so anonymous
        // /browse callers can render tag filters without first
        // authenticating. SecurityConfig matcher at line 123 flips this
        // ahead of the /api/v1/** authenticated catch-all.
        mockMvc.perform(get("/api/v1/parcel-tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void list_authenticated_returnsArrayGroupedByCategory() throws Exception {
        // Exercises the bearer-token path through the public endpoint. The
        // response is an array of {category, tags[]} groups; if the catalogue
        // is empty (no admin-seeded data), the array is empty, which is
        // correct per the post-V19 design (no boot-time seed).
        String token = registerUser(
                "ptr-" + UUID.randomUUID().toString().substring(0, 8), "Reader");

        MvcResult result = mockMvc.perform(get("/api/v1/parcel-tags")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        JsonNode groups = objectMapper.readTree(result.getResponse().getContentAsString());

        // If any groups are present, each must have the {category, tags[]} shape.
        for (JsonNode group : groups) {
            if (!group.has("category") || !group.has("tags") || !group.get("tags").isArray()) {
                throw new AssertionError("Malformed group: " + group.toPrettyString());
            }
        }
    }

    private String registerUser(String email, String displayName) throws Exception {
        String body = String.format(
                "{\"username\":\"%s\",\"password\":\"hunter22abc\"}",
                email, displayName);
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
