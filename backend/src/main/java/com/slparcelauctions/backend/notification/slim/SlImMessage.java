package com.slparcelauctions.backend.notification.slim;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(
    name = "sl_im_message",
    indexes = {
        @Index(name = "ix_sl_im_status_created", columnList = "status, created_at"),
        @Index(name = "ix_sl_im_user_status", columnList = "user_id, status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SlImMessage extends BaseMutableEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "avatar_uuid", nullable = false, length = 36)
    private String avatarUuid;

    @Column(name = "coalesce_key", length = 128)
    private String coalesceKey;

    @Column(name = "message_text", nullable = false, length = 1024)
    private String messageText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SlImMessageStatus status;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(nullable = false)
    private int attempts;
}
