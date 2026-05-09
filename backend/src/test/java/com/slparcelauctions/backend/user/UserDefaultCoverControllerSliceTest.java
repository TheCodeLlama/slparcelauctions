package com.slparcelauctions.backend.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import com.slparcelauctions.backend.user.dto.UserDefaultCoverDto;

/**
 * Slice tests for {@link UserDefaultCoverController}. The
 * {@link UserDefaultCoverNotFoundException} → 404 mapping is inherited from
 * {@code ResourceNotFoundException} via {@link GlobalExceptionHandler}; this
 * test verifies it lands at the wire.
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

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void getDefaultCover_set_returns200WithDto() throws Exception {
        when(service.get(42L))
                .thenReturn(new UserDefaultCoverDto("https://example/x", "image/jpeg", 100L));

        mockMvc.perform(get("/api/v1/users/me/default-cover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example/x"))
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.sizeBytes").value(100));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void getDefaultCover_unset_returns404() throws Exception {
        when(service.get(42L)).thenThrow(new UserDefaultCoverNotFoundException(42L));

        mockMvc.perform(get("/api/v1/users/me/default-cover"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void putDefaultCover_returns200_andDelegates() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(service.upload(eq(42L), any(MultipartFile.class)))
                .thenReturn(new UserDefaultCoverDto("https://example/y", "image/jpeg", 50L));

        mockMvc.perform(multipart("/api/v1/users/me/default-cover")
                        .file(file)
                        .with(req -> { req.setMethod("PUT"); return req; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example/y"));

        verify(service).upload(eq(42L), any(MultipartFile.class));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void deleteDefaultCover_returns204_andDelegates() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me/default-cover"))
                .andExpect(status().isNoContent());

        verify(service).delete(42L);
    }
}
