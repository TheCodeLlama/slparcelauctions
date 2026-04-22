package com.slparcelauctions.backend.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.bot.exception.BotEscrowTerminalException;
import com.slparcelauctions.backend.bot.exception.BotTaskNotClaimedException;
import com.slparcelauctions.backend.bot.exception.BotTaskNotFoundException;
import com.slparcelauctions.backend.bot.exception.BotTaskWrongTypeException;
import com.slparcelauctions.backend.escrow.EscrowState;

/**
 * Slice coverage for {@code POST /api/v1/bot/tasks/{id}/monitor}. Asserts:
 * <ul>
 *   <li>success — 200 with the updated task payload from {@code recordMonitorResult}.</li>
 *   <li>unknown task — {@link BotTaskNotFoundException} surfaces as 404 with
 *       {@code code=BOT_TASK_NOT_FOUND} via {@link BotTaskExceptionHandler}.</li>
 *   <li>missing outcome — Bean Validation rejects the request with 400.</li>
 *   <li>unclaimed task — {@link BotTaskNotClaimedException} surfaces as 409
 *       with {@code code=BOT_TASK_NOT_CLAIMED}.</li>
 *   <li>wrong-type task — {@link BotTaskWrongTypeException} surfaces as 409
 *       with {@code code=BOT_TASK_WRONG_TYPE}.</li>
 *   <li>terminal escrow — {@link BotEscrowTerminalException} surfaces as 409
 *       with {@code code=BOT_ESCROW_TERMINAL}.</li>
 * </ul>
 */
@WebMvcTest(controllers = BotTaskController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(BotTaskExceptionHandler.class)
class BotTaskControllerMonitorSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BotTaskService service;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void monitor_success_returns200() throws Exception {
        BotTask task = BotTask.builder()
                .id(9L)
                .taskType(BotTaskType.MONITOR_AUCTION)
                .status(BotTaskStatus.PENDING)
                .auction(Auction.builder().id(42L).build())
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .createdAt(OffsetDateTime.now())
                .build();
        when(service.recordMonitorResult(eq(9L), any())).thenReturn(task);

        mvc.perform(post("/api/v1/bot/tasks/9/monitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "outcome": "ALL_GOOD"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType").value("MONITOR_AUCTION"));
    }

    @Test
    void monitor_unknownTask_returns404() throws Exception {
        when(service.recordMonitorResult(eq(99L), any()))
                .thenThrow(new BotTaskNotFoundException(99L));

        mvc.perform(post("/api/v1/bot/tasks/99/monitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"ALL_GOOD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOT_TASK_NOT_FOUND"));
    }

    @Test
    void monitor_missingOutcome_returns400() throws Exception {
        mvc.perform(post("/api/v1/bot/tasks/9/monitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void monitor_notClaimed_returns409() throws Exception {
        when(service.recordMonitorResult(eq(9L), any()))
                .thenThrow(new BotTaskNotClaimedException(9L, BotTaskStatus.PENDING));

        mvc.perform(post("/api/v1/bot/tasks/9/monitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"ALL_GOOD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOT_TASK_NOT_CLAIMED"));
    }

    @Test
    void monitor_wrongType_returns409() throws Exception {
        when(service.recordMonitorResult(eq(9L), any()))
                .thenThrow(new BotTaskWrongTypeException(
                        9L, BotTaskType.VERIFY, BotTaskType.MONITOR_AUCTION));

        mvc.perform(post("/api/v1/bot/tasks/9/monitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"ALL_GOOD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOT_TASK_WRONG_TYPE"));
    }

    @Test
    void monitor_escrowTerminal_returns409() throws Exception {
        when(service.recordMonitorResult(eq(9L), any()))
                .thenThrow(new BotEscrowTerminalException(77L, EscrowState.COMPLETED));

        mvc.perform(post("/api/v1/bot/tasks/9/monitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"ALL_GOOD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOT_ESCROW_TERMINAL"));
    }
}
