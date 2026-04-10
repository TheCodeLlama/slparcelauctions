package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createUser_persistsHashedPasswordAndDefaultJsonbPrefs() throws Exception {
        CreateUserRequest request = new CreateUserRequest(
                "integration+create@example.com", "password123", "Integration User");

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value("integration+create@example.com"))
                .andExpect(jsonPath("$.displayName").value("Integration User"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.notifyEmail.bidding").value(true))
                .andExpect(jsonPath("$.notifyEmail.marketing").value(false))
                .andExpect(jsonPath("$.notifySlIm.reviews").value(false));

        Optional<User> persisted = userRepository.findByEmail("integration+create@example.com");
        assertThat(persisted).isPresent();
        User user = persisted.get();
        assertThat(user.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", user.getPasswordHash())).isTrue();
        assertThat(user.getNotifyEmail()).containsEntry("bidding", true);
        assertThat(user.getNotifySlIm()).containsEntry("auction_result", true);
        assertThat(user.getCreatedAt()).isNotNull();
    }

    @Test
    void createUser_duplicateEmail_returns409() throws Exception {
        CreateUserRequest first = new CreateUserRequest(
                "integration+dup@example.com", "password123", null);
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isConflict());
    }

    @Test
    void getUserProfile_returnsPublicView() throws Exception {
        User user = userRepository.save(User.builder()
                .email("integration+profile@example.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .displayName("Profile User")
                .bio("about me")
                .build());
        userRepository.flush();

        mockMvc.perform(get("/api/users/" + user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.displayName").value("Profile User"))
                .andExpect(jsonPath("$.bio").value("about me"))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void getUserProfile_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/users/9999999"))
                .andExpect(status().isNotFound());
    }
}
