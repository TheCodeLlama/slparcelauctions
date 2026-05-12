package com.slparcelauctions.backend.realty.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.dto.BulkCommissionRatesRequest;
import com.slparcelauctions.backend.realty.dto.BulkCommissionRatesRequest.MemberRate;
import com.slparcelauctions.backend.realty.exception.MemberNotInGroupException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard;
import com.slparcelauctions.backend.realty.moderation.exception.RealtyGroupSuspendedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

/**
 * Unit tests for {@link RealtyGroupBulkCommissionService}. Mockito-driven. Verifies
 * the atomic-batch invariant (one bad entry rolls back every preceding write because
 * the service throws inside the {@code @Transactional} method), the suspension guard
 * invocation, the {@code MANAGE_MEMBERS} authorization, and the per-entry validation
 * paths.
 */
@ExtendWith(MockitoExtension.class)
class RealtyGroupBulkCommissionServiceTest {

    @Mock RealtyGroupRepository groupRepo;
    @Mock RealtyGroupMemberRepository memberRepo;
    @Mock RealtyGroupGuard guard;
    @Mock RealtyGroupAuthorizer authorizer;

    RealtyGroupBulkCommissionService service;

    @BeforeEach
    void setUp() {
        service = new RealtyGroupBulkCommissionService(groupRepo, memberRepo, guard, authorizer);
    }

    // ─────────────────────── happy path ───────────────────────

    @Test
    void update_happyPath_writesAllRates() {
        RealtyGroup group = buildGroup(42L);
        RealtyGroupMember m1 = buildMember(101L, 42L);
        RealtyGroupMember m2 = buildMember(102L, 42L);
        when(groupRepo.findByPublicId(group.getPublicId())).thenReturn(Optional.of(group));
        when(memberRepo.findByPublicId(m1.getPublicId())).thenReturn(Optional.of(m1));
        when(memberRepo.findByPublicId(m2.getPublicId())).thenReturn(Optional.of(m2));

        BulkCommissionRatesRequest req = new BulkCommissionRatesRequest(List.of(
            new MemberRate(m1.getPublicId(), new BigDecimal("0.0750")),
            new MemberRate(m2.getPublicId(), new BigDecimal("0.1000"))
        ));

        service.updateRates(group.getPublicId(), 9L, req);

        // Both members' rates have been overwritten on the loaded entity (JPA
        // flushes the change at tx-commit; the test asserts at the entity level).
        assertThat(m1.getAgentCommissionRate()).isEqualByComparingTo("0.0750");
        assertThat(m2.getAgentCommissionRate()).isEqualByComparingTo("0.1000");
        verify(guard).requireGroupCanOperate(42L);
        verify(authorizer).assertCan(9L, 42L, RealtyGroupPermission.MANAGE_MEMBERS);
    }

    // ─────────────────────── rollback invariants ───────────────────────

    @Test
    void update_oneInvalidRate_rollsBackBatch() {
        // The second entry's rate is negative -- the service throws IllegalArgumentException,
        // which bubbles out of the @Transactional method and rolls back the in-progress
        // edits. The test asserts that the *throw* happens; Spring's transactional rollback
        // is integration-tested in the slice test. The first member's in-memory entity
        // does get its setter called before the throw (JPA hasn't flushed yet), but the
        // contract that matters at the wire is "the request fails, no commit happens."
        RealtyGroup group = buildGroup(42L);
        RealtyGroupMember m1 = buildMember(101L, 42L);
        RealtyGroupMember m2 = buildMember(102L, 42L);
        when(groupRepo.findByPublicId(group.getPublicId())).thenReturn(Optional.of(group));
        when(memberRepo.findByPublicId(m1.getPublicId())).thenReturn(Optional.of(m1));
        when(memberRepo.findByPublicId(m2.getPublicId())).thenReturn(Optional.of(m2));

        BulkCommissionRatesRequest req = new BulkCommissionRatesRequest(List.of(
            new MemberRate(m1.getPublicId(), new BigDecimal("0.0750")),
            new MemberRate(m2.getPublicId(), new BigDecimal("-0.0100"))
        ));

        assertThatThrownBy(() -> service.updateRates(group.getPublicId(), 9L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("rate must be >= 0");
    }

    @Test
    void update_memberNotInGroup_throwsMemberNotInGroup() {
        RealtyGroup group = buildGroup(42L);
        UUID missingMemberPid = UUID.randomUUID();
        when(groupRepo.findByPublicId(group.getPublicId())).thenReturn(Optional.of(group));
        when(memberRepo.findByPublicId(missingMemberPid)).thenReturn(Optional.empty());

        BulkCommissionRatesRequest req = new BulkCommissionRatesRequest(List.of(
            new MemberRate(missingMemberPid, new BigDecimal("0.0500"))
        ));

        assertThatThrownBy(() -> service.updateRates(group.getPublicId(), 9L, req))
            .isInstanceOf(MemberNotInGroupException.class)
            .extracting(t -> ((MemberNotInGroupException) t).getMemberPublicId())
            .isEqualTo(missingMemberPid);
    }

    @Test
    void update_memberInDifferentGroup_throwsMemberNotInGroup() {
        // Defense in depth: the member row exists in the DB but belongs to a different
        // group than the one named in the URL. Surfaces as MEMBER_NOT_IN_GROUP rather
        // than leaking cross-tenant existence.
        RealtyGroup group = buildGroup(42L);
        RealtyGroupMember foreignMember = buildMember(101L, /* groupId = */ 99L);
        when(groupRepo.findByPublicId(group.getPublicId())).thenReturn(Optional.of(group));
        when(memberRepo.findByPublicId(foreignMember.getPublicId()))
            .thenReturn(Optional.of(foreignMember));

        BulkCommissionRatesRequest req = new BulkCommissionRatesRequest(List.of(
            new MemberRate(foreignMember.getPublicId(), new BigDecimal("0.0500"))
        ));

        assertThatThrownBy(() -> service.updateRates(group.getPublicId(), 9L, req))
            .isInstanceOf(MemberNotInGroupException.class);
    }

    @Test
    void update_negativeRate_throwsValidationException() {
        // The DTO's @DecimalMin("0.0") catches this at the controller boundary, but the
        // service performs a defense-in-depth recheck so callers that wire the service
        // up directly (jobs, future batch importers) still get the guarantee.
        RealtyGroup group = buildGroup(42L);
        RealtyGroupMember m1 = buildMember(101L, 42L);
        when(groupRepo.findByPublicId(group.getPublicId())).thenReturn(Optional.of(group));
        when(memberRepo.findByPublicId(m1.getPublicId())).thenReturn(Optional.of(m1));

        BulkCommissionRatesRequest req = new BulkCommissionRatesRequest(List.of(
            new MemberRate(m1.getPublicId(), new BigDecimal("-0.01"))
        ));

        assertThatThrownBy(() -> service.updateRates(group.getPublicId(), 9L, req))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────── authorization ───────────────────────

    @Test
    void update_requiresManageMembersPermission() {
        // Spec §6.7: authorization is gated by MANAGE_MEMBERS. The leader holds it
        // implicitly via the authorizer's leader-short-circuit; an agent without the
        // flag is rejected with RealtyGroupPermissionDeniedException.
        RealtyGroup group = buildGroup(42L);
        RealtyGroupMember m1 = buildMember(101L, 42L);
        when(groupRepo.findByPublicId(group.getPublicId())).thenReturn(Optional.of(group));
        doThrow(new RealtyGroupPermissionDeniedException(RealtyGroupPermission.MANAGE_MEMBERS))
            .when(authorizer).assertCan(anyLong(), anyLong(), eq(RealtyGroupPermission.MANAGE_MEMBERS));

        BulkCommissionRatesRequest req = new BulkCommissionRatesRequest(List.of(
            new MemberRate(m1.getPublicId(), new BigDecimal("0.0500"))
        ));

        assertThatThrownBy(() -> service.updateRates(group.getPublicId(), 7L, req))
            .isInstanceOf(RealtyGroupPermissionDeniedException.class);
        // No member work was performed once the authorizer rejected.
        verify(memberRepo, never()).findByPublicId(any());
    }

    @Test
    void update_callsRealtyGroupGuardForSuspensionCheck() {
        // The guard fires *before* authorization so a suspended group can't be edited
        // even by its leader. A thrown RealtyGroupSuspendedException must propagate.
        RealtyGroup group = buildGroup(42L);
        RealtyGroupMember m1 = buildMember(101L, 42L);
        when(groupRepo.findByPublicId(group.getPublicId())).thenReturn(Optional.of(group));
        doThrow(new RealtyGroupSuspendedException(
                RealtyGroupSuspendedException.Status.SUSPENDED, null, "FRAUD"))
            .when(guard).requireGroupCanOperate(42L);

        BulkCommissionRatesRequest req = new BulkCommissionRatesRequest(List.of(
            new MemberRate(m1.getPublicId(), new BigDecimal("0.0500"))
        ));

        assertThatThrownBy(() -> service.updateRates(group.getPublicId(), 9L, req))
            .isInstanceOf(RealtyGroupSuspendedException.class);
        verify(guard).requireGroupCanOperate(42L);
        // Authorization and member lookups must not run once the guard rejects.
        verify(authorizer, never()).assertCan(anyLong(), anyLong(), any());
        verify(memberRepo, never()).findByPublicId(any());
    }

    @Test
    void update_groupNotFound_throwsRealtyGroupNotFound() {
        UUID missingGroupPid = UUID.randomUUID();
        when(groupRepo.findByPublicId(missingGroupPid)).thenReturn(Optional.empty());

        BulkCommissionRatesRequest req = new BulkCommissionRatesRequest(List.of(
            new MemberRate(UUID.randomUUID(), new BigDecimal("0.05"))
        ));

        assertThatThrownBy(() -> service.updateRates(missingGroupPid, 9L, req))
            .isInstanceOf(RealtyGroupNotFoundException.class);
    }

    // ─────────────────────── helpers ───────────────────────

    private RealtyGroup buildGroup(Long id) {
        RealtyGroup g = RealtyGroup.builder()
            .name("Mainland Realty Co")
            .slug("mainland-realty-co")
            .leaderId(100L)
            .build();
        setId(g, id);
        return g;
    }

    private RealtyGroupMember buildMember(Long id, Long groupId) {
        RealtyGroupMember m = RealtyGroupMember.builder()
            .groupId(groupId)
            .userId(id + 1000L)
            .build();
        m.setAgentCommissionRate(BigDecimal.ZERO);
        setId(m, id);
        return m;
    }

    /** Reflection helper: walk the inheritance chain and set the inherited {@code id}. */
    private static void setId(Object entity, Long id) {
        Class<?> cur = entity.getClass();
        while (cur != null) {
            try {
                java.lang.reflect.Field f = cur.getDeclaredField("id");
                f.setAccessible(true);
                f.set(entity, id);
                return;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            } catch (IllegalAccessException ex) {
                throw new RuntimeException(ex);
            }
        }
        throw new RuntimeException("id field not found on " + entity.getClass());
    }
}
