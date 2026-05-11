package com.slparcelauctions.backend.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.media.ImageFormat;
import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;
import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;

/**
 * Default {@link ImageStorageService}. Pipeline per spec §7.3:
 * <ol>
 *   <li>Buffer up to 16 MB from the input stream.</li>
 *   <li>Sniff magic bytes; reject non-raster / non-allow-listed types
 *       with 415 ({@link UnsupportedImageFormatException}). HEIC, SVG,
 *       text, and anything else fall here.</li>
 *   <li>Decode via the shared {@link ImageUploadValidator} (delegates to
 *       ImageIO for PNG/JPEG, Scrimage for WebP).</li>
 *   <li>If the longest axis exceeds the context's effective maxDim,
 *       resize down via Thumbnailator preserving aspect ratio. No
 *       upscale.</li>
 *   <li>Encode to WebP via Scrimage's {@link WebpWriter}. Lossless when
 *       the source carries an alpha channel (typically a logo PNG);
 *       quality-85 lossy otherwise.</li>
 *   <li>Force the object key extension to {@code .webp}; write through to
 *       {@link ObjectStorageService#put} with {@code image/webp}.</li>
 * </ol>
 *
 * <p>Buffer limit (16 MB) is a defense-in-depth cap above the per-caller
 * limits enforced by Spring multipart config. A malicious client cannot
 * stream a multi-GB payload through this code path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageStorageServiceImpl implements ImageStorageService {

    /** Cap on bytes buffered from the input stream. Defense-in-depth
     *  above Spring multipart limits. */
    private static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;

    /** WebP lossy quality used for opaque images. Higher = larger file,
     *  better fidelity. 85 balances visual quality vs. transfer size for
     *  product photos and covers. */
    private static final int LOSSY_QUALITY = 85;

    private final ObjectStorageService objectStorageService;
    private final ImageUploadValidator validator;

    @Override
    public StoredImage storeImage(InputStream in, ImageStorageContext ctx) {
        byte[] inputBytes = readAtMost(in, MAX_INPUT_BYTES);

        // Format sniff + decode via the shared validator. The validator
        // rejects unknown formats (HEIC / SVG / text / etc.) with our
        // standard 415 exception, so we don't need a separate allow-list
        // pass — the supported set (JPEG/PNG/WebP) is the spec allow list.
        ImageUploadValidator.ValidationResult decoded =
                validator.validate(inputBytes, MAX_INPUT_BYTES, 0);

        BufferedImage source = decoded.image();
        int targetMax = ctx.effectiveMaxDim();
        BufferedImage resized;
        if (targetMax > 0 && (source.getWidth() > targetMax || source.getHeight() > targetMax)) {
            try {
                // Thumbnailator preserves aspect ratio when both .size dims
                // match; we want longest-edge clamp so we pass the cap on
                // both axes — it scales to fit within the box.
                resized = Thumbnails.of(source)
                        .size(targetMax, targetMax)
                        .keepAspectRatio(true)
                        .asBufferedImage();
            } catch (IOException e) {
                throw new UnsupportedImageFormatException(
                        "Failed to resize image: " + e.getMessage(), e);
            }
        } else {
            // No upscale — smaller-than-cap inputs pass through untouched.
            resized = source;
        }

        boolean hasAlpha = resized.getColorModel().hasAlpha();
        WebpWriter writer = hasAlpha
                ? WebpWriter.DEFAULT.withLossless()
                : WebpWriter.DEFAULT.withQ(LOSSY_QUALITY);

        byte[] webpBytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImmutableImage.fromAwt(resized).forWriter(writer).write(baos);
            webpBytes = baos.toByteArray();
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

        String finalKey = forceWebpExtension(ctx.objectKey());
        objectStorageService.put(finalKey, webpBytes, "image/webp");

        log.info("Stored image via chokepoint: purpose={} key={} sourceFormat={} "
                + "sourceDim={}x{} finalDim={}x{} webpBytes={} alpha={}",
                ctx.purpose(), finalKey, decoded.format(),
                source.getWidth(), source.getHeight(),
                resized.getWidth(), resized.getHeight(),
                webpBytes.length, hasAlpha);

        return new StoredImage(finalKey, "image/webp", webpBytes.length);
    }

    /**
     * Reads up to {@code limit} bytes from the stream. Throws
     * {@link UnsupportedImageFormatException} (415) if the stream is empty
     * or if reading itself fails. We don't error on hitting the cap —
     * we return whatever we have, and the downstream decode catches a
     * truncated image with the standard 415.
     */
    private static byte[] readAtMost(InputStream in, int limit) {
        if (in == null) {
            throw new UnsupportedImageFormatException("Upload is empty");
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8 * 1024];
            int total = 0;
            int n;
            while (total < limit && (n = in.read(buf, 0, Math.min(buf.length, limit - total))) > 0) {
                baos.write(buf, 0, n);
                total += n;
            }
            if (total == 0) {
                throw new UnsupportedImageFormatException("Upload is empty");
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UnsupportedImageFormatException(
                    "Failed to read upload: " + e.getMessage(), e);
        }
    }

    /**
     * Forces a {@code .webp} extension on the supplied object key. If the
     * key already ends in an image extension recognised by
     * {@link ImageFormat#extension()}, it is replaced; otherwise
     * {@code .webp} is appended.
     */
    static String forceWebpExtension(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Object key must not be null or blank");
        }
        int dot = key.lastIndexOf('.');
        int slash = key.lastIndexOf('/');
        // Only treat a trailing token after the final '/' as an extension.
        if (dot > slash && dot < key.length() - 1) {
            String tail = key.substring(dot + 1).toLowerCase();
            // Replace any common raster extension (including foreign ones
            // like .heic) so a key passed with the original filename ext
            // still comes out clean.
            if (tail.equals("webp") || tail.equals("png") || tail.equals("jpg")
                    || tail.equals("jpeg") || tail.equals("heic") || tail.equals("heif")
                    || tail.equals("gif") || tail.equals("bmp")) {
                return key.substring(0, dot) + ".webp";
            }
        }
        return key + ".webp";
    }
}
