package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import java.time.OffsetDateTime;

/**
 * DB-backed refresh token row. The raw token value is NEVER stored — only its SHA-256 hash
 * (see {@link TokenHasher#sha256Hex(String)}). The raw value exists only in the HttpOnly cookie
 * on the wire and in the client's cookie jar. If the DB leaks, tokens leak as hashes, not usable
 * credentials. See FOOTGUNS §B.8.
 *
 * <p>The unique index on {@code token_hash} is the lookup path; the composite index on
 * {@code (user_id, revoked_at)} supports the {@code revokeAllByUserId} cascade and future
 * "active sessions per user" queries.
 *
 * <p>{@code user_id} is a plain {@code Long}, not a JPA relationship, to keep slice boundaries
 * loosely coupled. Joins happen at the service layer when needed.
 */
@Entity
@Table(
    name = "refresh_tokens",
    indexes = {
        @Index(name = "idx_refresh_tokens_user_active", columnList = "user_id, revoked_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RefreshToken extends BaseMutableEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;
}
