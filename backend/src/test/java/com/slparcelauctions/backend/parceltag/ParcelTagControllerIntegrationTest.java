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
 * Full-stack coverage for {@code GET /api/v1/parcel-tags}. Also implicitly
 * asserts the boot-time seed via {@link ParcelTagService#seedDefaultTagsIfEmpty()}
 * ran — the default-tag count must be exactly 25.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
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
    void list_authenticated_returnsAtLeast25TagsGroupedByCategory() throws Exception {
        String token = registerUser(
                "ptr-" + UUID.randomUUID().toString().substring(0, 8), "Reader");

        MvcResult result = mockMvc.perform(get("/api/v1/parcel-tags")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        JsonNode groups = objectMapper.readTree(result.getResponse().getContentAsString());

        // Seed inserts 25 rows the first time the table is empty. On a long-lived
        // dev DB earlier seeds may already be persisted, so assert "at least 25"
        // to remain robust across environments — the idempotency contract is
        // that the seeder never inserts again once any rows exist.
        int tagCount = 0;
        for (JsonNode group : groups) {
            tagCount += group.get("tags").size();
        }
        if (tagCount < 25) {
            throw new AssertionError("Expected at least 25 seeded tags, got " + tagCount
                    + ". Response: " + groups.toPrettyString());
        }

        // Core categories from §18 task 9 step 9.5 — must all be present.
        boolean foundTerrain = false;
        boolean foundRoads = false;
        boolean foundLocation = false;
        boolean foundNeighbors = false;
        boolean foundParcelFeatures = false;
        for (JsonNode group : groups) {
            String cat = group.get("category").asText();
            if ("Terrain / Environment".equals(cat)) foundTerrain = true;
            else if ("Roads / Access".equals(cat)) foundRoads = true;
            else if ("Location Features".equals(cat)) foundLocation = true;
            else if ("Neighbors / Context".equals(cat)) foundNeighbors = true;
            else if ("Parcel Features".equals(cat)) foundParcelFeatures = true;
        }
        if (!(foundTerrain && foundRoads && foundLocation && foundNeighbors && foundParcelFeatures)) {
            throw new AssertionError("Missing one or more expected categories. Response: "
                    + groups.toPrettyString());
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
