package com.slparcelauctions.backend.parceltag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.parceltag.dto.AdminParcelTagDto;
import com.slparcelauctions.backend.parceltag.dto.CreateParcelTagRequest;
import com.slparcelauctions.backend.parceltag.dto.UpdateParcelTagRequest;
import com.slparcelauctions.backend.parceltag.exception.ParcelTagCodeConflictException;
import com.slparcelauctions.backend.parceltag.exception.ParcelTagNotFoundException;

@ExtendWith(MockitoExtension.class)
class AdminParcelTagServiceTest {

    @Mock ParcelTagRepository repo;
    @Mock AdminActionService adminActionService;

    @InjectMocks AdminParcelTagService service;

    @Test
    void create_happyPath_persistsAndAudits() {
        when(repo.existsByCode("BEACHFRONT")).thenReturn(false);
        when(repo.findMaxSortOrderByCategory("Terrain")).thenReturn(8);
        when(repo.save(any(ParcelTag.class))).thenAnswer(inv -> {
            ParcelTag t = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(t, "id", 99L);
            return t;
        });

        CreateParcelTagRequest req = new CreateParcelTagRequest(
                "BEACHFRONT", "Beachfront", "Terrain", "Sandy beach", null);
        AdminParcelTagDto result = service.create(42L, req);

        assertThat(result.code()).isEqualTo("BEACHFRONT");
        assertThat(result.sortOrder()).isEqualTo(9);
        assertThat(result.active()).isTrue();
        verify(adminActionService).record(
                eq(42L),
                eq(AdminActionType.PARCEL_TAG_CREATED),
                eq(AdminActionTargetType.PARCEL_TAG),
                eq(99L),
                eq(null),
                any());
    }

    @Test
    void create_explicitSortOrder_isHonoured() {
        when(repo.existsByCode("X")).thenReturn(false);
        when(repo.save(any(ParcelTag.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateParcelTagRequest req = new CreateParcelTagRequest(
                "X", "X", "C", "d", 0);
        AdminParcelTagDto result = service.create(1L, req);

        assertThat(result.sortOrder()).isZero();
        verify(repo, never()).findMaxSortOrderByCategory(any());
    }

    @Test
    void create_duplicateCode_throwsConflict() {
        when(repo.existsByCode("WATERFRONT")).thenReturn(true);

        CreateParcelTagRequest req = new CreateParcelTagRequest(
                "WATERFRONT", "x", "y", null, null);

        assertThatThrownBy(() -> service.create(1L, req))
                .isInstanceOf(ParcelTagCodeConflictException.class);
        verify(repo, never()).save(any());
        verify(adminActionService, never()).record(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void update_writesOnlySuppliedFields_andAudits() {
        ParcelTag existing = ParcelTag.builder()
                .code("WATERFRONT").label("Old").category("Terrain")
                .description("d").sortOrder(1).active(true).build();
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", 7L);
        when(repo.findByCode("WATERFRONT")).thenReturn(Optional.of(existing));
        when(repo.save(any(ParcelTag.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateParcelTagRequest req = new UpdateParcelTagRequest(
                "New label", null, null, null);
        AdminParcelTagDto result = service.update(42L, "WATERFRONT", req);

        assertThat(result.label()).isEqualTo("New label");
        assertThat(result.category()).isEqualTo("Terrain"); // unchanged
        assertThat(existing.getLabel()).isEqualTo("New label");
        verify(adminActionService).record(
                eq(42L),
                eq(AdminActionType.PARCEL_TAG_UPDATED),
                eq(AdminActionTargetType.PARCEL_TAG),
                eq(7L),
                eq(null),
                any());
    }

    @Test
    void update_noChanges_skipsAudit() {
        ParcelTag existing = ParcelTag.builder()
                .code("WATERFRONT").label("Same").category("T")
                .description("d").sortOrder(1).active(true).build();
        when(repo.findByCode("WATERFRONT")).thenReturn(Optional.of(existing));
        when(repo.save(any(ParcelTag.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateParcelTagRequest req = new UpdateParcelTagRequest(
                "Same", "T", "d", 1);
        service.update(42L, "WATERFRONT", req);

        verify(adminActionService, never()).record(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void update_missingCode_throwsNotFound() {
        when(repo.findByCode("NOPE")).thenReturn(Optional.empty());

        UpdateParcelTagRequest req = new UpdateParcelTagRequest(
                "x", null, null, null);

        assertThatThrownBy(() -> service.update(1L, "NOPE", req))
                .isInstanceOf(ParcelTagNotFoundException.class);
    }

    @Test
    void toggleActive_flipsAndAudits() {
        ParcelTag existing = ParcelTag.builder()
                .code("X").label("X").category("Y")
                .sortOrder(1).active(true).build();
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", 5L);
        when(repo.findByCode("X")).thenReturn(Optional.of(existing));
        when(repo.save(any(ParcelTag.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminParcelTagDto result = service.toggleActive(42L, "X");

        assertThat(result.active()).isFalse();
        assertThat(existing.getActive()).isFalse();
        verify(adminActionService).record(
                eq(42L),
                eq(AdminActionType.PARCEL_TAG_TOGGLED_ACTIVE),
                eq(AdminActionTargetType.PARCEL_TAG),
                eq(5L),
                eq(null),
                any());
    }

    @Test
    void toggleActive_missingCode_throwsNotFound() {
        when(repo.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggleActive(1L, "NOPE"))
                .isInstanceOf(ParcelTagNotFoundException.class);
    }
}
