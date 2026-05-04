package com.slparcelauctions.backend.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Jackson honours the {@code @JsonIgnore} on {@link BaseEntity#id}:
 * serialized entities must emit {@code publicId} and must NOT emit the internal
 * {@code id}.
 *
 * <p>This is a pure unit test — no Spring context required. {@link ParcelTag} is
 * used as the representative entity because it extends {@link BaseMutableEntity}
 * (inheriting both {@code id} and {@code publicId}) and has no lazy associations
 * that would complicate out-of-session serialization.
 *
 * <p>A plain {@code new ObjectMapper()} is sufficient: {@code @JsonIgnore} is a
 * core Jackson annotation and is respected by any mapper instance regardless of
 * Spring Boot auto-configuration.
 */
class PublicIdSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void directEntitySerializationDoesNotLeakInternalId() throws Exception {
        // Construct an in-memory entity — no persist required.
        ParcelTag tag = ParcelTag.builder()
                .code("WATERFRONT")
                .label("Waterfront")
                .category("TERRAIN")
                .build();

        String json = objectMapper.writeValueAsString(tag);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.has("id")).isFalse();          // @JsonIgnore on BaseEntity.id
        assertThat(node.has("publicId")).isTrue();
        assertThat(node.get("publicId").asText()).isNotEmpty();
    }
}
