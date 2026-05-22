package com.slparcelauctions.backend.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;
import com.slparcelauctions.backend.common.image.ImageVariant;
import com.slparcelauctions.backend.user.dto.UserDefaultCoverDto;

/**
 * Slice tests for {@link UserDefaultCoverController}. Covers the variant
 * path-param surface from plan Task 4 of theme-image-variants. The
 * {@link UserDefaultCoverNotFoundException} → 404 mapping is inherited from
 * {@code ResourceNotFoundException} via {@link GlobalExceptionHandler}; the
 * {@code InvalidVariantException} → {@code 400 INVALID_VARIANT} mapping is
 * also inherited from the global handler.
 */
@WebMvcTest(controllers = UserDefaultCoverController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class})
class UserDefaultCoverControllerSliceTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private UserDefaultCoverService service;
    @MockitoBean private JwtService jwtService;
    // JwtAuthenticationFilter is a @Component that pulls a UserRepository bean
    // even though addFilters=false excludes it from the request chain. Without
    // this, the slice context fails to start.
    @MockitoBean private UserRepository userRepository;

    // ─────────────────────── GET happy paths ───────────────────────

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void getDefaultCover_light_set_returns200WithDto() throws Exception {
        when(service.get(42L, ImageVariant.LIGHT))
                .thenReturn(new UserDefaultCoverDto("https://example/light", "image/webp", 100L));

        mockMvc.perform(get("/api/v1/users/me/default-cover/light"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example/light"))
                .andExpect(jsonPath("$.contentType").value("image/webp"))
                .andExpect(jsonPath("$.sizeBytes").value(100));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void getDefaultCover_dark_set_returns200WithDto() throws Exception {
        when(service.get(42L, ImageVariant.DARK))
                .thenReturn(new UserDefaultCoverDto("https://example/dark", "image/webp", 50L));

        mockMvc.perform(get("/api/v1/users/me/default-cover/dark"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example/dark"))
                .andExpect(jsonPath("$.contentType").value("image/webp"))
                .andExpect(jsonPath("$.sizeBytes").value(50));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void getDefaultCover_caseInsensitiveVariantToken_returns200() throws Exception {
        when(service.get(42L, ImageVariant.LIGHT))
                .thenReturn(new UserDefaultCoverDto("https://example/light", "image/webp", 100L));

        mockMvc.perform(get("/api/v1/users/me/default-cover/LIGHT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example/light"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void getDefaultCover_unset_returns404() throws Exception {
        when(service.get(42L, ImageVariant.LIGHT))
                .thenThrow(new UserDefaultCoverNotFoundException(42L));

        mockMvc.perform(get("/api/v1/users/me/default-cover/light"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void getDefaultCover_invalidVariant_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/default-cover/sepia"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VARIANT"))
                .andExpect(jsonPath("$.value").value("sepia"));

        verifyNoInteractions(service);
    }

    // ─────────────────────── POST happy paths ───────────────────────

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void postDefaultCover_light_returns200_andDelegates() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(service.upload(eq(42L), eq(ImageVariant.LIGHT), any(MultipartFile.class)))
                .thenReturn(new UserDefaultCoverDto("https://example/y", "image/webp", 50L));

        mockMvc.perform(multipart("/api/v1/users/me/default-cover/light").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example/y"));

        verify(service).upload(eq(42L), eq(ImageVariant.LIGHT), any(MultipartFile.class));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void postDefaultCover_dark_returns200_andDelegates() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(service.upload(eq(42L), eq(ImageVariant.DARK), any(MultipartFile.class)))
                .thenReturn(new UserDefaultCoverDto("https://example/d", "image/webp", 77L));

        mockMvc.perform(multipart("/api/v1/users/me/default-cover/dark").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example/d"))
                .andExpect(jsonPath("$.sizeBytes").value(77));

        verify(service).upload(eq(42L), eq(ImageVariant.DARK), any(MultipartFile.class));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void postDefaultCover_invalidVariant_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.jpg", "image/jpeg", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/users/me/default-cover/sepia").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VARIANT"))
                .andExpect(jsonPath("$.value").value("sepia"));

        verifyNoInteractions(service);
    }

    // ─────────────────────── DELETE happy paths ───────────────────────

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void deleteDefaultCover_light_returns204_andDelegates() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me/default-cover/light"))
                .andExpect(status().isNoContent());

        verify(service).delete(42L, ImageVariant.LIGHT);
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void deleteDefaultCover_dark_returns204_andDelegates() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me/default-cover/dark"))
                .andExpect(status().isNoContent());

        verify(service).delete(42L, ImageVariant.DARK);
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void deleteDefaultCover_invalidVariant_returns400() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me/default-cover/sepia"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VARIANT"))
                .andExpect(jsonPath("$.value").value("sepia"));

        verifyNoInteractions(service);
    }
}
