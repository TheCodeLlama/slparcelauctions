package com.slparcelauctions.backend.realty.slgroup;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SlGroupRegistrationExpiryTaskTest {

    @Mock RealtyGroupSlGroupRepository repo;

    @Spy
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    SlGroupRegistrationExpiryTask task;

    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);

    private RealtyGroupSlGroup pendingRow(String code) {
        return RealtyGroupSlGroup.builder()
                .realtyGroupId(42L)
                .slGroupUuid(UUID.randomUUID())
                .verified(false)
                .verificationCode(code)
                .build();
    }

    @Test
    void runScheduled_deletesExpiredPendingRows() {
        RealtyGroupSlGroup r1 = pendingRow("SLPA-CODE1");
        RealtyGroupSlGroup r2 = pendingRow("SLPA-CODE2");

        ArgumentCaptor<OffsetDateTime> nowCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        when(repo.findExpiredPending(nowCaptor.capture())).thenReturn(List.of(r1, r2));

        task.runScheduled();

        assertThat(nowCaptor.getValue()).isEqualTo(NOW);
        verify(repo).delete(r1);
        verify(repo).delete(r2);
        verify(repo, times(2)).delete(any(RealtyGroupSlGroup.class));
    }

    @Test
    void runScheduled_noExpiredRows_doesNothing() {
        when(repo.findExpiredPending(any(OffsetDateTime.class))).thenReturn(List.of());

        task.runScheduled();

        verify(repo, never()).delete(any(RealtyGroupSlGroup.class));
    }
}
