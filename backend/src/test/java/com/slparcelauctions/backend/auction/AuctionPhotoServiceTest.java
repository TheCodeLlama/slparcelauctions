package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.PhotoLimitExceededException;
import com.slparcelauctions.backend.media.ImageFormat;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.User;

@ExtendWith(MockitoExtension.class)
class AuctionPhotoServiceTest {

    @Mock AuctionService auctionService;
    @Mock AuctionPhotoRepository photoRepo;
    @Mock ListingPhotoProcessor processor;
    @Mock ObjectStorageService storage;

    @InjectMocks AuctionPhotoService service;

    private Auction draftAuction;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "maxPerListing", 10);

        User seller = User.builder().id(42L).email("s@example.com").username("s").build();
        draftAuction = Auction.builder()
                .title("Test listing")
                .id(1L)
                .seller(seller)
                .status(AuctionStatus.DRAFT)
                .build();
    }

    @Test
    void upload_happyPath_storesObjectAndSavesRow() {
        when(auctionService.loadForSeller(1L, 42L)).thenReturn(draftAuction);
        when(photoRepo.countByAuctionId(1L)).thenReturn(0L);
        byte[] processedBytes = new byte[]{1, 2, 3};
        when(processor.process(any(byte[].class)))
                .thenReturn(new ListingPhotoProcessor.ProcessedPhoto(
                        processedBytes, ImageFormat.PNG, processedBytes.length));
        when(photoRepo.save(any(AuctionPhoto.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{10, 20});
        AuctionPhoto saved = service.upload(1L, 42L, file);

        assertThat(saved.getObjectKey()).startsWith("listings/1/");
        assertThat(saved.getObjectKey()).endsWith(".png");
        assertThat(saved.getContentType()).isEqualTo("image/png");
        assertThat(saved.getSortOrder()).isEqualTo(1);
        verify(storage).put(startsWith("listings/1/"), eq(processedBytes), eq("image/png"));
    }

    @Test
    void upload_assignsSortOrderAsCountPlusOne() {
        when(auctionService.loadForSeller(1L, 42L)).thenReturn(draftAuction);
        when(photoRepo.countByAuctionId(1L)).thenReturn(4L);
        when(processor.process(any(byte[].class)))
                .thenReturn(new ListingPhotoProcessor.ProcessedPhoto(
                        new byte[]{1}, ImageFormat.JPEG, 1L));
        when(photoRepo.save(any(AuctionPhoto.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1});
        AuctionPhoto saved = service.upload(1L, 42L, file);

        assertThat(saved.getSortOrder()).isEqualTo(5);
    }

    @Test
    void upload_draftPaidAllowed() {
        Auction paid = Auction.builder().title("Test listing").id(1L).status(AuctionStatus.DRAFT_PAID).build();
        when(auctionService.loadForSeller(1L, 42L)).thenReturn(paid);
        when(photoRepo.countByAuctionId(1L)).thenReturn(0L);
        when(processor.process(any(byte[].class)))
                .thenReturn(new ListingPhotoProcessor.ProcessedPhoto(
                        new byte[]{1}, ImageFormat.PNG, 1L));
        when(photoRepo.save(any(AuctionPhoto.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{1});

        assertThat(service.upload(1L, 42L, file)).isNotNull();
    }

    @Test
    void upload_activeStatus_rejectedWithInvalidState() {
        Auction active = Auction.builder().title("Test listing").id(1L).status(AuctionStatus.ACTIVE).build();
        when(auctionService.loadForSeller(1L, 42L)).thenReturn(active);

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{1});

        assertThatThrownBy(() -> service.upload(1L, 42L, file))
                .isInstanceOf(InvalidAuctionStateException.class);
        verify(storage, never()).put(anyString(), any(), anyString());
        verify(photoRepo, never()).save(any());
    }

    @Test
    void upload_eleventhPhoto_throwsPhotoLimitExceeded() {
        when(auctionService.loadForSeller(1L, 42L)).thenReturn(draftAuction);
        when(photoRepo.countByAuctionId(1L)).thenReturn(10L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{1});

        assertThatThrownBy(() -> service.upload(1L, 42L, file))
                .isInstanceOf(PhotoLimitExceededException.class);
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void delete_happyPath_removesObjectAndRow() {
        AuctionPhoto photo = AuctionPhoto.builder()
                .id(77L)
                .auction(draftAuction)
                .objectKey("listings/1/abc.png")
                .contentType("image/png")
                .sizeBytes(10L)
                .sortOrder(1)
                .build();
        when(auctionService.loadForSeller(1L, 42L)).thenReturn(draftAuction);
        when(photoRepo.findById(77L)).thenReturn(Optional.of(photo));

        service.delete(1L, 77L, 42L);

        verify(storage).delete("listings/1/abc.png");
        verify(photoRepo).delete(photo);
    }

    @Test
    void delete_activeStatus_rejected() {
        Auction active = Auction.builder().title("Test listing").id(1L).status(AuctionStatus.ACTIVE).build();
        when(auctionService.loadForSeller(1L, 42L)).thenReturn(active);

        assertThatThrownBy(() -> service.delete(1L, 77L, 42L))
                .isInstanceOf(InvalidAuctionStateException.class);
        verify(storage, never()).delete(anyString());
        verify(photoRepo, never()).delete(any());
    }

    @Test
    void delete_photoFromDifferentAuction_rejected() {
        Auction otherAuction = Auction.builder().title("Test listing").id(999L).status(AuctionStatus.DRAFT).build();
        AuctionPhoto photo = AuctionPhoto.builder()
                .id(77L).auction(otherAuction).objectKey("listings/999/x.png")
                .contentType("image/png").sizeBytes(1L).sortOrder(1).build();
        when(auctionService.loadForSeller(1L, 42L)).thenReturn(draftAuction);
        when(photoRepo.findById(77L)).thenReturn(Optional.of(photo));

        assertThatThrownBy(() -> service.delete(1L, 77L, 42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
        verify(storage, never()).delete(anyString());
    }
}
