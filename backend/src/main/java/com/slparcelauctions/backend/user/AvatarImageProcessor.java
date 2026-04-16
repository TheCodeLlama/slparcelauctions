package com.slparcelauctions.backend.user;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;

/**
 * Pure byte[]-in / byte[]-out image processor. Sniffs format via ImageIO,
 * center-crops to square, resizes to three target sizes, and outputs PNG bytes.
 *
 * <p>Format sniffing trusts the bytes, not the multipart {@code Content-Type}
 * header (which is trivially client-controlled).
 */
@Component
@Slf4j
public class AvatarImageProcessor {

    public static final int[] SIZES = {64, 128, 256};
    private static final Set<String> ALLOWED_FORMATS = Set.of("jpeg", "png", "webp");

    public Map<Integer, byte[]> process(byte[] inputBytes) {
        BufferedImage original;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(inputBytes))) {
            if (iis == null) {
                throw new UnsupportedImageFormatException("Failed to open image stream");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new UnsupportedImageFormatException("Unrecognized image format");
            }
            ImageReader reader = readers.next();
            String formatName = reader.getFormatName().toLowerCase(Locale.ROOT);
            if (!ALLOWED_FORMATS.contains(formatName)) {
                throw new UnsupportedImageFormatException(
                        "Format '" + formatName + "' not allowed. Use JPEG, PNG, or WebP.");
            }
            reader.setInput(iis);
            try {
                original = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("Failed to decode image: " + e.getMessage(), e);
        }

        Map<Integer, byte[]> out = new LinkedHashMap<>(3);
        for (int size : SIZES) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                Thumbnails.of(original)
                        .crop(Positions.CENTER)
                        .size(size, size)
                        .outputFormat("png")
                        .toOutputStream(baos);
                out.put(size, baos.toByteArray());
            } catch (IOException e) {
                throw new UnsupportedImageFormatException(
                        "Failed to resize image to " + size + "px: " + e.getMessage(), e);
            }
        }
        return out;
    }
}
