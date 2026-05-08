package com.slparcelauctions.backend.sl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Unit tests for {@link SlProfilePhotoService}. Two WireMock servers stand
 * in for {@code world.secondlife.com} and {@code picture-service.secondlife.com};
 * Redis is mocked. Each test asserts both the return value and (where
 * relevant) the cache write.
 */
@ExtendWith(MockitoExtension.class)
class SlProfilePhotoServiceTest {

    private static final UUID AVATAR = UUID.fromString("aa87bc38-c175-427d-b665-02e6838963cc");
    private static final String CACHE_KEY = "sl:profile-photo:v2:" + AVATAR;
    /**
     * A real, decodable 256x192 JPEG. The service's resize step needs
     * ImageIO to be able to round-trip the bytes; a hand-written magic-
     * bytes fixture is rejected. Generated once at class-load.
     */
    private static final byte[] PHOTO_BYTES = makeJpeg(256, 192);

    private static WireMockServer worldWm;
    private static WireMockServer pictureWm;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private SlProfilePhotoService service;

    @BeforeAll
    static void startWireMock() {
        worldWm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        worldWm.start();
        pictureWm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        pictureWm.start();
    }

    @AfterAll
    static void stopWireMock() {
        worldWm.stop();
        pictureWm.stop();
    }

    @AfterEach
    void resetWireMock() {
        worldWm.resetAll();
        pictureWm.resetAll();
    }

    @BeforeEach
    void wireService() {
        // Lenient because the null-uuid early-return test never reads redis;
        // the rest of the suite does, so a global stub is the cleanest shape.
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        WebClient world = WebClient.builder()
                .baseUrl("http://localhost:" + worldWm.port())
                .build();
        WebClient picture = WebClient.builder()
                .baseUrl("http://localhost:" + pictureWm.port())
                .build();
        service = new SlProfilePhotoService(world, picture, redis);
    }

    @Test
    void cacheHitPositive_returnsBytesWithoutHttp() {
        when(valueOps.get(CACHE_KEY))
                .thenReturn(Base64.getEncoder().encodeToString(PHOTO_BYTES));

        Optional<byte[]> result = service.fetchProfilePhoto(AVATAR);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(PHOTO_BYTES);
        worldWm.verify(0, getRequestedFor(urlMatching(".*")));
        pictureWm.verify(0, getRequestedFor(urlMatching(".*")));
    }

    @Test
    void cacheHitNegative_returnsEmptyWithoutHttp() {
        when(valueOps.get(CACHE_KEY)).thenReturn("NONE");

        Optional<byte[]> result = service.fetchProfilePhoto(AVATAR);

        assertThat(result).isEmpty();
        worldWm.verify(0, getRequestedFor(urlMatching(".*")));
        pictureWm.verify(0, getRequestedFor(urlMatching(".*")));
    }

    @Test
    void successfulScrapeAndImageFetch_returnsResizedBytesAndCachesPositive() throws Exception {
        // Stub world.sl: parcelimg with a picture-service src whose path we
        // mirror locally so the bound WebClient resolves it correctly.
        worldWm.stubFor(get(urlPathEqualTo("/resident/" + AVATAR))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(htmlWithImg(
                                "https://picture-service.secondlife.com/photo-uuid/256x192.jpg"))));
        pictureWm.stubFor(get(urlPathEqualTo("/photo-uuid/256x192.jpg"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "image/jpeg")
                        .withBody(PHOTO_BYTES)));
        when(valueOps.get(CACHE_KEY)).thenReturn(null);

        Optional<byte[]> result = service.fetchProfilePhoto(AVATAR);

        assertThat(result).isPresent();
        // Returned bytes are NOT the input — service stretches the 4:3 SL
        // photo back to a square and scales to 512x512 before returning.
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.get()));
        assertThat(decoded.getWidth()).isEqualTo(512);
        assertThat(decoded.getHeight()).isEqualTo(512);
        verify(valueOps).set(eq(CACHE_KEY), anyString(), eq(Duration.ofHours(1)));
    }

    @Test
    void htmlWithoutParcelimg_returnsEmptyAndCachesNegative() {
        worldWm.stubFor(get(urlPathEqualTo("/resident/" + AVATAR))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body><p>no parcelimg here</p></body></html>")));
        when(valueOps.get(CACHE_KEY)).thenReturn(null);

        Optional<byte[]> result = service.fetchProfilePhoto(AVATAR);

        assertThat(result).isEmpty();
        verify(valueOps).set(CACHE_KEY, "NONE", Duration.ofMinutes(5));
    }

    @Test
    void worldSlReturns404_returnsEmptyAndCachesNegative() {
        worldWm.stubFor(get(urlPathEqualTo("/resident/" + AVATAR))
                .willReturn(aResponse().withStatus(404)));
        when(valueOps.get(CACHE_KEY)).thenReturn(null);

        Optional<byte[]> result = service.fetchProfilePhoto(AVATAR);

        assertThat(result).isEmpty();
        verify(valueOps).set(CACHE_KEY, "NONE", Duration.ofMinutes(5));
    }

    @Test
    void pictureServiceReturns500_returnsEmptyAndCachesNegative() {
        worldWm.stubFor(get(urlPathEqualTo("/resident/" + AVATAR))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(htmlWithImg(
                                "https://picture-service.secondlife.com/photo-uuid/256x192.jpg"))));
        pictureWm.stubFor(get(urlPathEqualTo("/photo-uuid/256x192.jpg"))
                .willReturn(aResponse().withStatus(500)));
        when(valueOps.get(CACHE_KEY)).thenReturn(null);

        Optional<byte[]> result = service.fetchProfilePhoto(AVATAR);

        assertThat(result).isEmpty();
        verify(valueOps).set(CACHE_KEY, "NONE", Duration.ofMinutes(5));
    }

    @Test
    void srcOutsideAllowList_returnsEmptyAndDoesNotFetch() {
        worldWm.stubFor(get(urlPathEqualTo("/resident/" + AVATAR))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(htmlWithImg("https://evil.example.com/photo.jpg"))));
        when(valueOps.get(CACHE_KEY)).thenReturn(null);

        Optional<byte[]> result = service.fetchProfilePhoto(AVATAR);

        assertThat(result).isEmpty();
        // picture-service mock should not have been hit at all.
        pictureWm.verify(0, getRequestedFor(urlMatching(".*")));
        verify(valueOps).set(CACHE_KEY, "NONE", Duration.ofMinutes(5));
    }

    @Test
    void nullAvatarUuid_returnsEmptyImmediately() {
        Optional<byte[]> result = service.fetchProfilePhoto(null);

        assertThat(result).isEmpty();
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void corruptedPositiveCacheEntry_falsThroughToScrape() {
        when(valueOps.get(CACHE_KEY)).thenReturn("not-valid-base64!!@@");
        worldWm.stubFor(get(urlPathEqualTo("/resident/" + AVATAR))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(htmlWithImg(
                                "https://picture-service.secondlife.com/photo-uuid/256x192.jpg"))));
        pictureWm.stubFor(get(urlPathEqualTo("/photo-uuid/256x192.jpg"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "image/jpeg")
                        .withBody(PHOTO_BYTES)));

        Optional<byte[]> result = service.fetchProfilePhoto(AVATAR);

        assertThat(result).isPresent();
        worldWm.verify(exactly(1), getRequestedFor(urlPathEqualTo("/resident/" + AVATAR)));
    }

    /** Generates a real, decodable JPEG of the requested dimensions. */
    private static byte[] makeJpeg(int width, int height) {
        try {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            // Solid-color content — service decodes by dimensions, not pixels.
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    img.setRGB(x, y, 0x808080);
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ImageIO.write(img, "jpeg", baos)) {
                throw new IllegalStateException("ImageIO.write returned false");
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate test JPEG", e);
        }
    }

    private static String htmlWithImg(String src) {
        return """
                <!DOCTYPE html>
                <html><head>
                  <meta name="imageid" content="dff17403-2c74-ec3e-385d-6885345279fd">
                </head><body>
                  <div class="img">
                    <img alt="profile image"
                         src="%s"
                         class="parcelimg"
                         height="192" width="192">
                  </div>
                </body></html>
                """.formatted(src);
    }
}
