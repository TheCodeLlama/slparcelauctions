package com.slparcelauctions.backend.realty.browse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class RealtyGroupBrowseControllerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void anonymousCallerGets200WithEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/realty-groups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.totalElements").exists());
    }

    @Test
    void unknownSortReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/realty-groups").param("sort", "BOGUS"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void sizeAboveMaxIsClampedTo60() throws Exception {
        mockMvc.perform(get("/api/v1/realty-groups").param("size", "999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.size").value(60));
    }
}
