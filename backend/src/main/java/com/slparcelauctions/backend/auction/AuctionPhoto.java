package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "auction_photos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class AuctionPhoto extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Column(name = "light_object_key", nullable = false, length = 500)
    private String lightObjectKey;

    @Column(name = "light_content_type", nullable = false, length = 50)
    private String lightContentType;

    @Column(name = "light_size_bytes", nullable = false)
    private Long lightSizeBytes;

    @Column(name = "dark_object_key", length = 500)
    private String darkObjectKey;

    @Column(name = "dark_content_type", length = 50)
    private String darkContentType;

    @Column(name = "dark_size_bytes")
    private Long darkSizeBytes;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    @Builder.Default
    private PhotoSource source = PhotoSource.SELLER_UPLOAD;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;
}
