package com.slparcelauctions.backend.realty.slgroup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties;

/**
 * Sub-project G §9.2 — asserts that the configured
 * {@code slpa.realty.sl-group.reverify-batch-size} value flows through to the
 * {@link RealtyGroupSlGroupRepository#findDueForReverify(OffsetDateTime, Pageable)}
 * call as the {@code Pageable.pageSize}. The default ({@link Integer#MAX_VALUE})
 * is exercised by the existing {@link SlGroupReverifyTaskTest}; this test pins
 * the path where an operator dials the cap down.
 */
@ExtendWith(MockitoExtension.class)
class SlGroupReverifyTaskBatchSizeTest {

    @Mock RealtyGroupSlGroupRepository repo;
    @Mock SlGroupReverifyService reverifyService;

    @Test
    void run_once_passes_configured_batch_size_to_repo() {
        RealtyGroupModerationProperties props = new RealtyGroupModerationProperties();
        props.getSlGroup().setReverifyBatchSize(50);

        Clock fixed = Clock.fixed(
                java.time.Instant.parse("2026-05-12T00:00:00Z"),
                ZoneOffset.UTC);
        SlGroupReverifyTask task = new SlGroupReverifyTask(repo, reverifyService, props, fixed);

        when(repo.findDueForReverify(any(OffsetDateTime.class), any())).thenReturn(List.of());

        task.runOnce();

        ArgumentCaptor<Pageable> pageableCap = ArgumentCaptor.forClass(Pageable.class);
        verify(repo, times(1)).findDueForReverify(any(OffsetDateTime.class), pageableCap.capture());
        Pageable p = pageableCap.getValue();
        org.assertj.core.api.Assertions.assertThat(p.getPageSize()).isEqualTo(50);
        org.assertj.core.api.Assertions.assertThat(p.getPageNumber()).isEqualTo(0);
    }
}
