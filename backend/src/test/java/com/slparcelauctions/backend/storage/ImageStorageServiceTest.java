package com.slparcelauctions.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.user.exception.ImageTooLargeException;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;
import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;

/**
 * Unit tests for the {@link ImageStorageServiceImpl} chokepoint. The real
 * {@link ImageUploadValidator} is used so the format-sniff allow-list +
 * decoder path is covered end-to-end; only the {@link ObjectStorageService}
 * is mocked so we can assert what hits S3.
 */
class ImageStorageServiceTest {

    private ObjectStorageService storage;
    private ImageStorageServiceImpl service;

    @BeforeEach
    void setup() {
        storage = mock(ObjectStorageService.class);
        service = new ImageStorageServiceImpl(storage, new ImageUploadValidator());
    }

    // ---------- Round-trip success cases ----------

    @Test
    void pngOpaqueRoundTripsToWebp() {
        byte[] png = makeOpaquePngBytes(100, 100);

        StoredImage result = service.storeImage(
                new ByteArrayInputStream(png),
                new ImageStorageContext(ImagePurpose.COVER, "users/1/cover"));

        assertThat(result.contentType()).isEqualTo("image/webp");
        assertThat(result.objectKey()).isEqualTo("users/1/cover.webp");

        ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq("users/1/cover.webp"), bytesCap.capture(), eq("image/webp"));
        byte[] written = bytesCap.getValue();
        assertWebpMagic(written);
        assertThat(result.sizeBytes()).isEqualTo(written.length);
    }

    @Test
    void pngWithAlphaRoundTripsToWebpLossless() {
        byte[] png = makeAlphaPngBytes(64, 64);

        StoredImage result = service.storeImage(
                new ByteArrayInputStream(png),
                new ImageStorageContext(ImagePurpose.LOGO, "groups/abc/logo"));

        assertThat(result.objectKey()).isEqualTo("groups/abc/logo.webp");
        ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq("groups/abc/logo.webp"), bytesCap.capture(), eq("image/webp"));
        assertWebpMagic(bytesCap.getValue());
    }

    @Test
    void jpegRoundTripsToWebp() {
        byte[] jpeg = makeOpaqueImageBytes(120, 90, "jpg");

        StoredImage result = service.storeImage(
                new ByteArrayInputStream(jpeg),
                new ImageStorageContext(ImagePurpose.LISTING_PHOTO, "listings/9/photo"));

        assertThat(result.objectKey()).isEqualTo("listings/9/photo.webp");
        ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq("listings/9/photo.webp"), bytesCap.capture(), eq("image/webp"));
        assertWebpMagic(bytesCap.getValue());
    }

    @Test
    void webpRoundTripsToWebp() {
        byte[] webp = makeWebpBytes(80, 80);

        StoredImage result = service.storeImage(
                new ByteArrayInputStream(webp),
                new ImageStorageContext(ImagePurpose.AVATAR, "avatars/1/256"));

        assertThat(result.objectKey()).isEqualTo("avatars/1/256.webp");
        verify(storage).put(eq("avatars/1/256.webp"), any(byte[].class), eq("image/webp"));
    }

    // ---------- Allow-list rejections ----------

    @Test
    void rejectsTextPayloadWith415() {
        byte[] txt = "hello world".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.storeImage(
                new ByteArrayInputStream(txt),
                new ImageStorageContext(ImagePurpose.AVATAR, "k")))
                .isInstanceOf(UnsupportedImageFormatException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsSvgPayloadWith415() {
        byte[] svg = ("<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\""
                + " width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\"/></svg>")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.storeImage(
                new ByteArrayInputStream(svg),
                new ImageStorageContext(ImagePurpose.LOGO, "k")))
                .isInstanceOf(UnsupportedImageFormatException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsHeicPayloadWith415() {
        // Synthesize an HEIC-shaped file: ISO BMFF box "ftyp" with brand
        // "heic". 4-byte size + "ftyp" + "heic" + 4-byte minor + "mif1heic".
        byte[] heic = new byte[24];
        // size = 24 (big-endian)
        heic[0] = 0; heic[1] = 0; heic[2] = 0; heic[3] = 24;
        // "ftyp"
        heic[4] = 'f'; heic[5] = 't'; heic[6] = 'y'; heic[7] = 'p';
        // major brand "heic"
        heic[8] = 'h'; heic[9] = 'e'; heic[10] = 'i'; heic[11] = 'c';
        // minor version (zeros) at 12..15
        // compat brands: "mif1", "heic"
        heic[16] = 'm'; heic[17] = 'i'; heic[18] = 'f'; heic[19] = '1';
        heic[20] = 'h'; heic[21] = 'e'; heic[22] = 'i'; heic[23] = 'c';

        assertThatThrownBy(() -> service.storeImage(
                new ByteArrayInputStream(heic),
                new ImageStorageContext(ImagePurpose.AVATAR, "k")))
                .isInstanceOf(UnsupportedImageFormatException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsOversizedInputWith413() {
        // 16 MB cap + 1 byte. The probe-byte logic should detect the
        // overflow and throw ImageTooLargeException (413), not silently
        // truncate and 415.
        byte[] tooBig = new byte[16 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> service.storeImage(
                new ByteArrayInputStream(tooBig),
                new ImageStorageContext(ImagePurpose.LISTING_PHOTO, "k")))
                .isInstanceOf(ImageTooLargeException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsEmptyInputWith415() {
        assertThatThrownBy(() -> service.storeImage(
                new ByteArrayInputStream(new byte[0]),
                new ImageStorageContext(ImagePurpose.AVATAR, "k")))
                .isInstanceOf(UnsupportedImageFormatException.class);
        verifyNoInteractions(storage);
    }

    // ---------- Resize semantics ----------

    @Test
    void resizesDownToPurposeMaxDim_avatar256OnLargeInput() {
        byte[] png = makeOpaquePngBytes(1024, 768);

        StoredImage result = service.storeImage(
                new ByteArrayInputStream(png),
                new ImageStorageContext(ImagePurpose.AVATAR, "avatars/1/256"));

        ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq("avatars/1/256.webp"), bytesCap.capture(), eq("image/webp"));
        // Decode the WebP we wrote and assert the longest axis is <= 256.
        BufferedImage out = decodeWebp(bytesCap.getValue());
        assertThat(out.getWidth()).isLessThanOrEqualTo(256);
        assertThat(out.getHeight()).isLessThanOrEqualTo(256);
        assertThat(Math.max(out.getWidth(), out.getHeight())).isEqualTo(256);
        assertThat(result.sizeBytes()).isEqualTo(bytesCap.getValue().length);
    }

    @Test
    void doesNotUpscale_smallInputStaysSmall() {
        byte[] png = makeOpaquePngBytes(100, 100);

        service.storeImage(
                new ByteArrayInputStream(png),
                new ImageStorageContext(ImagePurpose.COVER, "users/2/cover"));

        ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq("users/2/cover.webp"), bytesCap.capture(), eq("image/webp"));
        BufferedImage out = decodeWebp(bytesCap.getValue());
        assertThat(out.getWidth()).isEqualTo(100);
        assertThat(out.getHeight()).isEqualTo(100);
    }

    @Test
    void honorsMaxDimOverride_avatarPipelineCanRequest64() {
        byte[] png = makeOpaquePngBytes(1024, 1024);

        service.storeImage(
                new ByteArrayInputStream(png),
                new ImageStorageContext(ImagePurpose.AVATAR, "avatars/1/64", 64));

        ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(eq("avatars/1/64.webp"), bytesCap.capture(), eq("image/webp"));
        BufferedImage out = decodeWebp(bytesCap.getValue());
        assertThat(out.getWidth()).isEqualTo(64);
        assertThat(out.getHeight()).isEqualTo(64);
    }

    // ---------- Object key extension semantics ----------

    @Test
    void appendsWebpExtensionWhenKeyHasNone() {
        assertThat(ImageStorageServiceImpl.forceWebpExtension("users/abc/avatar/256"))
                .isEqualTo("users/abc/avatar/256.webp");
    }

    @Test
    void replacesExistingExtensionWithWebp() {
        assertThat(ImageStorageServiceImpl.forceWebpExtension("users/1/cover.png"))
                .isEqualTo("users/1/cover.webp");
        assertThat(ImageStorageServiceImpl.forceWebpExtension("listings/9/photo.JPG"))
                .isEqualTo("listings/9/photo.webp");
        assertThat(ImageStorageServiceImpl.forceWebpExtension("things/x.jpeg"))
                .isEqualTo("things/x.webp");
        assertThat(ImageStorageServiceImpl.forceWebpExtension("things/x.heic"))
                .isEqualTo("things/x.webp");
    }

    @Test
    void leavesDotsInDirectoryNamesAlone() {
        // A path like 'foo.bar/baz' has its only dot before the final '/'.
        // We must not strip 'baz' as if it were an extension.
        assertThat(ImageStorageServiceImpl.forceWebpExtension("foo.bar/baz"))
                .isEqualTo("foo.bar/baz.webp");
    }

    @Test
    void idempotentOnAlreadyWebpKey() {
        assertThat(ImageStorageServiceImpl.forceWebpExtension("things/x.webp"))
                .isEqualTo("things/x.webp");
    }

    // ---------- Test fixtures ----------

    private static byte[] makeOpaquePngBytes(int w, int h) {
        return makeOpaqueImageBytes(w, h, "png");
    }

    private static byte[] makeOpaqueImageBytes(int w, int h, String fmt) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(120, 200, 80));
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, fmt, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] makeAlphaPngBytes(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(120, 200, 80, 200));
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] makeWebpBytes(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(40, 40, 200));
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImmutableImage.fromAwt(img).forWriter(WebpWriter.DEFAULT).write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertWebpMagic(byte[] bytes) {
        assertThat(bytes.length).isGreaterThanOrEqualTo(12);
        assertThat(new String(bytes, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(bytes, 8, 4)).isEqualTo("WEBP");
    }

    private static BufferedImage decodeWebp(byte[] bytes) {
        try {
            return ImmutableImage.loader().fromBytes(bytes).awt();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
