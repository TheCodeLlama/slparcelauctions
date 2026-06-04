package com.slparcelauctions.backend.promotion;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.promotion.dto.FeaturedBoardPayloadDto;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class InWorldFeaturedBoardControllerSliceTest {

    @Autowired MockMvc mvc;
    @MockitoBean BoardContentResolver resolver;

    @Test
    void anonymous_can_GET_board() throws Exception {
        when(resolver.resolve(1)).thenReturn(new FeaturedBoardPayloadDto(
                1, 30, List.of(), FeaturedBoardPayloadDto.Source.PLACEHOLDER));
        mvc.perform(get("/api/v1/in-world/featured-board/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardIndex").value(1));
    }

    @Test
    void invalid_board_index_400() throws Exception {
        mvc.perform(get("/api/v1/in-world/featured-board/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BOARD_INDEX"));
    }

    @Test
    void placeholder_returns_PLACEHOLDER_source() throws Exception {
        mvc.perform(get("/api/v1/in-world/board/placeholder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PLACEHOLDER"));
    }
}
