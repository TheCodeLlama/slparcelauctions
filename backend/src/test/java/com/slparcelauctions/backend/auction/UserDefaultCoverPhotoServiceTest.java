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

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.user.User;

class UserDefaultCoverPhotoServiceTest {

    private AuctionPhotoRepository repo;
    private ObjectStorageService storage;
    private UserDefaultCoverPhotoService service;

    @BeforeEach
    void setup() {
        repo = mock(AuctionPhotoRepository.class);
        storage = mock(ObjectStorageService.class);
        service = new UserDefaultCoverPhotoService(repo, storage);
    }

    private User sellerWithCover() {
        User seller = User.builder()
                .id(42L).publicId(UUID.randomUUID())
                .email("a@b.c").username("alice").passwordHash("x")
                .build();
        seller.setDefaultCoverLightObjectKey("users/42/default-cover-abc.jpg");
        seller.setDefaultCoverLightContentType("image/jpeg");
        seller.setDefaultCoverLightSizeBytes(123L);
        return seller;
    }

    private User sellerWithoutCover() {
        return User.builder()
                .id(42L).publicId(UUID.randomUUID())
                .email("a@b.c").username("alice").passwordHash("x")
                .build();
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

    @Test
    void applyTo_userHasCover_insertsAtSortOrder0() {
        Auction a = auction(7L, sellerWithCover());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(storage.get("users/42/default-cover-abc.jpg"))
                .thenReturn(new StoredObject(new byte[]{1, 2, 3}, "image/jpeg", 3L));

        service.applyTo(a);

        ArgumentCaptor<AuctionPhoto> cap = ArgumentCaptor.forClass(AuctionPhoto.class);
        verify(repo).save(cap.capture());
        AuctionPhoto saved = cap.getValue();
        assertThat(saved.getSortOrder()).isEqualTo(0);
        assertThat(saved.getSource()).isEqualTo(PhotoSource.USER_DEFAULT_COVER);
        assertThat(saved.getLightObjectKey()).startsWith("listings/7/");
        assertThat(saved.getLightObjectKey()).endsWith(".jpg");
        assertThat(saved.getLightContentType()).isEqualTo("image/jpeg");
        assertThat(saved.getLightSizeBytes()).isEqualTo(123L);
        verify(storage).put(startsWith("listings/7/"), eq(new byte[]{1, 2, 3}), eq("image/jpeg"));
    }

    @Test
    void applyTo_userHasNoCover_isNoOp() {
        Auction a = auction(7L, sellerWithoutCover());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);

        service.applyTo(a);

        verify(repo, never()).save(any());
        verify(storage, never()).get(anyString());
        verify(storage, never()).put(anyString(), any(byte[].class), anyString());
    }

    @Test
    void applyTo_alreadyApplied_isIdempotent() {
        Auction a = auction(7L, sellerWithCover());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(true);

        service.applyTo(a);

        verify(repo, never()).save(any());
        verify(storage, never()).get(anyString());
        verify(storage, never()).put(anyString(), any(byte[].class), anyString());
    }

    @Test
    void applyTo_s3GetFails_logsAndDoesNotInsertRow() {
        Auction a = auction(7L, sellerWithCover());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(storage.get(anyString())).thenThrow(new RuntimeException("S3 boom"));

        service.applyTo(a);

        verify(repo, never()).save(any());
        verify(storage, never()).put(anyString(), any(byte[].class), anyString());
    }

    @Test
    void applyTo_s3PutFails_logsAndDoesNotInsertRow() {
        Auction a = auction(7L, sellerWithCover());
        when(repo.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER)).thenReturn(false);
        when(storage.get(anyString())).thenReturn(new StoredObject(new byte[]{1}, "image/jpeg", 1L));
        org.mockito.Mockito.doThrow(new RuntimeException("S3 boom"))
                .when(storage).put(anyString(), any(byte[].class), anyString());

        service.applyTo(a);

        verify(repo, never()).save(any());
    }
}
