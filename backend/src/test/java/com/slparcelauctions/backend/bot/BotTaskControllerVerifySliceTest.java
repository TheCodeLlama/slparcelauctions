package com.slparcelauctions.backend.bot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice coverage for the VERIFY callback route
 * {@code PUT /api/v1/bot/tasks/{id}/verify} (Epic 06 Task 4). The Task 4
 * deprecated {@code PUT /api/v1/bot/tasks/{id}} shim was removed in Task 12.
 */
@WebMvcTest(controllers = BotTaskController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(BotTaskExceptionHandler.class)
class BotTaskControllerVerifySliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BotTaskService service;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    @SuppressWarnings("unused")
    private UserRepository userRepository;

    @Test
    void verify_success_returns200() throws Exception {
        BotTask task = stub(5L, BotTaskStatus.COMPLETED);
        when(service.complete(eq(5L), any())).thenReturn(task);

        mvc.perform(put("/api/v1/bot/tasks/5/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "result": "SUCCESS",
                                  "authBuyerId": "00000000-0000-0000-0000-000000000099",
                                  "salePrice": 999999999,
                                  "parcelOwner": "11111111-1111-1111-1111-111111111111"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    private BotTask stub(long id, BotTaskStatus status) {
        return BotTask.builder()
                .id(id)
                .taskType(BotTaskType.VERIFY)
                .status(status)
                .auction(Auction.builder().title("Test listing").id(42L).build())
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
