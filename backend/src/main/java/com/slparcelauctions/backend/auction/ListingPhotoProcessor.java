package com.slparcelauctions.backend.auction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.media.ImageFormat;
import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;
import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;

import lombok.RequiredArgsConstructor;

/**
 * Validates listing-style photos and re-encodes them to strip metadata. Unlike
 * the avatar processor, this one preserves the input's aspect ratio and
 * dimensions — it only re-encodes through ImageIO (JPEG/PNG) or Scrimage
 * (WebP) so EXIF / IPTC / XMP metadata is dropped in the round-trip. Output
 * format matches input format (JPEG in -> JPEG out, PNG in -> PNG out,
 * WebP in -> WebP out).
 *
 * <p>Used by both the listing-photo upload path
 * ({@link AuctionPhotoService}) and the user default-cover upload path
 * ({@link com.slparcelauctions.backend.user.UserDefaultCoverService}).
 * Both paths share the same byte/dimension caps. If they ever need to
 * diverge, split this into qualified beans — for now the shared instance
 * is the simpler model.
 *
 * <p>Byte-size and dimension caps are enforced via the shared
 * {@link ImageUploadValidator}. Default caps are 2048px on the longest edge
 * and 25 MB on the raw input — small enough that the client-side resize via
 * {@code browser-image-compression} satisfies them on every realistic phone
 * photo, large enough that a malicious client bypassing the resize doesn't
 * OOM the JVM during decode.
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

    @Value("${slpa.photos.max-bytes:26214400}")
    private long maxBytes;

    @Value("${slpa.photos.max-dimension:2048}")
    private int maxDimension;

    public ProcessedPhoto process(byte[] inputBytes) {
        ImageUploadValidator.ValidationResult result =
                validator.validate(inputBytes, maxBytes, maxDimension);

        byte[] out;
        try {
            out = switch (result.format()) {
                case JPEG -> encodeImageIo(result, "jpg");
                case PNG -> encodeImageIo(result, "png");
                case WEBP -> encodeWebp(result);
            };
        } catch (IOException e) {
            throw new UnsupportedImageFormatException(
                    "Failed to re-encode image: " + e.getMessage(), e);
        }
        return new ProcessedPhoto(out, result.format(), out.length);
    }

    private static byte[] encodeImageIo(ImageUploadValidator.ValidationResult result, String writerFormat)
            throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (!ImageIO.write(result.image(), writerFormat, baos)) {
                throw new UnsupportedImageFormatException(
                        "No ImageIO writer registered for format " + result.format());
            }
            return baos.toByteArray();
        }
    }

    private static byte[] encodeWebp(ImageUploadValidator.ValidationResult result) {
        try {
            return ImmutableImage.fromAwt(result.image()).bytes(WebpWriter.DEFAULT);
        } catch (IOException e) {
            throw new UnsupportedImageFormatException(
                    "Failed to encode WebP image: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Scrimage's cwebp subprocess can fail in unchecked ways
            // (binary missing, exec error, etc.); surface as the same
            // unsupported-format reject the controller layer expects.
            throw new UnsupportedImageFormatException(
                    "Failed to encode WebP image: " + e.getMessage(), e);
        }
    }
}
