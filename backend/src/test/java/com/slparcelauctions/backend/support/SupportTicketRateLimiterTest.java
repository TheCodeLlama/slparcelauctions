package com.slparcelauctions.backend.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SupportTicketRateLimiterTest {

    SupportTicketRepository repo;
    SupportTicketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        repo = mock(SupportTicketRepository.class);
        limiter = new SupportTicketRateLimiter(repo);
        ReflectionTestUtils.setField(limiter, "ticketsPerHour", 5);
    }

    @Test
    void under_cap_does_not_throw() {
        when(repo.countByUserIdAndCreatedAtAfter(anyLong(), any())).thenReturn(4L);
        limiter.assertCanOpenNewTicket(1L);
    }

    @Test
    void at_cap_throws() {
        when(repo.countByUserIdAndCreatedAtAfter(anyLong(), any())).thenReturn(5L);
        assertThatThrownBy(() -> limiter.assertCanOpenNewTicket(1L))
                .isInstanceOf(SupportTicketException.class)
                .extracting("code").isEqualTo(SupportTicketError.RATE_LIMITED);
    }
}
