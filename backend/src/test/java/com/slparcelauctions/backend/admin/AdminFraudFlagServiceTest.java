package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagEntityKind;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests covering the realty-groups-F extension: createFraudFlag's
 * entity-type resolver for REALTY_GROUP. Pure Mockito so the test stays fast
 * and isolated from the SpringBootTest seed dance used by the list/dismiss/
 * reinstate integration suites in this package.
 */
@ExtendWith(MockitoExtension.class)
class AdminFraudFlagServiceTest {

    @Mock FraudFlagRepository fraudFlagRepository;
    @Mock UserRepository userRepository;
    @Mock RealtyGroupRepository realtyGroupRepository;
    @Mock AdminAuctionService adminAuctionService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneId.of("UTC"));

    private AdminFraudFlagService service;

    @BeforeEach
    void setUp() {
        service = new AdminFraudFlagService(
            fraudFlagRepository,
            userRepository,
            realtyGroupRepository,
            adminAuctionService,
            clock
        );
    }

    @Test
    void createFraudFlag_forRealtyGroup_writesFlagWithEntityTypeAndEntityId() {
        Long adminId = 42L;
        Long groupId = 7L;
        UUID groupPublicId = UUID.randomUUID();

        User admin = User.builder()
            .username("admin-" + UUID.randomUUID())
            .email("admin@example.com")
            .passwordHash("x")
            .slAvatarUuid(UUID.randomUUID())
            .displayName("Admin Avatar")
            .build();
        // Spring Data callbacks aren't run in a unit test, so we have to seed
        // the id manually to mimic the persisted state.
        setBaseEntityId(admin, adminId);

        RealtyGroup group = RealtyGroup.builder()
            .name("Mainland Realty Co")
            .build();
        setBaseEntityId(group, groupId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(realtyGroupRepository.findByPublicId(groupPublicId)).thenReturn(Optional.of(group));

        // Capture the saved flag so we can assert its shape.
        ArgumentCaptor<FraudFlag> savedFlag = ArgumentCaptor.forClass(FraudFlag.class);
        when(fraudFlagRepository.save(savedFlag.capture())).thenAnswer(inv -> {
            FraudFlag f = inv.getArgument(0);
            setBaseEntityId(f, 100L);
            return f;
        });

        // detail() lookups: fetch the just-saved flag back.
        when(fraudFlagRepository.findById(100L)).thenAnswer(inv -> Optional.of(savedFlag.getValue()));

        AdminFraudFlagDetailDto dto = service.createFraudFlag(
            FraudFlagEntityKind.REALTY_GROUP,
            groupPublicId,
            FraudFlagReason.REALTY_GROUP_FRAUDULENT_LISTINGS,
            adminId,
            Map.of("notes", "Group pattern of fake parcels."),
            "Initial admin notes"
        );

        verify(realtyGroupRepository).findByPublicId(groupPublicId);
        verify(fraudFlagRepository, times(1)).save(any(FraudFlag.class));
        // Slice 3 only routes REALTY_GROUP -- LISTING/USER are explicitly not
        // resolved here, so the auction lookup must never happen on this path.
        verify(adminAuctionService, never()).reinstate(any(), any());

        FraudFlag persisted = savedFlag.getValue();
        assertThat(persisted.getEntityType()).isEqualTo(FraudFlagEntityKind.REALTY_GROUP);
        assertThat(persisted.getReason()).isEqualTo(FraudFlagReason.REALTY_GROUP_FRAUDULENT_LISTINGS);
        assertThat(persisted.getAuction()).isNull();
        assertThat(persisted.isResolved()).isFalse();
        assertThat(persisted.getAdminNotes()).isEqualTo("Initial admin notes");
        assertThat(persisted.getDetectedAt()).isNotNull();

        Map<String, Object> evidence = persisted.getEvidenceJson();
        assertThat(evidence).containsEntry("realtyGroupId", groupId);
        assertThat(evidence).containsEntry("realtyGroupPublicId", groupPublicId.toString());
        assertThat(evidence).containsEntry("entityInternalId", groupId);
        // Caller-supplied evidence is preserved.
        assertThat(evidence).containsEntry("notes", "Group pattern of fake parcels.");

        assertThat(dto.id()).isEqualTo(100L);
        assertThat(dto.reason()).isEqualTo(FraudFlagReason.REALTY_GROUP_FRAUDULENT_LISTINGS);
        // REALTY_GROUP flags have no auction context.
        assertThat(dto.auction()).isNull();
        assertThat(dto.siblingOpenFlagCount()).isZero();
    }

    @Test
    void createFraudFlag_forRealtyGroup_throwsWhenGroupMissing() {
        Long adminId = 42L;
        UUID groupPublicId = UUID.randomUUID();

        User admin = User.builder()
            .username("admin-" + UUID.randomUUID())
            .email("admin@example.com")
            .passwordHash("x")
            .slAvatarUuid(UUID.randomUUID())
            .displayName("Admin Avatar")
            .build();
        setBaseEntityId(admin, adminId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(realtyGroupRepository.findByPublicId(groupPublicId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createFraudFlag(
                FraudFlagEntityKind.REALTY_GROUP,
                groupPublicId,
                FraudFlagReason.REALTY_GROUP_IMPERSONATION,
                adminId,
                null,
                "notes"))
            .isInstanceOf(RealtyGroupNotFoundException.class);

        verify(fraudFlagRepository, never()).save(any());
    }

    @Test
    void createFraudFlag_forListing_isNotYetRoutedThroughThisMethod() {
        Long adminId = 42L;
        UUID anyPublicId = UUID.randomUUID();

        User admin = User.builder()
            .username("admin-" + UUID.randomUUID())
            .email("admin@example.com")
            .passwordHash("x")
            .slAvatarUuid(UUID.randomUUID())
            .displayName("Admin Avatar")
            .build();
        setBaseEntityId(admin, adminId);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.createFraudFlag(
                FraudFlagEntityKind.LISTING,
                anyPublicId,
                FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN,
                adminId,
                null,
                "notes"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LISTING");

        verify(fraudFlagRepository, never()).save(any());
        verify(realtyGroupRepository, never()).findByPublicId(eq(anyPublicId));
    }

    /**
     * BaseEntity / BaseMutableEntity declare {@code id} privately with no public
     * setter, so reflection is the simplest way to seed it for a unit test.
     */
    private static void setBaseEntityId(Object entity, Long id) {
        try {
            Class<?> cls = entity.getClass();
            while (cls != null) {
                try {
                    var field = cls.getDeclaredField("id");
                    field.setAccessible(true);
                    field.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            throw new IllegalStateException("No id field on " + entity.getClass());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set id on " + entity, e);
        }
    }
}
