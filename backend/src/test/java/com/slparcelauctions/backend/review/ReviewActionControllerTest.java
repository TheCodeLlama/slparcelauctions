package com.slparcelauctions.backend.review;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;
import com.slparcelauctions.backend.review.dto.ReviewFlagRequest;
import com.slparcelauctions.backend.review.dto.ReviewResponseDto;
import com.slparcelauctions.backend.review.dto.ReviewResponseSubmitRequest;
import com.slparcelauctions.backend.review.exception.ReviewExceptionHandler;
import com.slparcelauctions.backend.review.exception.ReviewFlagAlreadyExistsException;
import com.slparcelauctions.backend.review.exception.ReviewNotFoundException;
import com.slparcelauctions.backend.review.exception.ReviewResponseAlreadyExistsException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice tests for {@link ReviewActionController}. Filters are off (the
 * SecurityConfig matchers are covered in {@code SecurityConfigTest}), so
 * every test stages an {@link com.slparcelauctions.backend.auth.AuthPrincipal}
 * into the context via {@link WithMockAuthPrincipal} and exercises the
 * controller → service boundary, the validation errors, and the exception
 * → ProblemDetail mappings pulled in by
 * {@link ReviewExceptionHandler}.
 */
@WebMvcTest(controllers = ReviewActionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, ReviewExceptionHandler.class})
class ReviewActionControllerTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private ReviewService reviewService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private JwtService jwtService;

    private User mockCaller() {
        User caller = User.builder().email("test@example.com").passwordHash("x").build();
        caller.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(caller));
        return caller;
    }

    // ---------- POST /reviews/{id}/respond ----------

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void respond_returns201_withDto() throws Exception {
        mockCaller();
        ReviewResponseDto dto = new ReviewResponseDto(9_001L, "Thanks!",
                OffsetDateTime.parse("2026-05-01T10:00:00Z"));
        when(reviewService.respondTo(eq(1_234L), any(User.class),
                any(ReviewResponseSubmitRequest.class))).thenReturn(dto);

        ReviewResponseSubmitRequest req = new ReviewResponseSubmitRequest("Thanks!");
        mockMvc.perform(post("/api/v1/reviews/1234/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9_001))
                .andExpect(jsonPath("$.text").value("Thanks!"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void respond_returns400_whenTextBlank() throws Exception {
        ReviewResponseSubmitRequest req = new ReviewResponseSubmitRequest("   ");
        mockMvc.perform(post("/api/v1/reviews/1234/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.text").exists());
        verifyNoInteractions(reviewService);
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void respond_returns400_whenTextMissing() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/1234/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.text").exists());
        verifyNoInteractions(reviewService);
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void respond_returns400_whenTextTooLong() throws Exception {
        String tooLong = "a".repeat(501);
        ReviewResponseSubmitRequest req = new ReviewResponseSubmitRequest(tooLong);
        mockMvc.perform(post("/api/v1/reviews/1234/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.text").exists());
        verifyNoInteractions(reviewService);
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void respond_returns403_whenNotReviewee() throws Exception {
        mockCaller();
        when(reviewService.respondTo(eq(1_234L), any(User.class),
                any(ReviewResponseSubmitRequest.class)))
                .thenThrow(new AccessDeniedException("Only the reviewee can respond to a review."));

        ReviewResponseSubmitRequest req = new ReviewResponseSubmitRequest("Hi");
        mockMvc.perform(post("/api/v1/reviews/1234/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void respond_returns404_whenReviewMissing() throws Exception {
        mockCaller();
        when(reviewService.respondTo(eq(1_234L), any(User.class),
                any(ReviewResponseSubmitRequest.class)))
                .thenThrow(new ReviewNotFoundException(1_234L));

        ReviewResponseSubmitRequest req = new ReviewResponseSubmitRequest("Hi");
        mockMvc.perform(post("/api/v1/reviews/1234/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void respond_returns409_whenDuplicate() throws Exception {
        mockCaller();
        when(reviewService.respondTo(eq(1_234L), any(User.class),
                any(ReviewResponseSubmitRequest.class)))
                .thenThrow(new ReviewResponseAlreadyExistsException(1_234L));

        ReviewResponseSubmitRequest req = new ReviewResponseSubmitRequest("Thanks!");
        mockMvc.perform(post("/api/v1/reviews/1234/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_RESPONSE_ALREADY_EXISTS"));
    }

    // ---------- POST /reviews/{id}/flag ----------

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void flag_returns204_onFirstFlag() throws Exception {
        mockCaller();
        doNothing().when(reviewService).flag(eq(1_234L), any(User.class),
                any(ReviewFlagRequest.class));

        ReviewFlagRequest req = new ReviewFlagRequest(ReviewFlagReason.SPAM, null);
        mockMvc.perform(post("/api/v1/reviews/1234/flag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(reviewService).flag(eq(1_234L), any(User.class),
                any(ReviewFlagRequest.class));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void flag_returns400_whenReasonMissing() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/1234/flag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        verifyNoInteractions(reviewService);
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void flag_returns400_whenOtherWithoutElaboration() throws Exception {
        ReviewFlagRequest req = new ReviewFlagRequest(ReviewFlagReason.OTHER, null);
        mockMvc.perform(post("/api/v1/reviews/1234/flag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.elaboration").exists());
        verifyNoInteractions(reviewService);
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void flag_returns400_whenOtherWithBlankElaboration() throws Exception {
        ReviewFlagRequest req = new ReviewFlagRequest(ReviewFlagReason.OTHER, "   ");
        mockMvc.perform(post("/api/v1/reviews/1234/flag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.elaboration").exists());
        verifyNoInteractions(reviewService);
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void flag_returns400_whenElaborationTooLong() throws Exception {
        String tooLong = "a".repeat(501);
        ReviewFlagRequest req = new ReviewFlagRequest(ReviewFlagReason.OTHER, tooLong);
        mockMvc.perform(post("/api/v1/reviews/1234/flag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.elaboration").exists());
        verifyNoInteractions(reviewService);
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void flag_returns204_whenOtherWithElaboration() throws Exception {
        mockCaller();
        doNothing().when(reviewService).flag(eq(1_234L), any(User.class),
                any(ReviewFlagRequest.class));

        ReviewFlagRequest req = new ReviewFlagRequest(ReviewFlagReason.OTHER,
                "Contains a scam link");
        mockMvc.perform(post("/api/v1/reviews/1234/flag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void flag_returns403_whenCallerIsReviewer() throws Exception {
        mockCaller();
        doThrow(new AccessDeniedException("You cannot flag your own review."))
                .when(reviewService).flag(eq(1_234L), any(User.class),
                        any(ReviewFlagRequest.class));

        ReviewFlagRequest req = new ReviewFlagRequest(ReviewFlagReason.SPAM, null);
        mockMvc.perform(post("/api/v1/reviews/1234/flag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void flag_returns404_whenReviewMissing() throws Exception {
        mockCaller();
        doThrow(new ReviewNotFoundException(1_234L))
                .when(reviewService).flag(eq(1_234L), any(User.class),
                        any(ReviewFlagRequest.class));

        ReviewFlagRequest req = new ReviewFlagRequest(ReviewFlagReason.SPAM, null);
        mockMvc.perform(post("/api/v1/reviews/1234/flag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void flag_returns409_whenDuplicate() throws Exception {
        mockCaller();
        doThrow(new ReviewFlagAlreadyExistsException(1_234L))
                .when(reviewService).flag(eq(1_234L), any(User.class),
                        any(ReviewFlagRequest.class));

        ReviewFlagRequest req = new ReviewFlagRequest(ReviewFlagReason.SPAM, null);
        mockMvc.perform(post("/api/v1/reviews/1234/flag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_FLAG_ALREADY_EXISTS"));
    }
}
