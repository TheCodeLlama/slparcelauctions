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
