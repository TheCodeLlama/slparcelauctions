package com.slparcelauctions.backend.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

class ImageUploadValidatorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private ImageUploadValidator validator;

    @BeforeEach
    void setup() {
        validator = new ImageUploadValidator();
    }

    private byte[] loadFixture(String name) throws IOException {
        return Files.readAllBytes(FIXTURES.resolve(name));
    }

    private static byte[] buildPng(int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void validate_png_returnsPngFormatAndDimensions() throws IOException {
        byte[] bytes = loadFixture("avatar-valid.png");

        ImageUploadValidator.ValidationResult result = validator.validate(bytes, 0, 0);

        assertThat(result.format()).isEqualTo(ImageFormat.PNG);
        assertThat(result.image()).isNotNull();
        assertThat(result.width()).isGreaterThan(0);
        assertThat(result.height()).isGreaterThan(0);
    }

    @Test
    void validate_jpeg_returnsJpegFormat() throws IOException {
        byte[] bytes = loadFixture("avatar-valid.jpg");

        ImageUploadValidator.ValidationResult result = validator.validate(bytes, 0, 0);

        assertThat(result.format()).isEqualTo(ImageFormat.JPEG);
    }

    @Test
    void validate_webp_returnsWebpFormat() throws IOException {
        byte[] bytes = loadFixture("avatar-valid.webp");

        ImageUploadValidator.ValidationResult result = validator.validate(bytes, 0, 0);

        assertThat(result.format()).isEqualTo(ImageFormat.WEBP);
    }

    @Test
    void validate_bmp_rejectedAsUnsupportedFormat() throws IOException {
        byte[] bytes = loadFixture("avatar-invalid.bmp");

        assertThatThrownBy(() -> validator.validate(bytes, 0, 0))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("bmp");
    }

    @Test
    void validate_oversizedBytes_rejectedWithFileTooLargePrefix() throws IOException {
        byte[] bytes = loadFixture("avatar-valid.png");

        assertThatThrownBy(() -> validator.validate(bytes, 10L, 0))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageStartingWith("File too large");
    }

    @Test
    void validate_overDimension_rejected() throws IOException {
        // avatar-valid.png fixture is >128 px; cap at 64 forces a dimension reject.
        byte[] bytes = loadFixture("avatar-valid.png");

        assertThatThrownBy(() -> validator.validate(bytes, 0, 64))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("exceed max 64");
    }

    @Test
    void validate_maxBytesZero_noByteCap() throws IOException {
        byte[] bytes = loadFixture("avatar-valid.png");

        // maxBytes=0 must disable the check; no exception expected.
        assertThat(validator.validate(bytes, 0, 0)).isNotNull();
    }

    @Test
    void validate_maxDimensionZero_noDimensionCap() throws IOException {
        byte[] bytes = loadFixture("avatar-valid.png");

        assertThat(validator.validate(bytes, 0, 0)).isNotNull();
    }

    @Test
    void validate_emptyBytes_rejected() {
        assertThatThrownBy(() -> validator.validate(new byte[0], 0, 0))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void validate_garbageBytes_rejected() {
        assertThatThrownBy(() -> validator.validate(new byte[]{0x00, 0x01, 0x02}, 0, 0))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void validate_pngWithinCaps_passes() throws IOException {
        byte[] bytes = buildPng(128, 64);

        ImageUploadValidator.ValidationResult result = validator.validate(bytes, 100_000L, 512);

        assertThat(result.format()).isEqualTo(ImageFormat.PNG);
        assertThat(result.width()).isEqualTo(128);
        assertThat(result.height()).isEqualTo(64);
    }
}
