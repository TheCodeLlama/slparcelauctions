package com.slparcelauctions.backend.admin.infrastructure.withdrawals;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;

@Entity
@Table(name = "withdrawals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Withdrawal extends BaseMutableEntity {

    @Column(nullable = false)
    private Long amount;

    @Column(name = "recipient_uuid", nullable = false, length = 36)
    private String recipientUuid;

    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;

    @Column(length = 1000)
    private String notes;

    @Column(name = "terminal_command_id")
    private Long terminalCommandId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WithdrawalStatus status;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;
}
