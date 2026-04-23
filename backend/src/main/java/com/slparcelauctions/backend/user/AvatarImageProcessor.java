package com.slparcelauctions.backend.user;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;

/**
 * Pure byte[]-in / byte[]-out avatar processor. Validation (format sniffing +
 * decode) is delegated to the shared {@link ImageUploadValidator}; this class
 * retains only the avatar-specific transforms: center-crop to square and
 * resize to three canonical sizes via Thumbnailator, output PNG bytes.
 *
 * <p>Format sniffing trusts the bytes, not the multipart {@code Content-Type}
 * header (which is trivially client-controlled). No byte cap is applied here
 * because Spring's multipart layer enforces the 2MB limit before the request
 * reaches the service. No dimension cap either — Thumbnailator resizes to
 * 64/128/256 regardless of input size.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AvatarImageProcessor {

    public static final int[] SIZES = {64, 128, 256};

    private final ImageUploadValidator validator;

    public Map<Integer, byte[]> process(byte[] inputBytes) {
        ImageUploadValidator.ValidationResult result = validator.validate(inputBytes, 0, 0);
        BufferedImage original = result.image();

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
