package com.slparcelauctions.backend.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared bytes-level image validation used by both {@code AvatarImageProcessor}
 * and {@code ListingPhotoProcessor}. Sniffs the format via ImageIO (trusting
 * the actual bytes, not the multipart {@code Content-Type} header which is
 * trivially client-controlled) and decodes the image so callers can work on
 * a {@link BufferedImage}.
 *
 * <p>The returned {@link ValidationResult} also carries the detected format
 * and pre-decoded dimensions so downstream processors avoid redundant I/O.
 */
@Component
@Slf4j
public class ImageUploadValidator {

    /**
     * Result of a successful validation. The {@code image} field is a fully
     * decoded {@link BufferedImage} suitable for re-encoding or thumbnailing.
     */
    public record ValidationResult(ImageFormat format, BufferedImage image, int width, int height) {}

    /**
     * Validates the upload bytes and returns a {@link ValidationResult} on
     * success. Rejects unsupported formats, oversized byte counts, and
     * images exceeding the per-axis dimension cap.
     *
     * @param inputBytes   raw upload bytes
     * @param maxBytes     upper byte-size limit; {@code 0} disables the check
     * @param maxDimension per-axis pixel cap; {@code 0} disables the check
     * @throws UnsupportedImageFormatException on any validation failure
     */
    public ValidationResult validate(byte[] inputBytes, long maxBytes, int maxDimension) {
        if (inputBytes == null || inputBytes.length == 0) {
            throw new UnsupportedImageFormatException("Upload is empty");
        }
        if (maxBytes > 0 && inputBytes.length > maxBytes) {
            throw new UnsupportedImageFormatException(
                    "File too large: " + inputBytes.length + " bytes > " + maxBytes);
        }

        BufferedImage image;
        ImageFormat format;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(inputBytes))) {
            if (iis == null) {
                throw new UnsupportedImageFormatException("Failed to open image stream");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new UnsupportedImageFormatException("Unrecognized image format");
            }
            ImageReader reader = readers.next();
            String readerFormatName = reader.getFormatName();
            format = ImageFormat.fromImageIoName(readerFormatName)
                    .orElseThrow(() -> new UnsupportedImageFormatException(
                            "Format '" + readerFormatName + "' not allowed. Use JPEG, PNG, or WebP."));
            reader.setInput(iis);
            try {
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UnsupportedImageFormatException("Failed to decode image: " + e.getMessage(), e);
        }

        if (image == null) {
            throw new UnsupportedImageFormatException("Image decoder returned null");
        }

        int width = image.getWidth();
        int height = image.getHeight();
        if (maxDimension > 0 && (width > maxDimension || height > maxDimension)) {
            throw new UnsupportedImageFormatException(
                    "Image dimensions " + width + "x" + height + " exceed max " + maxDimension);
        }
        return new ValidationResult(format, image, width, height);
    }
}
