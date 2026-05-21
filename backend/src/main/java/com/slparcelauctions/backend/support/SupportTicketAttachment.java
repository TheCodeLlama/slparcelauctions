package com.slparcelauctions.backend.support;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.slparcelauctions.backend.common.BaseMutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * One image attachment on a {@link SupportTicketMessage}. Up to three
 * may be attached per message (enforced at the service layer).
 *
 * <p>{@code storageKey} is the S3 object key after promotion from the
 * {@code support-attachments/pending/} prefix into the message-scoped
 * path. Width / height are snapshotted post-validation so the frontend
 * can size thumbnails without re-reading the file.
 */
@Entity
@Table(name = "support_ticket_attachments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SupportTicketAttachment extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    @JsonIgnore
    private SupportTicketMessage message;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "mime_type", nullable = false, length = 64)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private Integer sizeBytes;

    @Column
    private Integer width;

    @Column
    private Integer height;
}
