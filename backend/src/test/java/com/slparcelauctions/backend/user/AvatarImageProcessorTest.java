package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

class AvatarImageProcessorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private AvatarImageProcessor processor;

    @BeforeEach
    void setup() {
        processor = new AvatarImageProcessor();
    }

    private byte[] loadFixture(String name) throws IOException {
        return Files.readAllBytes(FIXTURES.resolve(name));
    }

    private BufferedImage decode(byte[] pngBytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(pngBytes));
    }

    @Test
    void process_validPng_producesThreeSizes() throws IOException {
        byte[] input = loadFixture("avatar-valid.png");

        Map<Integer, byte[]> out = processor.process(input);

        assertThat(out).containsOnlyKeys(64, 128, 256);

        BufferedImage small = decode(out.get(64));
        assertThat(small.getWidth()).isEqualTo(64);
        assertThat(small.getHeight()).isEqualTo(64);

        BufferedImage medium = decode(out.get(128));
        assertThat(medium.getWidth()).isEqualTo(128);
        assertThat(medium.getHeight()).isEqualTo(128);

        BufferedImage large = decode(out.get(256));
        assertThat(large.getWidth()).isEqualTo(256);
        assertThat(large.getHeight()).isEqualTo(256);
    }

    @Test
    void process_validJpeg_producesThreeSizes() throws IOException {
        byte[] input = loadFixture("avatar-valid.jpg");

        Map<Integer, byte[]> out = processor.process(input);

        assertThat(out).containsOnlyKeys(64, 128, 256);
        assertThat(decode(out.get(64)).getWidth()).isEqualTo(64);
        assertThat(decode(out.get(128)).getWidth()).isEqualTo(128);
        assertThat(decode(out.get(256)).getWidth()).isEqualTo(256);
    }

    @Test
    void process_validWebp_producesThreeSizes() throws IOException {
        byte[] input = loadFixture("avatar-valid.webp");

        Map<Integer, byte[]> out = processor.process(input);

        assertThat(out).containsOnlyKeys(64, 128, 256);
        // Proves webp-imageio-sejda is on the classpath and SPI-registered.
        assertThat(decode(out.get(256)).getWidth()).isEqualTo(256);
    }

    @Test
    void process_nonSquareInput_centerCrops() throws IOException {
        // avatar-wide.png is 800x400 with a vertical blue band at pixels 380-420
        byte[] input = loadFixture("avatar-wide.png");

        Map<Integer, byte[]> out = processor.process(input);

        BufferedImage cropped = decode(out.get(256));
        assertThat(cropped.getWidth()).isEqualTo(256);
        assertThat(cropped.getHeight()).isEqualTo(256);
        // Center pixel should be blue (from the center band), not white (from the edges).
        int centerPixel = cropped.getRGB(128, 128);
        int blue = centerPixel & 0xFF;
        int green = (centerPixel >> 8) & 0xFF;
        int red = (centerPixel >> 16) & 0xFF;
        assertThat(blue).isGreaterThan(200);
        assertThat(red).isLessThan(100);
        assertThat(green).isLessThan(100);
    }

    @Test
    void process_jpegWithExifRotation_doesNotCrash() throws IOException {
        // avatar-rotated.jpg has EXIF orientation=6. Thumbnailator should handle this
        // internally. The strictest assertion we can make without a perfect color
        // reference is "output dimensions are correct" — if Thumbnailator crashed on
        // EXIF, the method would throw.
        byte[] input = loadFixture("avatar-rotated.jpg");

        Map<Integer, byte[]> out = processor.process(input);

        assertThat(out).containsOnlyKeys(64, 128, 256);
        assertThat(decode(out.get(256)).getWidth()).isEqualTo(256);
    }

    @Test
    void process_invalidBytes_throwsUnsupportedImageFormat() {
        byte[] garbage = new byte[]{0x00, 0x01, 0x02};

        assertThatThrownBy(() -> processor.process(garbage))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void process_bmpFormat_throwsUnsupportedImageFormat() throws IOException {
        // BMP is readable by ImageIO (passes sniff) but not in ALLOWED_FORMATS.
        byte[] input = loadFixture("avatar-invalid.bmp");

        assertThatThrownBy(() -> processor.process(input))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("bmp");
    }

    @Test
    void process_truncatedPng_throwsUnsupportedImageFormat() throws IOException {
        byte[] truncated = loadFixture("avatar-truncated.png");

        assertThatThrownBy(() -> processor.process(truncated))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }
}
