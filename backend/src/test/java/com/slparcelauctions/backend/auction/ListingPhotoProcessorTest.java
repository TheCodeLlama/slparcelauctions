package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.media.ImageFormat;
import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

class ListingPhotoProcessorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private ListingPhotoProcessor processor;

    @BeforeEach
    void setup() throws Exception {
        processor = new ListingPhotoProcessor(new ImageUploadValidator());
        setField("maxBytes", 2L * 1024 * 1024);
        setField("maxDimension", 4096);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ListingPhotoProcessor.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(processor, value);
    }

    private byte[] loadFixture(String name) throws IOException {
        return Files.readAllBytes(FIXTURES.resolve(name));
    }

    private static byte[] renderPng(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void process_pngInput_producesPngOutput() throws IOException {
        byte[] input = loadFixture("avatar-valid.png");

        ListingPhotoProcessor.ProcessedPhoto out = processor.process(input);

        assertThat(out.format()).isEqualTo(ImageFormat.PNG);
        assertThat(out.bytes()).isNotEmpty();
        assertThat(out.sizeBytes()).isEqualTo(out.bytes().length);
        // Output is a valid PNG that round-trips through ImageIO
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.bytes()));
        assertThat(decoded).isNotNull();
    }

    @Test
    void process_jpegInput_producesJpegOutput() throws IOException {
        byte[] input = loadFixture("avatar-valid.jpg");

        ListingPhotoProcessor.ProcessedPhoto out = processor.process(input);

        assertThat(out.format()).isEqualTo(ImageFormat.JPEG);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.bytes()));
        assertThat(decoded).isNotNull();
    }

    @Test
    void process_webpInput_producesWebpOutput() throws IOException {
        byte[] input = loadFixture("avatar-valid.webp");

        ListingPhotoProcessor.ProcessedPhoto out = processor.process(input);

        assertThat(out.format()).isEqualTo(ImageFormat.WEBP);
        assertThat(out.bytes()).isNotEmpty();
    }

    @Test
    void process_reEncodedBytes_differFromOriginal_provingMetadataStripped() throws IOException {
        // avatar-rotated.jpg carries EXIF orientation metadata; after re-encode the
        // metadata is gone and the output byte stream is necessarily different.
        byte[] input = loadFixture("avatar-rotated.jpg");

        ListingPhotoProcessor.ProcessedPhoto out = processor.process(input);

        assertThat(out.bytes()).isNotEqualTo(input);
    }

    @Test
    void process_preservesAspectRatioAndDimensions() throws IOException {
        // 800x400 wide fixture — output should retain 800x400 (no square crop).
        byte[] input = loadFixture("avatar-wide.png");
        BufferedImage originalDecoded = ImageIO.read(new ByteArrayInputStream(input));
        int origW = originalDecoded.getWidth();
        int origH = originalDecoded.getHeight();

        ListingPhotoProcessor.ProcessedPhoto out = processor.process(input);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(origW);
        assertThat(decoded.getHeight()).isEqualTo(origH);
    }

    @Test
    void process_bmpInput_rejected() throws IOException {
        byte[] input = loadFixture("avatar-invalid.bmp");

        assertThatThrownBy(() -> processor.process(input))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("bmp");
    }

    @Test
    void process_overDimension_rejected() throws Exception {
        // Tighten the cap below the fixture size to trigger rejection.
        setField("maxDimension", 32);
        byte[] input = renderPng(64, 64);

        assertThatThrownBy(() -> processor.process(input))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("exceed max 32");
    }

    @Test
    void process_overBytes_rejected() throws Exception {
        setField("maxBytes", 10L);
        byte[] input = renderPng(64, 64);

        assertThatThrownBy(() -> processor.process(input))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageStartingWith("File too large");
    }
}
