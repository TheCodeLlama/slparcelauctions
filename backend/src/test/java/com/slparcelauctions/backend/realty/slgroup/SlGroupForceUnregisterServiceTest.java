package com.slparcelauctions.backend.realty.slgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService;
import com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService.BulkSuspendResult;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupNotFoundException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for {@link SlGroupForceUnregisterService}. Mockito-driven; fixed clock for
 * determinism. Spec §13.5: an admin force-unregister bypasses the active-listings gate
 * and cascades into {@link BulkListingSuspendService#suspendAll} when any
 * {@code AuctionStatus.ACTIVE} case-3 listing is still attached to the SL group
 * registration.
 */
@ExtendWith(MockitoExtension.class)
class SlGroupForceUnregisterServiceTest {

    private static final OffsetDateTime FIXED_NOW =
        OffsetDateTime.of(2026, 5, 12, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final Long REALTY_GROUP_ID = 42L;
    private static final Long SL_GROUP_ID = 7L;
    private static final Long ADMIN_USER_ID = 9L;
    private static final UUID REALTY_GROUP_PUBLIC_ID = UUID.randomUUID();
    private static final UUID SL_GROUP_PUBLIC_ID = UUID.randomUUID();
    private static final String REASON = "FRAUD";

    @Mock RealtyGroupSlGroupRepository slGroupRepo;
    @Mock AuctionRepository auctionRepo;
    @Mock BulkListingSuspendService bulkListingSuspendService;
    @Mock AdminActionService adminActionService;
    @Mock UserRepository userRepo;

    Clock clock;
    SlGroupForceUnregisterService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);
        service = new SlGroupForceUnregisterService(
            slGroupRepo, auctionRepo, bulkListingSuspendService,
            adminActionService, userRepo, clock);
        lenient().when(userRepo.getReferenceById(anyLong()))
            .thenAnswer(inv -> {
                Long uid = inv.getArgument(0);
                User u = User.builder().build();
                setId(u, uid);
                return u;
            });
        lenient().when(bulkListingSuspendService.suspendAll(
                anyLong(), anyLong(), anyString(), any(), any()))
            .thenReturn(new BulkSuspendResult(UUID.randomUUID(), 0));
    }

    @Test
    void forceUnregister_setsUnregisteredAtAndAdmin() {
        RealtyGroupSlGroup row = buildRegistration();
        when(slGroupRepo.findByPublicId(SL_GROUP_PUBLIC_ID)).thenReturn(Optional.of(row));
        when(auctionRepo.findActiveCase3ListingsForSlGroup(SL_GROUP_ID))
            .thenReturn(List.of());

        service.forceUnregister(REALTY_GROUP_PUBLIC_ID, SL_GROUP_PUBLIC_ID, ADMIN_USER_ID, REASON);

        assertThat(row.getUnregisteredAt()).isEqualTo(FIXED_NOW);
        assertThat(row.getUnregisteredByAdmin()).isNotNull();
        assertThat(row.getUnregisteredByAdmin().getId()).isEqualTo(ADMIN_USER_ID);
        assertThat(row.getUnregisterReason()).isEqualTo(REASON);
    }

    @Test
    void forceUnregister_findsCase3ActiveListings_callsBulkSuspendAll() {
        RealtyGroupSlGroup row = buildRegistration();
        when(slGroupRepo.findByPublicId(SL_GROUP_PUBLIC_ID)).thenReturn(Optional.of(row));
        Auction a1 = buildActive(101L);
        Auction a2 = buildActive(102L);
        when(auctionRepo.findActiveCase3ListingsForSlGroup(SL_GROUP_ID))
            .thenReturn(List.of(a1, a2));

        service.forceUnregister(REALTY_GROUP_PUBLIC_ID, SL_GROUP_PUBLIC_ID, ADMIN_USER_ID, REASON);

        verify(bulkListingSuspendService, times(1)).suspendAll(
            eq(REALTY_GROUP_ID),
            eq(ADMIN_USER_ID),
            eq("SL_GROUP_FORCE_UNREGISTER"),
            isNull(),
            isNull());
    }

    @Test
    void forceUnregister_zeroActiveListings_succeedsWithNoCascade() {
        RealtyGroupSlGroup row = buildRegistration();
        when(slGroupRepo.findByPublicId(SL_GROUP_PUBLIC_ID)).thenReturn(Optional.of(row));
        when(auctionRepo.findActiveCase3ListingsForSlGroup(SL_GROUP_ID))
            .thenReturn(List.of());

        service.forceUnregister(REALTY_GROUP_PUBLIC_ID, SL_GROUP_PUBLIC_ID, ADMIN_USER_ID, REASON);

        // Unregister fields still applied even without cascade.
        assertThat(row.getUnregisteredAt()).isEqualTo(FIXED_NOW);
        assertThat(row.getUnregisterReason()).isEqualTo(REASON);
        // Cascade short-circuits.
        verifyNoInteractions(bulkListingSuspendService);
    }

    @Test
    void forceUnregister_writesAdminAction() {
        RealtyGroupSlGroup row = buildRegistration();
        when(slGroupRepo.findByPublicId(SL_GROUP_PUBLIC_ID)).thenReturn(Optional.of(row));
        Auction a1 = buildActive(101L);
        when(auctionRepo.findActiveCase3ListingsForSlGroup(SL_GROUP_ID))
            .thenReturn(List.of(a1));

        service.forceUnregister(REALTY_GROUP_PUBLIC_ID, SL_GROUP_PUBLIC_ID, ADMIN_USER_ID, REASON);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailsCap = ArgumentCaptor.forClass(Map.class);
        verify(adminActionService).record(
            eq(ADMIN_USER_ID),
            eq(AdminActionType.REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER),
            eq(AdminActionTargetType.REALTY_GROUP),
            eq(REALTY_GROUP_ID),
            eq(REASON),
            detailsCap.capture());
        Map<String, Object> details = detailsCap.getValue();
        assertThat(details).containsEntry("slGroupPublicId", SL_GROUP_PUBLIC_ID.toString());
        assertThat(details).containsEntry("cascadedListingCount", 1);
        assertThat(details).containsEntry("reason", REASON);
    }

    @Test
    void forceUnregister_unknownSlGroup_throwsNotFound() {
        when(slGroupRepo.findByPublicId(SL_GROUP_PUBLIC_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            service.forceUnregister(REALTY_GROUP_PUBLIC_ID, SL_GROUP_PUBLIC_ID, ADMIN_USER_ID, REASON))
            .isInstanceOf(SlGroupNotFoundException.class);

        verifyNoInteractions(bulkListingSuspendService);
        verify(adminActionService, never()).record(anyLong(), any(), any(), anyLong(), any(), any());
    }

    // ─────────────────────────── helpers ────────────────────────────────

    private RealtyGroupSlGroup buildRegistration() {
        RealtyGroupSlGroup row = RealtyGroupSlGroup.builder()
            .realtyGroupId(REALTY_GROUP_ID)
            .slGroupUuid(UUID.randomUUID())
            .slGroupName("Test SL Group")
            .verified(true)
            .build();
        setId(row, SL_GROUP_ID);
        return row;
    }

    private Auction buildActive(Long auctionId) {
        Auction a = Auction.builder()
            .title("Active Parcel " + auctionId)
            .startingBid(1000L)
            .durationHours(72)
            .status(AuctionStatus.ACTIVE)
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
