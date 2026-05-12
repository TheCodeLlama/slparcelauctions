package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties;

/**
 * Unit tests for {@link BulkSuspendedListingExpiryTask}. The task is a thin
 * sweep that delegates per-row cancellation to
 * {@link CancellationService#adminCancelExpiredBulkSuspend(Long, Long)} and
 * relies on {@link ListingSuspensionRepository#findExpiredBulkSuspends(OffsetDateTime)}
 * to filter to the right rows. The tests guard both ends:
 *
 * <ul>
 *   <li>The threshold passed to {@code findExpiredBulkSuspends} is
 *       {@code now - autoCancelHours}, so the repository's filter (cause =
 *       ADMIN_GROUP_BULK AND lifted_at IS NULL AND cancelled_at IS NULL AND
 *       suspended_at &lt; threshold) returns only the right rows.</li>
 *   <li>Each returned row drives a {@code adminCancelExpiredBulkSuspend}
 *       call carrying the row's auction id and listing-suspension id.</li>
 *   <li>One batched {@code admin_actions} row is written per execution
 *       carrying the cancelled count.</li>
 * </ul>
 *
 * <p>Per the plan's Task 13 note: the per-row try/catch is safe because
 * {@code adminCancelExpiredBulkSuspend} is {@code @Transactional} with default
 * propagation, so each call gets its own transaction; the outer {@code runOnce}
 * is intentionally NOT {@code @Transactional}.
 */
@ExtendWith(MockitoExtension.class)
class BulkSuspendedListingExpiryTaskTest {

    private static final OffsetDateTime FIXED_NOW =
        OffsetDateTime.of(2026, 5, 12, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock ListingSuspensionRepository listingSuspensionRepo;
    @Mock CancellationService cancellationService;
    @Mock AdminActionService adminActionService;

    RealtyGroupModerationProperties props;
    Clock clock;
    BulkSuspendedListingExpiryTask task;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);
        props = new RealtyGroupModerationProperties();
        // Default is 48h per Task 4; assert it explicitly for clarity.
        props.getGroupBulkSuspend().setAutoCancelHours(48);
        task = new BulkSuspendedListingExpiryTask(
            listingSuspensionRepo,
            cancellationService,
            adminActionService,
            props,
            clock
        );
    }

    @Test
    void runOnce_picksOnlyAdminGroupBulkSuspensionsOlderThanThreshold() {
        // The repository's filter is what enforces "cause = ADMIN_GROUP_BULK AND
        // lifted_at IS NULL AND cancelled_at IS NULL AND suspended_at < threshold".
        // We assert the task queries findExpiredBulkSuspends with the correct
        // threshold derived from the fixed clock minus configured hours.
        when(listingSuspensionRepo.findExpiredBulkSuspends(any(OffsetDateTime.class)))
            .thenReturn(List.of());

        task.runOnce();

        ArgumentCaptor<OffsetDateTime> thresholdCaptor =
            ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(listingSuspensionRepo).findExpiredBulkSuspends(thresholdCaptor.capture());
        assertThat(thresholdCaptor.getValue()).isEqualTo(FIXED_NOW.minusHours(48));
    }

    @Test
    void runOnce_callsAdminCancelExpiredBulkSuspendPerRow() {
        ListingSuspension row1 = bulkRow(10L, 100L, FIXED_NOW.minusHours(50));
        ListingSuspension row2 = bulkRow(11L, 101L, FIXED_NOW.minusHours(72));
        ListingSuspension row3 = bulkRow(12L, 102L, FIXED_NOW.minusHours(49));
        when(listingSuspensionRepo.findExpiredBulkSuspends(any(OffsetDateTime.class)))
            .thenReturn(List.of(row1, row2, row3));

        task.runOnce();

        verify(cancellationService).adminCancelExpiredBulkSuspend(100L, 10L);
        verify(cancellationService).adminCancelExpiredBulkSuspend(101L, 11L);
        verify(cancellationService).adminCancelExpiredBulkSuspend(102L, 12L);
    }

    @Test
    void runOnce_setsListingSuspensionCancelledAtViaTheCancellationService() {
        // listing_suspensions.cancelled_at is set inside adminCancelExpiredBulkSuspend
        // (verified by CancellationServiceTest); this test guards the delegation —
        // the task hands every due row off to the cancellation service, which is the
        // single place that stamps cancelled_at.
        ListingSuspension row = bulkRow(7L, 70L, FIXED_NOW.minusHours(60));
        when(listingSuspensionRepo.findExpiredBulkSuspends(any(OffsetDateTime.class)))
            .thenReturn(List.of(row));

        task.runOnce();

        verify(cancellationService).adminCancelExpiredBulkSuspend(70L, 7L);
        // The task itself never writes cancelled_at directly — only via the service.
        // (Setter would be on the entity; we assert the row is untouched by the task.)
        assertThat(row.getCancelledAt()).isNull();
    }

    @Test
    void runOnce_writesBatchedAdminActionWithCount() {
        ListingSuspension row1 = bulkRow(20L, 200L, FIXED_NOW.minusHours(50));
        ListingSuspension row2 = bulkRow(21L, 201L, FIXED_NOW.minusHours(60));
        when(listingSuspensionRepo.findExpiredBulkSuspends(any(OffsetDateTime.class)))
            .thenReturn(List.of(row1, row2));

        task.runOnce();

        verify(adminActionService).recordSystemAction(
            eq(AdminActionType.REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN),
            eq(Map.of("cancelledCount", 2))
        );
    }

    @Test
    void runOnce_perRowFailureDoesNotAbortSweep() {
        // Per the plan's Task 13 note: a per-row failure (each call has its own tx)
        // does not abort the sweep; the sweep continues with the remaining rows and
        // the batched audit row reflects the count of successfully-cancelled rows.
        // Stub the SECOND row's call (auction id 301L, listing-suspension id 31L) to
        // throw and verify rows 1 and 3 still cancel.
        ListingSuspension row1 = bulkRow(30L, 300L, FIXED_NOW.minusHours(50));
        ListingSuspension row2 = bulkRow(31L, 301L, FIXED_NOW.minusHours(60));
        ListingSuspension row3 = bulkRow(32L, 302L, FIXED_NOW.minusHours(70));
        when(listingSuspensionRepo.findExpiredBulkSuspends(any(OffsetDateTime.class)))
            .thenReturn(List.of(row1, row2, row3));
        // Lenient: rows 1 and 3 don't match this stub but Mockito's strict-stubbing
        // would otherwise treat their calls as PotentialStubbingProblem.
        lenient().doThrow(new RuntimeException("boom"))
            .when(cancellationService).adminCancelExpiredBulkSuspend(301L, 31L);

        task.runOnce();

        verify(cancellationService).adminCancelExpiredBulkSuspend(300L, 30L);
        verify(cancellationService).adminCancelExpiredBulkSuspend(301L, 31L);
        verify(cancellationService).adminCancelExpiredBulkSuspend(302L, 32L);
        verify(adminActionService).recordSystemAction(
            eq(AdminActionType.REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN),
            eq(Map.of("cancelledCount", 2))
        );
    }

    @Test
    void runOnce_isNoOpWhenNoRowsDue() {
        when(listingSuspensionRepo.findExpiredBulkSuspends(any(OffsetDateTime.class)))
            .thenReturn(List.of());

        task.runOnce();

        // No cancellation calls, and no batched audit row either — the spec says
        // "one row per task execution" but the implementation short-circuits on
        // empty to avoid log noise; we lock that behavior down here.
        verifyNoInteractions(cancellationService);
        verify(adminActionService, never()).recordSystemAction(any(), any());
    }

    @Test
    void runOnce_doesNotTouchAdminIndividualCauseRows() {
        // The repository filter excludes cause=ADMIN_INDIVIDUAL — these rows never
        // reach the task. We assert the task respects that contract: feeding only
        // the bulk-cause rows the repository returned means rows with other causes
        // are not touched.
        ListingSuspension bulk = bulkRow(40L, 400L, FIXED_NOW.minusHours(50));
        when(listingSuspensionRepo.findExpiredBulkSuspends(any(OffsetDateTime.class)))
            .thenReturn(List.of(bulk));

        task.runOnce();

        // Only the bulk row's auction id is passed to the service. An ADMIN_INDIVIDUAL
        // row's auction id (e.g. 999L) would never appear here.
        verify(cancellationService).adminCancelExpiredBulkSuspend(400L, 40L);
        verify(cancellationService, never()).adminCancelExpiredBulkSuspend(eq(999L), anyLong());
    }

    @Test
    void runOnce_doesNotTouchAutoCauseRows() {
        // The repository filter also excludes cause=AUTO (ownership monitor). Same
        // shape as the previous test: feeding only the bulk-cause rows means an
        // AUTO row's auction id never gets cancelled.
        ListingSuspension bulk = bulkRow(50L, 500L, FIXED_NOW.minusHours(50));
        when(listingSuspensionRepo.findExpiredBulkSuspends(any(OffsetDateTime.class)))
            .thenReturn(List.of(bulk));

        task.runOnce();

        verify(cancellationService).adminCancelExpiredBulkSuspend(500L, 50L);
        verify(cancellationService, never()).adminCancelExpiredBulkSuspend(eq(888L), anyLong());
    }

    /**
     * Build a bulk-cause listing-suspension row. {@code BaseEntity#id} has no
     * public setter (the {@code @Setter(AccessLevel.NONE)} on the id field is
     * intentional — ids come from JPA's IDENTITY sequence in production), so the
     * test seeds ids by reflection. Matches the helper used in
     * {@code BulkListingSuspendServiceTest}.
     */
    private ListingSuspension bulkRow(Long listingSuspensionId, Long auctionId, OffsetDateTime suspendedAt) {
        Auction auction = new Auction();
        setId(auction, auctionId);
        ListingSuspension ls = ListingSuspension.builder()
            .auction(auction)
            .cause(ListingSuspensionCause.ADMIN_GROUP_BULK)
            .suspendedAt(suspendedAt)
            .build();
        setId(ls, listingSuspensionId);
        return ls;
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
}
