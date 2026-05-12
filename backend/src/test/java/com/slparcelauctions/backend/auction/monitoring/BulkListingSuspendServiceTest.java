package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.AdminAuctionService;
import com.slparcelauctions.backend.admin.AdminAuctionService.AdminAuctionReinstateResult;
import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService.BulkSuspendResult;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspension;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link BulkListingSuspendService}. Mockito-driven; fixed clock for
 * determinism. Covers spec §10.1, §10.3 behaviours:
 *
 * <ul>
 *   <li>{@code suspendAll} sweeps every ACTIVE listing on the group (case-1 + case-3)
 *       to SUSPENDED, writes a {@link ListingSuspension} row with {@code ADMIN_GROUP_BULK}
 *       cause, fires {@link BotMonitorLifecycleService#onAuctionClosed} and a per-seller
 *       suspended notification, then records one batched admin audit action.</li>
 *   <li>{@code reinstateAll} delegates per-row to {@link AdminAuctionService#reinstate}
 *       (which handles the {@code endsAt} extension + reactivation + reinstated notify),
 *       marks the listing-suspension row {@code liftedAt}, and records one batched audit.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BulkListingSuspendServiceTest {

    private static final OffsetDateTime FIXED_NOW =
        OffsetDateTime.of(2026, 5, 12, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final Long GROUP_ID = 42L;
    private static final Long ADMIN_USER_ID = 9L;

    @Mock AuctionRepository auctionRepo;
    @Mock ListingSuspensionRepository listingSuspensionRepo;
    @Mock BotMonitorLifecycleService botMonitorLifecycleService;
    @Mock NotificationPublisher notificationPublisher;
    @Mock AdminAuctionService adminAuctionService;
    @Mock AdminActionService adminActionService;
    @Mock UserRepository userRepository;

    Clock clock;
    BulkListingSuspendService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);
        service = new BulkListingSuspendService(
            auctionRepo, listingSuspensionRepo, botMonitorLifecycleService,
            notificationPublisher, adminAuctionService, adminActionService,
            userRepository, clock);
        lenient().when(userRepository.getReferenceById(anyLong()))
            .thenAnswer(inv -> {
                Long uid = inv.getArgument(0);
                User u = User.builder().build();
                setId(u, uid);
                return u;
            });
        lenient().when(listingSuspensionRepo.save(any(ListingSuspension.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    // ─────────────────────────── suspendAll() ───────────────────────────

    @Test
    void suspendAll_findsActiveListingsByCase1And3_writesListingSuspensionsAndFlipsStatus() {
        Auction case1 = buildActive(101L, "Mainland Parcel A", buildSeller(7L));
        Auction case3 = buildActive(102L, "Mainland Parcel B", buildSeller(8L));
        when(auctionRepo.findActiveListingsForGroup(GROUP_ID))
            .thenReturn(List.of(case1, case3));

        BulkSuspendResult result = service.suspendAll(GROUP_ID, ADMIN_USER_ID, "FRAUD", null);

        assertThat(result.suspendedCount()).isEqualTo(2);
        assertThat(result.bulkActionId()).isNotNull();

        assertThat(case1.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
        assertThat(case1.getSuspendedAt()).isEqualTo(FIXED_NOW);
        assertThat(case3.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
        assertThat(case3.getSuspendedAt()).isEqualTo(FIXED_NOW);

        ArgumentCaptor<ListingSuspension> rowCap = ArgumentCaptor.forClass(ListingSuspension.class);
        verify(listingSuspensionRepo, times(2)).save(rowCap.capture());
        List<ListingSuspension> rows = rowCap.getAllValues();
        assertThat(rows).hasSize(2);
        for (ListingSuspension row : rows) {
            assertThat(row.getCause()).isEqualTo(ListingSuspensionCause.ADMIN_GROUP_BULK);
            assertThat(row.getBulkActionId()).isEqualTo(result.bulkActionId());
            assertThat(row.getSuspendedByAdmin().getId()).isEqualTo(ADMIN_USER_ID);
            assertThat(row.getReason()).isEqualTo("FRAUD");
            assertThat(row.getSuspendedAt()).isEqualTo(FIXED_NOW);
            assertThat(row.getGroupSuspension()).isNull();
            assertThat(row.getLiftedAt()).isNull();
            assertThat(row.getCancelledAt()).isNull();
        }
    }

    @Test
    void suspendAll_skipsAlreadySuspendedListings() {
        // The auction repo only returns ACTIVE rows so the loop wouldn't normally see this
        // entity — but if a parallel transaction flips one to SUSPENDED between the SELECT
        // and the per-row update, we must skip it instead of writing a duplicate listing
        // suspension row.
        Auction active = buildActive(101L, "Parcel A", buildSeller(7L));
        Auction sneakySuspended = buildActive(102L, "Parcel B", buildSeller(8L));
        sneakySuspended.setStatus(AuctionStatus.SUSPENDED);  // raced after the SELECT
        when(auctionRepo.findActiveListingsForGroup(GROUP_ID))
            .thenReturn(List.of(active, sneakySuspended));

        BulkSuspendResult result = service.suspendAll(GROUP_ID, ADMIN_USER_ID, "FRAUD", null);

        assertThat(result.suspendedCount()).isEqualTo(1);
        verify(listingSuspensionRepo, times(1)).save(any(ListingSuspension.class));
        verify(botMonitorLifecycleService, times(1)).onAuctionClosed(any(Auction.class));
        verify(notificationPublisher, times(1))
            .listingSuspended(anyLong(), anyLong(), any(), any());
    }

    @Test
    void suspendAll_skipsNonActiveListings() {
        // Same idea as above but with a non-SUSPENDED non-ACTIVE status — confirms the
        // guard checks for ACTIVE rather than "is not SUSPENDED".
        Auction active = buildActive(101L, "Parcel A", buildSeller(7L));
        Auction draft = buildActive(102L, "Parcel B", buildSeller(8L));
        draft.setStatus(AuctionStatus.DRAFT);
        when(auctionRepo.findActiveListingsForGroup(GROUP_ID))
            .thenReturn(List.of(active, draft));

        BulkSuspendResult result = service.suspendAll(GROUP_ID, ADMIN_USER_ID, "FRAUD", null);

        assertThat(result.suspendedCount()).isEqualTo(1);
        verify(listingSuspensionRepo, times(1)).save(any(ListingSuspension.class));
    }

    @Test
    void suspendAll_callsBotMonitorLifecycleOnEach() {
        Auction a1 = buildActive(101L, "Parcel A", buildSeller(7L));
        Auction a2 = buildActive(102L, "Parcel B", buildSeller(8L));
        Auction a3 = buildActive(103L, "Parcel C", buildSeller(9L));
        when(auctionRepo.findActiveListingsForGroup(GROUP_ID))
            .thenReturn(List.of(a1, a2, a3));

        service.suspendAll(GROUP_ID, ADMIN_USER_ID, "FRAUD", null);

        verify(botMonitorLifecycleService).onAuctionClosed(a1);
        verify(botMonitorLifecycleService).onAuctionClosed(a2);
        verify(botMonitorLifecycleService).onAuctionClosed(a3);
    }

    @Test
    void suspendAll_publishesListingSuspendedNotificationPerSeller() {
        Auction a1 = buildActive(101L, "Parcel A", buildSeller(7L));
        Auction a2 = buildActive(102L, "Parcel B", buildSeller(8L));
        when(auctionRepo.findActiveListingsForGroup(GROUP_ID))
            .thenReturn(List.of(a1, a2));

        service.suspendAll(GROUP_ID, ADMIN_USER_ID, "FRAUD", null);

        verify(notificationPublisher).listingSuspended(7L, 101L, "Parcel A", "ADMIN_GROUP_BULK_SUSPEND");
        verify(notificationPublisher).listingSuspended(8L, 102L, "Parcel B", "ADMIN_GROUP_BULK_SUSPEND");
    }

    @Test
    void suspendAll_writesAdminActionWithCount() {
        Auction a1 = buildActive(101L, "Parcel A", buildSeller(7L));
        Auction a2 = buildActive(102L, "Parcel B", buildSeller(8L));
        when(auctionRepo.findActiveListingsForGroup(GROUP_ID))
            .thenReturn(List.of(a1, a2));

        BulkSuspendResult result = service.suspendAll(GROUP_ID, ADMIN_USER_ID, "FRAUD", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailsCap = ArgumentCaptor.forClass(Map.class);
        verify(adminActionService).record(
            eq(ADMIN_USER_ID),
            eq(AdminActionType.REALTY_GROUP_BULK_SUSPEND),
            eq(AdminActionTargetType.REALTY_GROUP),
            eq(GROUP_ID),
            any(),
            detailsCap.capture());
        Map<String, Object> details = detailsCap.getValue();
        assertThat(details).containsEntry("count", 2);
        assertThat(details).containsEntry("groupId", GROUP_ID);
        assertThat(details).containsEntry("bulkActionId", result.bulkActionId().toString());
    }

    @Test
    void suspendAll_returnsBulkActionIdAndCount() {
        Auction a = buildActive(101L, "Parcel A", buildSeller(7L));
        when(auctionRepo.findActiveListingsForGroup(GROUP_ID))
            .thenReturn(List.of(a));

        BulkSuspendResult result = service.suspendAll(GROUP_ID, ADMIN_USER_ID, "FRAUD", null);

        assertThat(result.bulkActionId()).isNotNull();
        assertThat(result.suspendedCount()).isEqualTo(1);
    }

    @Test
    void suspendAll_zeroActiveListings_returnsZeroCountAndStillRecordsAudit() {
        when(auctionRepo.findActiveListingsForGroup(GROUP_ID)).thenReturn(List.of());

        BulkSuspendResult result = service.suspendAll(GROUP_ID, ADMIN_USER_ID, "FRAUD", null);

        assertThat(result.suspendedCount()).isEqualTo(0);
        verify(listingSuspensionRepo, never()).save(any(ListingSuspension.class));
        verify(notificationPublisher, never())
            .listingSuspended(anyLong(), anyLong(), any(), any());
        verify(botMonitorLifecycleService, never()).onAuctionClosed(any(Auction.class));
        verify(adminActionService).record(
            eq(ADMIN_USER_ID),
            eq(AdminActionType.REALTY_GROUP_BULK_SUSPEND),
            eq(AdminActionTargetType.REALTY_GROUP),
            eq(GROUP_ID),
            any(), any());
    }

    // ─────────────────────────── reinstateAll() ─────────────────────────

    @Test
    void reinstateAll_findsActiveBulkSuspensionsForGroup_callsReinstateOnEach() {
        Auction a1 = buildSuspended(101L);
        Auction a2 = buildSuspended(102L);
        OffsetDateTime suspendedAt1 = FIXED_NOW.minusHours(6);
        OffsetDateTime suspendedAt2 = FIXED_NOW.minusHours(3);
        ListingSuspension ls1 = ListingSuspension.builder()
            .auction(a1).cause(ListingSuspensionCause.ADMIN_GROUP_BULK)
            .suspendedAt(suspendedAt1).build();
        ListingSuspension ls2 = ListingSuspension.builder()
            .auction(a2).cause(ListingSuspensionCause.ADMIN_GROUP_BULK)
            .suspendedAt(suspendedAt2).build();
        when(listingSuspensionRepo.findActiveBulkSuspensionsForGroup(GROUP_ID))
            .thenReturn(List.of(ls1, ls2));
        when(adminAuctionService.reinstate(eq(101L), eq(Optional.of(suspendedAt1))))
            .thenReturn(new AdminAuctionReinstateResult(a1, Duration.ofHours(6), FIXED_NOW.plusHours(8)));
        when(adminAuctionService.reinstate(eq(102L), eq(Optional.of(suspendedAt2))))
            .thenReturn(new AdminAuctionReinstateResult(a2, Duration.ofHours(3), FIXED_NOW.plusHours(5)));

        int count = service.reinstateAll(GROUP_ID, ADMIN_USER_ID, "false positive");

        assertThat(count).isEqualTo(2);
        verify(adminAuctionService).reinstate(eq(101L), eq(Optional.of(suspendedAt1)));
        verify(adminAuctionService).reinstate(eq(102L), eq(Optional.of(suspendedAt2)));

        verify(adminActionService).record(
            eq(ADMIN_USER_ID),
            eq(AdminActionType.REALTY_GROUP_BULK_REINSTATE),
            eq(AdminActionTargetType.REALTY_GROUP),
            eq(GROUP_ID),
            any(), any());
    }

    @Test
    void reinstateAll_writesListingSuspensionLiftedAt() {
        Auction a = buildSuspended(101L);
        OffsetDateTime suspendedAt = FIXED_NOW.minusHours(6);
        ListingSuspension ls = ListingSuspension.builder()
            .auction(a).cause(ListingSuspensionCause.ADMIN_GROUP_BULK)
            .suspendedAt(suspendedAt).build();
        when(listingSuspensionRepo.findActiveBulkSuspensionsForGroup(GROUP_ID))
            .thenReturn(new ArrayList<>(List.of(ls)));
        when(adminAuctionService.reinstate(eq(101L), eq(Optional.of(suspendedAt))))
            .thenReturn(new AdminAuctionReinstateResult(a, Duration.ofHours(6), FIXED_NOW.plusHours(8)));

        service.reinstateAll(GROUP_ID, ADMIN_USER_ID, "lifted");

        assertThat(ls.getLiftedAt()).isEqualTo(FIXED_NOW);
    }

    // ─────────────────────────── helpers ────────────────────────────────

    private User buildSeller(Long id) {
        User u = User.builder()
            .username("seller-" + id)
            .build();
        setId(u, id);
        return u;
    }

    private Auction buildActive(Long auctionId, String title, User seller) {
        Auction a = Auction.builder()
            .seller(seller)
            .title(title)
            .startingBid(1000L)
            .durationHours(72)
            .status(AuctionStatus.ACTIVE)
            .build();
        setId(a, auctionId);
        return a;
    }

    private Auction buildSuspended(Long auctionId) {
        Auction a = Auction.builder()
            .seller(buildSeller(7L))
            .title("Suspended Parcel")
            .startingBid(1000L)
            .durationHours(72)
            .status(AuctionStatus.SUSPENDED)
            .build();
        setId(a, auctionId);
        return a;
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
