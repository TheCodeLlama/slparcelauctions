package com.slparcelauctions.backend.escrow.dispute;

import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceImageContentTypeException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceImageTooLargeException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceTooManyImagesException;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.storage.ImagePurpose;
import com.slparcelauctions.backend.storage.ImageStorageContext;
import com.slparcelauctions.backend.storage.ImageStorageService;
import com.slparcelauctions.backend.storage.StoredImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DisputeEvidenceUploadServiceTest {

    private ImageStorageService imageStorage;
    private Clock fixedClock;
    private DisputeEvidenceUploadService service;

    /** Escrow config with the dispute-cap defaults (5 images / 5 MiB) filled
     *  in by the record's compact constructor. */
    private static EscrowConfigProperties defaultEscrowConfig() {
        return new EscrowConfigProperties("dispute-evidence-test-secret",
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        imageStorage = mock(ImageStorageService.class);
        fixedClock = Clock.fixed(Instant.parse("2026-04-27T12:00:00Z"), ZoneOffset.UTC);
        service = new DisputeEvidenceUploadService(
                imageStorage, fixedClock, defaultEscrowConfig());
    }

    /** Echoes the caller's key + ".webp" as if the chokepoint encoded
     *  the input. Uses the requested size for the response sizeBytes. */
    private void stubChokepointEcho(long sizeBytes) {
        when(imageStorage.storeImage(any(), any(ImageStorageContext.class)))
                .thenAnswer(inv -> {
                    ImageStorageContext ctx = inv.getArgument(1);
                    return new StoredImage(
                            ctx.objectKey() + ".webp", "image/webp", sizeBytes);
                });
    }

    @Test
    void uploadStoresImageViaChokepointAndReturnsEvidenceImage() {
        MultipartFile png = new MockMultipartFile(
                "file", "shot.png", "image/png", new byte[]{1, 2, 3});
        stubChokepointEcho(99L);

        List<EvidenceImage> result = service.uploadAll(42L, "winner", List.of(png));

        assertThat(result).hasSize(1);
        EvidenceImage img = result.get(0);
        // Chokepoint key has .webp extension; the caller passed in a key
        // sans extension under "dispute-evidence/{escrowId}/{role}/".
        assertThat(img.s3Key()).startsWith("dispute-evidence/42/winner/").endsWith(".webp");
        assertThat(img.contentType()).isEqualTo("image/webp");
        assertThat(img.size()).isEqualTo(99L);

        ArgumentCaptor<ImageStorageContext> ctxCap =
                ArgumentCaptor.forClass(ImageStorageContext.class);
        verify(imageStorage).storeImage(any(), ctxCap.capture());
        assertThat(ctxCap.getValue().purpose()).isEqualTo(ImagePurpose.DISPUTE_EVIDENCE);
        assertThat(ctxCap.getValue().objectKey())
                .startsWith("dispute-evidence/42/winner/")
                .doesNotContain(".");
    }

    @Test
    void uploadRejectsMoreThanFiveImages() {
        List<MultipartFile> six = List.of(
                fakePng("a.png"), fakePng("b.png"), fakePng("c.png"),
                fakePng("d.png"), fakePng("e.png"), fakePng("f.png"));
        assertThatThrownBy(() -> service.uploadAll(1L, "winner", six))
                .isInstanceOf(EvidenceTooManyImagesException.class);
        verifyNoInteractions(imageStorage);
    }

    @Test
    void uploadRejectsImageOver5Mb() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        MultipartFile huge = new MockMultipartFile(
                "file", "huge.png", "image/png", big);
        assertThatThrownBy(() -> service.uploadAll(1L, "winner", List.of(huge)))
                .isInstanceOf(EvidenceImageTooLargeException.class);
        verifyNoInteractions(imageStorage);
    }

    @Test
    void uploadRejectsNonImageContentType() {
        MultipartFile pdf = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[]{1});
        assertThatThrownBy(() -> service.uploadAll(1L, "winner", List.of(pdf)))
                .isInstanceOf(EvidenceImageContentTypeException.class);
        verifyNoInteractions(imageStorage);
    }

    @Test
    void uploadRejectsSvg() {
        MultipartFile svg = new MockMultipartFile(
                "file", "x.svg", "image/svg+xml", new byte[]{1});
        assertThatThrownBy(() -> service.uploadAll(1L, "winner", List.of(svg)))
                .isInstanceOf(EvidenceImageContentTypeException.class);
        verifyNoInteractions(imageStorage);
    }

    @Test
    void uploadAcceptsJpegPngWebp() {
        stubChokepointEcho(1L);
        service.uploadAll(1L, "winner", List.of(
                new MockMultipartFile("a", "a.jpg", "image/jpeg", new byte[]{1}),
                new MockMultipartFile("b", "b.png", "image/png", new byte[]{1}),
                new MockMultipartFile("c", "c.webp", "image/webp", new byte[]{1})));
        verify(imageStorage, times(3)).storeImage(any(), any(ImageStorageContext.class));
    }

    @Test
    void uploadEmptyListReturnsEmpty() {
        List<EvidenceImage> result = service.uploadAll(1L, "winner", List.of());
        assertThat(result).isEmpty();
        verifyNoInteractions(imageStorage);
    }

    @Test
    void uploadStampsUploadedAtFromClock() {
        stubChokepointEcho(1L);
        EvidenceImage img = service.uploadAll(7L, "seller",
                List.of(fakePng("x.png"))).get(0);
        assertThat(img.uploadedAt())
                .isEqualTo("2026-04-27T12:00:00Z");
    }

    private static MultipartFile fakePng(String name) {
        return new MockMultipartFile("file", name, "image/png", new byte[]{1});
    }
}
