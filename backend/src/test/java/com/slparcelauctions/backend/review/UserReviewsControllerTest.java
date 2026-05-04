package com.slparcelauctions.backend.review;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;
import com.slparcelauctions.backend.review.dto.PendingReviewDto;
import com.slparcelauctions.backend.review.dto.ReviewDto;
import com.slparcelauctions.backend.review.exception.ReviewExceptionHandler;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice tests for {@link UserReviewsController}. Filters off (the
 * security filter chain is exercised in {@code SecurityConfigTest}).
 */
@WebMvcTest(controllers = UserReviewsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, ReviewExceptionHandler.class})
class UserReviewsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ReviewService reviewService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private JwtService jwtService;

    private ReviewDto sampleDto() {
        return new ReviewDto(
                1_234L, 555L, "Lakefront",
                "/api/v1/auctions/555/photos/1/bytes",
                1L, "Viewer", "/api/v1/users/1/avatar/256",
                10L, ReviewedRole.SELLER,
                5, "Great", true, false,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void listForUser_publicGet_returnsPagedResponse_shape() throws Exception {
        Page<ReviewDto> pageResult = new PageImpl<>(
                List.of(sampleDto()), PageRequest.of(0, 10), 1);
        when(reviewService.listForUser(eq(10L), eq(ReviewedRole.SELLER), any(Pageable.class)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/users/10/reviews?role=SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").value(1_234))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void listForUser_clampsPageSizeAt50() throws Exception {
        Page<ReviewDto> pageResult = new PageImpl<>(
                List.of(), PageRequest.of(0, 50), 0);
        when(reviewService.listForUser(eq(10L), eq(ReviewedRole.BUYER), any(Pageable.class)))
                .thenReturn(pageResult);

        // Size 9999 request → clamped to 50 (and so forwarded to service).
        mockMvc.perform(get("/api/v1/users/10/reviews?role=BUYER&size=9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void listPending_returnsListOfPendingReviewDto() throws Exception {
        User caller = User.builder().id(1L).email("test@example.com").passwordHash("x").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(caller));

        PendingReviewDto pending = new PendingReviewDto(
                555L, "Lakefront",
                "/api/v1/auctions/555/photos/1/bytes",
                10L, "Sally", "/api/v1/users/10/avatar/256",
                OffsetDateTime.now().minusDays(1),
                OffsetDateTime.now().plusDays(13),
                312L,
                ReviewedRole.BUYER);
        when(reviewService.listPendingForCaller(any(User.class)))
                .thenReturn(List.of(pending));

        mockMvc.perform(get("/api/v1/users/me/pending-reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].auctionId").value(555))
                .andExpect(jsonPath("$[0].counterpartyDisplayName").value("Sally"))
                .andExpect(jsonPath("$[0].hoursRemaining").value(312));
    }
}
