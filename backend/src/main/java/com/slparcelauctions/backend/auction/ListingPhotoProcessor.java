package com.slparcelauctions.backend.auction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.media.ImageFormat;
import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.RequiredArgsConstructor;

/**
 * Validates listing photos and re-encodes them to strip metadata. Unlike the
 * avatar processor, this one preserves the input's aspect ratio and
 * dimensions — it only re-encodes through ImageIO so EXIF / IPTC / XMP
 * metadata is dropped in the round-trip. Output format matches input format
 * (JPEG in -> JPEG out, PNG in -> PNG out, WebP in -> WebP out).
 *
 * <p>Byte-size and dimension caps are enforced via the shared
 * {@link ImageUploadValidator}.
 */
@Component
@RequiredArgsConstructor
public class ListingPhotoProcessor {

    /**
     * Result of processing a listing photo — the re-encoded bytes along with
     * the detected format and final byte length.
     */
    public record ProcessedPhoto(byte[] bytes, ImageFormat format, long sizeBytes) {}

    private final ImageUploadValidator validator;

    @Value("${slpa.photos.max-bytes:2097152}")
    private long maxBytes;

    @Value("${slpa.photos.max-dimension:4096}")
    private int maxDimension;

    public ProcessedPhoto process(byte[] inputBytes) {
        ImageUploadValidator.ValidationResult result =
                validator.validate(inputBytes, maxBytes, maxDimension);

        String writerFormat = switch (result.format()) {
            case JPEG -> "jpg";
            case PNG -> "png";
            case WEBP -> "webp";
        };
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (!ImageIO.write(result.image(), writerFormat, baos)) {
                throw new UnsupportedImageFormatException(
                        "No ImageIO writer registered for format " + result.format());
            }
            byte[] out = baos.toByteArray();
            return new ProcessedPhoto(out, result.format(), out.length);
        } catch (IOException e) {
            throw new UnsupportedImageFormatException(
                    "Failed to re-encode image: " + e.getMessage(), e);
        }
    }
}
