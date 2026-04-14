package com.slparcelauctions.backend.sl;

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
 * Direct unit test for {@link SlExceptionHandler#handleDataIntegrity}. Mocks a
 * {@link DataIntegrityViolationException} wrapping Hibernate's
 * {@link ConstraintViolationException} for both the known
 * ({@code users_sl_avatar_uuid_key}) constraint and an unknown one, and verifies
 * the handler maps correctly. Covers the race-path branches of
 * {@code SlExceptionHandler} that {@link SlVerificationFlowIntegrationTest}
 * cannot reach because the service-level pre-check trips before save in a
 * single-threaded {@code @Transactional} test.
 */
class SlExceptionHandlerRacePathTest {

    private SlExceptionHandler handler;
    private HttpServletRequest req;

    @BeforeEach
    void setup() {
        handler = new SlExceptionHandler();
        MockHttpServletRequest mockReq = new MockHttpServletRequest();
        mockReq.setRequestURI("/api/v1/sl/verify");
        req = mockReq;
    }

    @Test
    void knownAvatarUuidConstraint_returnsAvatarAlreadyLinkedProblem() {
        DataIntegrityViolationException dataEx = wrap("users_sl_avatar_uuid_key");

        ProblemDetail pd = handler.handleDataIntegrity(dataEx, req);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getType().toString())
                .isEqualTo("https://slpa.example/problems/sl/avatar-already-linked");
        assertThat(pd.getTitle()).isEqualTo("Avatar already linked");
        assertThat(pd.getProperties()).containsEntry("code", "SL_AVATAR_ALREADY_LINKED");
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
