package com.slparcelauctions.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;

/**
 * Probe test that verifies the WebP encoder path is wired and produces a
 * valid RIFF/WEBP container on the platform the test runs on (Windows dev
 * box + Linux CI in Docker). The {@link ImageStorageService} migration in
 * Phase 9 of the realty-groups slice relies on Scrimage's {@link WebpWriter}
 * — which shells out to the bundled {@code cwebp} subprocess binary — being
 * able to encode an arbitrary {@link BufferedImage}.
 *
 * <p>The encoder dependency is already on the classpath via
 * {@code com.sksamuel.scrimage:scrimage-webp:4.3.2}; this test guards
 * against a regression that would silently break every image upload in the
 * system (avatars, default covers, listing photos, dispute evidence,
 * realty-group logos + covers).
 */
class WebpEncoderProbeTest {

    @Test
    void encodesArbitraryBufferedImageToValidWebpContainer() throws Exception {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(120, 200, 80));
            g.fillRect(0, 0, 16, 16);
        } finally {
            g.dispose();
        }

        byte[] webp;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImmutableImage.fromAwt(img).forWriter(WebpWriter.DEFAULT).write(baos);
            webp = baos.toByteArray();
        }

        assertThat(webp).isNotEmpty();
        assertThat(webp.length).isGreaterThanOrEqualTo(12);
        // RIFF container magic — bytes 0..3 spell "RIFF".
        assertThat(new String(webp, 0, 4)).isEqualTo("RIFF");
        // WebP form-type identifier — bytes 8..11 spell "WEBP".
        assertThat(new String(webp, 8, 4)).isEqualTo("WEBP");
    }
}
