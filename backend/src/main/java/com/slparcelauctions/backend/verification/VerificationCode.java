package com.slparcelauctions.backend.verification;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * 6-digit verification code row. Maps the existing {@code verification_codes} table
 * from V2 migration. No schema changes in this sub-spec.
 *
 * <p>{@code userId} is a plain {@code Long}, not a {@code @ManyToOne} — avoids
 * lazy-loading surprises during validation. Services load the {@link com.slparcelauctions.backend.user.User}
 * directly via {@code UserRepository} when they need the full row.
 */
@Entity
@Table(name = "verification_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class VerificationCode extends BaseMutableEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "auction_id")  // nullable by default
    private Long auctionId;

    @Column(nullable = false, length = 6)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationCodeType type;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean used = false;
}
