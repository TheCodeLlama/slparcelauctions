package com.slparcelauctions.backend.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auth.JwtService;

/**
 * Slice coverage for {@code POST /api/v1/bot/tasks/claim}. Verifies the HTTP
 * contract: 200 with task payload when a PENDING row is available, 204 when
 * the queue is empty, and 400 when the body fails validation.
 */
@WebMvcTest(controllers = BotTaskController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(BotTaskExceptionHandler.class)
class BotTaskControllerClaimSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BotTaskService service;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void claim_withPendingTask_returns200WithTaskPayload() throws Exception {
        UUID botUuid = UUID.randomUUID();
        Auction auction = Auction.builder().title("Test listing").id(42L).build();
        BotTask task = BotTask.builder()
                .id(7L)
                .taskType(BotTaskType.VERIFY)
                .status(BotTaskStatus.IN_PROGRESS)
                .auction(auction)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .assignedBotUuid(botUuid)
                .createdAt(OffsetDateTime.now())
                .build();
        when(service.claim(any())).thenReturn(Optional.of(task));

        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"botUuid\":\"" + botUuid + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.taskType").value("VERIFY"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void claim_withEmptyQueue_returns204() throws Exception {
        when(service.claim(any())).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"botUuid\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void claim_withMissingBotUuid_returns400() throws Exception {
        mvc.perform(post("/api/v1/bot/tasks/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
