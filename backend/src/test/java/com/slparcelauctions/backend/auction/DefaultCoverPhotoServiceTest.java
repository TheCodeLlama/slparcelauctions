package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.User;

/**
 * Unit coverage for {@link DefaultCoverPhotoService} (Plan Task 5 of the
 * theme-image-variants feature). The service picks between group-source and
 * user-source defaults, copies whichever variants are present on the source
 * via {@link ObjectStorageService#copy(String, String)}, and inserts a single
 * {@link AuctionPhoto} row. The {@code light_*} columns are NOT NULL, so a
 * source with only the dark variant promotes that variant into the light slot
 * so the row inserts.
 */
class DefaultCoverPhotoServiceTest {

    private AuctionPhotoRepository repo;
    private ObjectStorageService storage;
    private RealtyGroupRepository groupRepo;
    private DefaultCoverPhotoService service;

    @BeforeEach
    void setup() {
        repo = mock(AuctionPhotoRepository.class);
        storage = mock(ObjectStorageService.class);
        groupRepo = mock(RealtyGroupRepository.class);
        service = new DefaultCoverPhotoService(repo, storage, groupRepo);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static User sellerWithBothVariants() {
        User u = User.builder()
                .id(42L).publicId(UUID.randomUUID())
                .email("a@b.c").username("alice").passwordHash("x")
                .build();
        u.setDefaultCoverLightObjectKey("users/42/default-cover-light.webp");
        u.setDefaultCoverLightContentType("image/webp");
        u.setDefaultCoverLightSizeBytes(123L);
        u.setDefaultCoverDarkObjectKey("users/42/default-cover-dark.webp");
        u.setDefaultCoverDarkContentType("image/webp");
        u.setDefaultCoverDarkSizeBytes(150L);
        return u;
    }

    private static User sellerLightOnly() {
        User u = User.builder()
                .id(42L).publicId(UUID.randomUUID())
                .email("a@b.c").username("alice").passwordHash("x")
                .build();
        u.setDefaultCoverLightObjectKey("users/42/default-cover-light.jpg");
        u.setDefaultCoverLightContentType("image/jpeg");
        u.setDefaultCoverLightSizeBytes(123L);
        return u;
    }

    private static User sellerDarkOnly() {
        User u = User.builder()
                .id(42L).publicId(UUID.randomUUID())
                .email("a@b.c").username("alice").passwordHash("x")
                .build();
        u.setDefaultCoverDarkObjectKey("users/42/default-cover-dark.webp");
        u.setDefaultCoverDarkContentType("image/webp");
        u.setDefaultCoverDarkSizeBytes(150L);
        return u;
    }

    private static User sellerWithoutCover() {
        return User.builder()
                .id(42L).publicId(UUID.randomUUID())
                .email("a@b.c").username("alice").passwordHash("x")
                .build();
    }

    private static RealtyGroup groupWithBothVariants(Long id) {
        RealtyGroup g = RealtyGroup.builder()
                .name("Acme Realty").slug("acme").leaderId(42L)
                .defaultListingLightObjectKey("groups/" + id + "/default-listing-light.webp")
                .defaultListingLightContentType("image/webp")
                .defaultListingLightSizeBytes(500L)
                .defaultListingDarkObjectKey("groups/" + id + "/default-listing-dark.webp")
                .defaultListingDarkContentType("image/webp")
                .defaultListingDarkSizeBytes(550L)
                .build();
        setBaseField(g, "id", id);
        return g;
    }

    private static RealtyGroup groupLightOnly(Long id) {
        RealtyGroup g = RealtyGroup.builder()
                .name("Acme Realty").slug("acme").leaderId(42L)
                .defaultListingLightObjectKey("groups/" + id + "/default-listing-light.jpg")
                .defaultListingLightContentType("image/jpeg")
                .defaultListingLightSizeBytes(500L)
                .build();
        setBaseField(g, "id", id);
        return g;
    }

    private static RealtyGroup groupWithoutDefault(Long id) {
        RealtyGroup g = RealtyGroup.builder()
                .name("Acme Realty").slug("acme").leaderId(42L)
                .build();
        setBaseField(g, "id", id);
        return g;
    }

    private static Auction auction(Long id, User seller) {
        Auction a = Auction.builder()
                .id(id).publicId(UUID.randomUUID())
                .seller(seller)
                .status(AuctionStatus.DRAFT)
                .title("test")
                .build();
        return a;
    }

    private static Auction groupAuction(Long id, User seller, Long groupId) {
        Auction a = auction(id, seller);
        a.setRealtyGroupId(groupId);
        return a;
    }

    /** Reflection helper so we can stamp ids on builder-only entities for the @ManyToOne FK pathways. */
    private static void setBaseField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = null;
            Class<?> c = target.getClass();
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField(name);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                throw new IllegalStateException("No field " + name + " on " + target.getClass());
            }
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // User-source coverage (existing behavior + variant copy)
    // -------------------------------------------------------------------------

    @Test
    void applyTo_userHasBothVariants_copiesBothAndInsertsRow() {
        Auction a = auction(7L, sellerWithBothVariants());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(false);

        service.applyTo(a);

        ArgumentCaptor<AuctionPhoto> cap = ArgumentCaptor.forClass(AuctionPhoto.class);
        verify(repo).save(cap.capture());
        AuctionPhoto saved = cap.getValue();
        assertThat(saved.getSortOrder()).isEqualTo(0);
        assertThat(saved.getSource()).isEqualTo(PhotoSource.USER_DEFAULT_COVER);
        assertThat(saved.getLightObjectKey()).startsWith("listings/7/");
        assertThat(saved.getLightObjectKey()).endsWith(".webp");
        assertThat(saved.getLightContentType()).isEqualTo("image/webp");
        assertThat(saved.getLightSizeBytes()).isEqualTo(123L);
        assertThat(saved.getDarkObjectKey()).startsWith("listings/7/");
        assertThat(saved.getDarkObjectKey()).endsWith(".webp");
        assertThat(saved.getDarkContentType()).isEqualTo("image/webp");
        assertThat(saved.getDarkSizeBytes()).isEqualTo(150L);
        verify(storage).copy(eq("users/42/default-cover-light.webp"), startsWith("listings/7/"));
        verify(storage).copy(eq("users/42/default-cover-dark.webp"), startsWith("listings/7/"));
    }

    @Test
    void applyTo_userHasLightOnly_insertsLightDarkNull() {
        Auction a = auction(7L, sellerLightOnly());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(false);

        service.applyTo(a);

        ArgumentCaptor<AuctionPhoto> cap = ArgumentCaptor.forClass(AuctionPhoto.class);
        verify(repo).save(cap.capture());
        AuctionPhoto saved = cap.getValue();
        assertThat(saved.getSource()).isEqualTo(PhotoSource.USER_DEFAULT_COVER);
        assertThat(saved.getLightObjectKey()).startsWith("listings/7/");
        assertThat(saved.getLightObjectKey()).endsWith(".jpg");
        assertThat(saved.getLightContentType()).isEqualTo("image/jpeg");
        assertThat(saved.getLightSizeBytes()).isEqualTo(123L);
        assertThat(saved.getDarkObjectKey()).isNull();
        assertThat(saved.getDarkContentType()).isNull();
        assertThat(saved.getDarkSizeBytes()).isNull();
        verify(storage).copy(eq("users/42/default-cover-light.jpg"), startsWith("listings/7/"));
    }

    @Test
    void applyTo_userHasDarkOnly_promotesDarkIntoLightSlot() {
        Auction a = auction(7L, sellerDarkOnly());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(false);

        service.applyTo(a);

        ArgumentCaptor<AuctionPhoto> cap = ArgumentCaptor.forClass(AuctionPhoto.class);
        verify(repo).save(cap.capture());
        AuctionPhoto saved = cap.getValue();
        // Dark-only sources are promoted into the light slot (light_* is NOT NULL).
        // The dark slot is left null; the themed renderer falls back to light.
        assertThat(saved.getSource()).isEqualTo(PhotoSource.USER_DEFAULT_COVER);
        assertThat(saved.getLightObjectKey()).startsWith("listings/7/");
        assertThat(saved.getLightObjectKey()).endsWith(".webp");
        assertThat(saved.getLightContentType()).isEqualTo("image/webp");
        assertThat(saved.getLightSizeBytes()).isEqualTo(150L);
        assertThat(saved.getDarkObjectKey()).isNull();
        verify(storage).copy(eq("users/42/default-cover-dark.webp"), startsWith("listings/7/"));
    }

    @Test
    void applyTo_userHasNoCover_isNoOp() {
        Auction a = auction(7L, sellerWithoutCover());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(false);

        service.applyTo(a);

        verify(repo, never()).save(any());
        verify(storage, never()).copy(anyString(), anyString());
    }

    @Test
    void applyTo_userRowAlreadyExists_isIdempotent() {
        Auction a = auction(7L, sellerWithBothVariants());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(true);

        service.applyTo(a);

        verify(repo, never()).save(any());
        verify(storage, never()).copy(anyString(), anyString());
    }

    @Test
    void applyTo_groupRowAlreadyExists_isIdempotent() {
        Auction a = auction(7L, sellerWithBothVariants());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(true);

        service.applyTo(a);

        verify(repo, never()).save(any());
        verify(storage, never()).copy(anyString(), anyString());
    }

    @Test
    void applyTo_lightCopyFails_doesNotInsertRow() {
        Auction a = auction(7L, sellerWithBothVariants());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(false);
        org.mockito.Mockito.doThrow(new RuntimeException("S3 boom"))
                .when(storage).copy(eq("users/42/default-cover-light.webp"), anyString());

        service.applyTo(a);

        verify(repo, never()).save(any());
    }

    @Test
    void applyTo_darkCopyFails_insertsLightOnly() {
        Auction a = auction(7L, sellerWithBothVariants());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(false);
        // Light copy succeeds; dark copy fails.
        org.mockito.Mockito.doThrow(new RuntimeException("dark boom"))
                .when(storage).copy(eq("users/42/default-cover-dark.webp"), anyString());

        service.applyTo(a);

        ArgumentCaptor<AuctionPhoto> cap = ArgumentCaptor.forClass(AuctionPhoto.class);
        verify(repo).save(cap.capture());
        AuctionPhoto saved = cap.getValue();
        assertThat(saved.getSource()).isEqualTo(PhotoSource.USER_DEFAULT_COVER);
        assertThat(saved.getLightObjectKey()).startsWith("listings/7/");
        assertThat(saved.getDarkObjectKey()).isNull();
    }

    // -------------------------------------------------------------------------
    // Group-source coverage (new)
    // -------------------------------------------------------------------------

    @Test
    void applyTo_groupOwnedWithBothVariants_usesGroupSource() {
        Auction a = groupAuction(7L, sellerWithBothVariants(), 99L);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(false);
        when(groupRepo.findById(99L)).thenReturn(Optional.of(groupWithBothVariants(99L)));

        service.applyTo(a);

        ArgumentCaptor<AuctionPhoto> cap = ArgumentCaptor.forClass(AuctionPhoto.class);
        verify(repo).save(cap.capture());
        AuctionPhoto saved = cap.getValue();
        assertThat(saved.getSource()).isEqualTo(PhotoSource.GROUP_DEFAULT_COVER);
        assertThat(saved.getLightObjectKey()).startsWith("listings/7/");
        assertThat(saved.getLightSizeBytes()).isEqualTo(500L);
        assertThat(saved.getDarkObjectKey()).startsWith("listings/7/");
        assertThat(saved.getDarkSizeBytes()).isEqualTo(550L);
        verify(storage).copy(eq("groups/99/default-listing-light.webp"), startsWith("listings/7/"));
        verify(storage).copy(eq("groups/99/default-listing-dark.webp"), startsWith("listings/7/"));
    }

    @Test
    void applyTo_groupOwnedWithLightOnly_usesGroupSourceLightOnly() {
        Auction a = groupAuction(7L, sellerWithBothVariants(), 99L);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(false);
        when(groupRepo.findById(99L)).thenReturn(Optional.of(groupLightOnly(99L)));

        service.applyTo(a);

        ArgumentCaptor<AuctionPhoto> cap = ArgumentCaptor.forClass(AuctionPhoto.class);
        verify(repo).save(cap.capture());
        AuctionPhoto saved = cap.getValue();
        // Group source wins even though the seller has both variants — group
        // is checked first when realtyGroupId is set.
        assertThat(saved.getSource()).isEqualTo(PhotoSource.GROUP_DEFAULT_COVER);
        assertThat(saved.getLightObjectKey()).startsWith("listings/7/");
        assertThat(saved.getLightObjectKey()).endsWith(".jpg");
        assertThat(saved.getDarkObjectKey()).isNull();
        verify(storage).copy(eq("groups/99/default-listing-light.jpg"), startsWith("listings/7/"));
    }

    @Test
    void applyTo_groupOwnedButGroupHasNoDefault_fallsBackToUserSource() {
        Auction a = groupAuction(7L, sellerWithBothVariants(), 99L);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(false);
        when(groupRepo.findById(99L)).thenReturn(Optional.of(groupWithoutDefault(99L)));

        service.applyTo(a);

        ArgumentCaptor<AuctionPhoto> cap = ArgumentCaptor.forClass(AuctionPhoto.class);
        verify(repo).save(cap.capture());
        AuctionPhoto saved = cap.getValue();
        assertThat(saved.getSource()).isEqualTo(PhotoSource.USER_DEFAULT_COVER);
        verify(storage).copy(eq("users/42/default-cover-light.webp"), startsWith("listings/7/"));
        verify(storage).copy(eq("users/42/default-cover-dark.webp"), startsWith("listings/7/"));
    }

    @Test
    void applyTo_groupOwnedAndGroupMissing_fallsBackToUserSource() {
        Auction a = groupAuction(7L, sellerWithBothVariants(), 99L);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(false);
        when(groupRepo.findById(99L)).thenReturn(Optional.empty());

        service.applyTo(a);

        ArgumentCaptor<AuctionPhoto> cap = ArgumentCaptor.forClass(AuctionPhoto.class);
        verify(repo).save(cap.capture());
        AuctionPhoto saved = cap.getValue();
        assertThat(saved.getSource()).isEqualTo(PhotoSource.USER_DEFAULT_COVER);
    }

    @Test
    void applyTo_groupOwnedNoGroupDefaultNoUserDefault_isNoOp() {
        Auction a = groupAuction(7L, sellerWithoutCover(), 99L);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.GROUP_DEFAULT_COVER)).thenReturn(false);
        when(groupRepo.findById(99L)).thenReturn(Optional.of(groupWithoutDefault(99L)));

        service.applyTo(a);

        verify(repo, never()).save(any());
        verify(storage, never()).copy(anyString(), anyString());
    }
}
