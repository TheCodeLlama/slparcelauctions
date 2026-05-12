package com.slparcelauctions.backend.realty.slgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties.SlGroupReverify;

/**
 * Unit tests for {@link SlGroupReverifyTask}. Mockito-driven; fixed clock for
 * determinism. Sub-project F spec §13.1.
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>The task computes the cadence threshold from {@code now - reverifyCadenceDays}
 *       and hands it to {@code findDueForReverify(...)}.</li>
 *   <li>The repository query (exercised in a separate repository test) is responsible
 *       for filtering out unverified rows, unregistered rows, and rows whose
 *       {@code last_revalidated_at} is recent enough; the task simply trusts the result
 *       set and calls {@code reverifyService.recheck(...)} per row.</li>
 *   <li>A per-row failure does not abort the sweep — the next row is still recheck'd.</li>
 *   <li>An empty result set is a no-op (no recheck calls).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SlGroupReverifyTaskTest {

    @Mock RealtyGroupSlGroupRepository repo;
    @Mock SlGroupReverifyService reverifyService;

    @Spy
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);

    private final RealtyGroupModerationProperties props = new RealtyGroupModerationProperties();

    private SlGroupReverifyTask task;

    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        SlGroupReverify slGroup = new SlGroupReverify();
        slGroup.setReverifyCadenceDays(30);
        props.setSlGroup(slGroup);
        task = new SlGroupReverifyTask(repo, reverifyService, props, clock);
    }

    private RealtyGroupSlGroup verifiedRow(Long id) {
        RealtyGroupSlGroup row = RealtyGroupSlGroup.builder()
                .realtyGroupId(42L)
                .slGroupUuid(UUID.randomUUID())
                .verified(true)
                .build();
        setId(row, id);
        return row;
    }

    /** Reflection helper: set the inherited Long id on a BaseEntity subclass. */
    private static void setId(Object entity, Long id) {
        try {
            java.lang.reflect.Field f = findIdField(entity.getClass());
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static java.lang.reflect.Field findIdField(Class<?> c) throws NoSuchFieldException {
        Class<?> cur = c;
        while (cur != null) {
            try {
                return cur.getDeclaredField("id");
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException("id field not found in " + c);
    }

    @Test
    void runOnce_picksRowsDueForRevalidationBasedOnCadence() {
        when(repo.findDueForReverify(any(OffsetDateTime.class))).thenReturn(List.of());

        task.runOnce();

        ArgumentCaptor<OffsetDateTime> thresholdCaptor =
                ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).findDueForReverify(thresholdCaptor.capture());
        // 30-day cadence: threshold = now - 30 days.
        assertThat(thresholdCaptor.getValue()).isEqualTo(NOW.minusDays(30));
    }

    @Test
    void runOnce_skipsRowsRevalidatedRecentlyEnough() {
        // The repository query is what filters out recent rows; the task simply
        // forwards whatever the repo returns. With an empty result set (the repo's
        // way of saying "nothing recent enough is due"), the task should do nothing.
        when(repo.findDueForReverify(any(OffsetDateTime.class))).thenReturn(List.of());

        task.runOnce();

        verifyNoInteractions(reverifyService);
    }

    @Test
    void runOnce_skipsUnverifiedRows() {
        // Unverified rows are filtered out by the repository query (verified = true
        // predicate). The task observes this by never seeing them in the result list
        // — so with an empty list, no recheck call happens.
        when(repo.findDueForReverify(any(OffsetDateTime.class))).thenReturn(List.of());

        task.runOnce();

        verify(reverifyService, never()).recheck(any(Long.class));
    }

    @Test
    void runOnce_callsRecheckPerEligibleRow() {
        RealtyGroupSlGroup r1 = verifiedRow(101L);
        RealtyGroupSlGroup r2 = verifiedRow(102L);
        RealtyGroupSlGroup r3 = verifiedRow(103L);
        when(repo.findDueForReverify(any(OffsetDateTime.class)))
                .thenReturn(List.of(r1, r2, r3));

        task.runOnce();

        verify(reverifyService).recheck(101L);
        verify(reverifyService).recheck(102L);
        verify(reverifyService).recheck(103L);
        verify(reverifyService, times(3)).recheck(any(Long.class));
    }

    @Test
    void runOnce_perRowFailureDoesNotAbortSweep() {
        RealtyGroupSlGroup r1 = verifiedRow(201L);
        RealtyGroupSlGroup r2 = verifiedRow(202L);
        RealtyGroupSlGroup r3 = verifiedRow(203L);
        when(repo.findDueForReverify(any(OffsetDateTime.class)))
                .thenReturn(List.of(r1, r2, r3));
        doThrow(new RuntimeException("boom")).when(reverifyService).recheck(202L);

        task.runOnce();

        verify(reverifyService).recheck(201L);
        verify(reverifyService).recheck(202L);
        // The sweep continues past the failed row.
        verify(reverifyService).recheck(203L);
    }

    @Test
    void runOnce_isNoOpWhenNoRowsDue() {
        when(repo.findDueForReverify(any(OffsetDateTime.class))).thenReturn(List.of());

        task.runOnce();

        verifyNoInteractions(reverifyService);
    }
}
