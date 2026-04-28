package com.slparcelauctions.backend.escrow;

import com.slparcelauctions.backend.escrow.dispute.EvidenceImage;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EscrowEvidenceColumnsTest {

    @Test
    void escrowDefaultsEvidenceListsToEmpty() {
        Escrow e = new Escrow();
        assertThat(e.getWinnerEvidenceImages()).isEmpty();
        assertThat(e.getSellerEvidenceImages()).isEmpty();
    }

    @Test
    void evidenceImageHoldsAllFields() {
        EvidenceImage img = new EvidenceImage(
                "dispute-evidence/42/winner/abc.png",
                "image/png",
                123_456L,
                OffsetDateTime.parse("2026-04-27T12:00:00Z"));
        assertThat(img.s3Key()).isEqualTo("dispute-evidence/42/winner/abc.png");
        assertThat(img.contentType()).isEqualTo("image/png");
        assertThat(img.size()).isEqualTo(123_456L);
        assertThat(img.uploadedAt()).isEqualTo("2026-04-27T12:00:00Z");
    }

    @Test
    void escrowAcceptsEvidenceListAndRetainsIt() {
        Escrow e = new Escrow();
        EvidenceImage img = new EvidenceImage(
                "dispute-evidence/1/winner/x.png", "image/png", 100L,
                OffsetDateTime.parse("2026-04-27T00:00:00Z"));
        e.setWinnerEvidenceImages(List.of(img));
        assertThat(e.getWinnerEvidenceImages()).hasSize(1);
        assertThat(e.getWinnerEvidenceImages().get(0).s3Key())
                .isEqualTo("dispute-evidence/1/winner/x.png");
    }

    @Test
    void escrowSellerEvidenceSubmittedAtNullByDefault() {
        Escrow e = new Escrow();
        assertThat(e.getSellerEvidenceSubmittedAt()).isNull();
        assertThat(e.getSellerEvidenceText()).isNull();
        assertThat(e.getSlTransactionKey()).isNull();
    }
}
