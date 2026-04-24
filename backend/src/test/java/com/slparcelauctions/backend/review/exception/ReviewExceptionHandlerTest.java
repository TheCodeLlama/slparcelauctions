package com.slparcelauctions.backend.review.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Direct unit test for {@link ReviewExceptionHandler#handleDataIntegrity}.
 * Mocks a {@link DataIntegrityViolationException} wrapping Hibernate's
 * {@link ConstraintViolationException} for both the known
 * {@code uq_reviews_auction_reviewer} constraint and an unknown one, and
 * verifies the handler maps correctly. Covers the race-path branch that
 * {@code ReviewServiceSubmitTest} cannot reach because the service-level
 * pre-check trips before save in a single-threaded mocked test.
 */
class ReviewExceptionHandlerTest {

    private ReviewExceptionHandler handler;
    private HttpServletRequest req;

    @BeforeEach
    void setup() {
        handler = new ReviewExceptionHandler();
        MockHttpServletRequest mockReq = new MockHttpServletRequest();
        mockReq.setRequestURI("/api/v1/auctions/42/review");
        req = mockReq;
    }

    @Test
    void knownReviewsConstraint_returnsAlreadySubmittedProblem() {
        DataIntegrityViolationException dataEx = wrap("uq_reviews_auction_reviewer");

        ProblemDetail pd = handler.handleDataIntegrity(dataEx, req);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getType().toString())
                .isEqualTo("https://slpa.example/problems/review/already-submitted");
        assertThat(pd.getTitle()).isEqualTo("Review already submitted");
        assertThat(pd.getProperties()).containsEntry("code", "REVIEW_ALREADY_SUBMITTED");
    }

    @Test
    void knownFlagConstraint_returnsFlagAlreadyExistsProblem() {
        // URI needs to look like the flag endpoint so the id extractor resolves.
        MockHttpServletRequest mockReq = new MockHttpServletRequest();
        mockReq.setRequestURI("/api/v1/reviews/42/flag");
        HttpServletRequest flagReq = mockReq;

        DataIntegrityViolationException dataEx = wrap("uq_review_flags_review_flagger");

        ProblemDetail pd = handler.handleDataIntegrity(dataEx, flagReq);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getType().toString())
                .isEqualTo("https://slpa.example/problems/review/flag-already-exists");
        assertThat(pd.getTitle()).isEqualTo("Review flag already exists");
        assertThat(pd.getProperties()).containsEntry("code", "REVIEW_FLAG_ALREADY_EXISTS");
    }

    @Test
    void knownResponseConstraint_returnsResponseAlreadyExistsProblem() {
        MockHttpServletRequest mockReq = new MockHttpServletRequest();
        mockReq.setRequestURI("/api/v1/reviews/42/respond");
        HttpServletRequest respondReq = mockReq;

        DataIntegrityViolationException dataEx = wrap("review_responses_review_id_key");

        ProblemDetail pd = handler.handleDataIntegrity(dataEx, respondReq);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getType().toString())
                .isEqualTo("https://slpa.example/problems/review/response-already-exists");
        assertThat(pd.getTitle()).isEqualTo("Review response already exists");
        assertThat(pd.getProperties()).containsEntry("code", "REVIEW_RESPONSE_ALREADY_EXISTS");
    }

    @Test
    void knownResponseConstraint_conventionalName_mapsTo409() {
        MockHttpServletRequest mockReq = new MockHttpServletRequest();
        mockReq.setRequestURI("/api/v1/reviews/42/respond");
        HttpServletRequest respondReq = mockReq;

        DataIntegrityViolationException dataEx = wrap("uq_review_responses_review");

        ProblemDetail pd = handler.handleDataIntegrity(dataEx, respondReq);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("code", "REVIEW_RESPONSE_ALREADY_EXISTS");
    }

    @Test
    void unknownConstraint_rethrowsOriginalException() {
        DataIntegrityViolationException dataEx = wrap("some_other_constraint");

        assertThatThrownBy(() -> handler.handleDataIntegrity(dataEx, req))
                .isSameAs(dataEx);
    }

    @Test
    void nullConstraintChain_rethrowsOriginalException() {
        DataIntegrityViolationException dataEx =
                new DataIntegrityViolationException("no cause", new RuntimeException());

        assertThatThrownBy(() -> handler.handleDataIntegrity(dataEx, req))
                .isSameAs(dataEx);
    }

    private DataIntegrityViolationException wrap(String constraintName) {
        SQLException sql = new SQLException("pg exception");
        ConstraintViolationException hib =
                new ConstraintViolationException("dup", sql, constraintName);
        return new DataIntegrityViolationException("wrapped", hib);
    }
}
