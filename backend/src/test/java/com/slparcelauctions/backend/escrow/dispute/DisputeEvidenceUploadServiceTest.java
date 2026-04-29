package com.slparcelauctions.backend.escrow.dispute;

import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceImageContentTypeException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceImageTooLargeException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceTooManyImagesException;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DisputeEvidenceUploadServiceTest {

    private ObjectStorageService storage;
    private Clock fixedClock;
    private DisputeEvidenceUploadService service;

    @BeforeEach
    void setUp() {
        storage = mock(ObjectStorageService.class);
        fixedClock = Clock.fixed(Instant.parse("2026-04-27T12:00:00Z"), ZoneOffset.UTC);
        service = new DisputeEvidenceUploadService(storage, fixedClock);
    }

    @Test
    void uploadStoresImageAndReturnsEvidenceImage() {
        MultipartFile png = new MockMultipartFile(
                "file", "shot.png", "image/png", new byte[]{1, 2, 3});
        List<EvidenceImage> result = service.uploadAll(42L, "winner", List.of(png));
        assertThat(result).hasSize(1);
        EvidenceImage img = result.get(0);
        assertThat(img.s3Key()).startsWith("dispute-evidence/42/winner/").endsWith(".png");
        assertThat(img.contentType()).isEqualTo("image/png");
        assertThat(img.size()).isEqualTo(3);
        verify(storage).put(eq(img.s3Key()), eq(new byte[]{1, 2, 3}), eq("image/png"));
    }

    @Test
    void uploadRejectsMoreThanFiveImages() {
        List<MultipartFile> six = List.of(
                fakePng("a.png"), fakePng("b.png"), fakePng("c.png"),
                fakePng("d.png"), fakePng("e.png"), fakePng("f.png"));
        assertThatThrownBy(() -> service.uploadAll(1L, "winner", six))
                .isInstanceOf(EvidenceTooManyImagesException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void uploadRejectsImageOver5Mb() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        MultipartFile huge = new MockMultipartFile(
                "file", "huge.png", "image/png", big);
        assertThatThrownBy(() -> service.uploadAll(1L, "winner", List.of(huge)))
                .isInstanceOf(EvidenceImageTooLargeException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void uploadRejectsNonImageContentType() {
        MultipartFile pdf = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[]{1});
        assertThatThrownBy(() -> service.uploadAll(1L, "winner", List.of(pdf)))
                .isInstanceOf(EvidenceImageContentTypeException.class);
    }

    @Test
    void uploadRejectsSvg() {
        MultipartFile svg = new MockMultipartFile(
                "file", "x.svg", "image/svg+xml", new byte[]{1});
        assertThatThrownBy(() -> service.uploadAll(1L, "winner", List.of(svg)))
                .isInstanceOf(EvidenceImageContentTypeException.class);
    }

    @Test
    void uploadAcceptsJpegPngWebp() {
        service.uploadAll(1L, "winner", List.of(
                new MockMultipartFile("a", "a.jpg", "image/jpeg", new byte[]{1}),
                new MockMultipartFile("b", "b.png", "image/png", new byte[]{1}),
                new MockMultipartFile("c", "c.webp", "image/webp", new byte[]{1})));
        verify(storage, times(3)).put(anyString(), any(byte[].class), anyString());
    }

    @Test
    void uploadEmptyListReturnsEmpty() {
        List<EvidenceImage> result = service.uploadAll(1L, "winner", List.of());
        assertThat(result).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test
    void uploadStampsUploadedAtFromClock() {
        EvidenceImage img = service.uploadAll(7L, "seller",
                List.of(fakePng("x.png"))).get(0);
        assertThat(img.uploadedAt())
                .isEqualTo("2026-04-27T12:00:00Z");
    }

    private static MultipartFile fakePng(String name) {
        return new MockMultipartFile("file", name, "image/png", new byte[]{1});
    }
}
