package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SlImMessageBuilderTest {

    private final SlImMessageBuilder builder = new SlImMessageBuilder();

    @Test
    void assemble_shortInputs_returnsExactTemplate() {
        String result = builder.assemble(
            "You've been outbid on Hampton Hills",
            "Current bid is L$2,000.",
            "https://slpa.example.com/auction/42#bid-panel");

        assertThat(result).isEqualTo(
            "[SLPA] You've been outbid on Hampton Hills\n\n" +
            "Current bid is L$2,000.\n\n" +
            "https://slpa.example.com/auction/42#bid-panel");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    @Test
    void assemble_multiByteParcelName_keepsDeeplinkIntact() {
        // Three CJK characters, each 3 bytes UTF-8.
        String result = builder.assemble(
            "You've been outbid on 東京タワー Estates",
            "Current bid is L$2,000.",
            "https://slpa.example.com/auction/42#bid-panel");

        assertThat(result).contains("東京タワー Estates");
        assertThat(result).endsWith("https://slpa.example.com/auction/42#bid-panel");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    @Test
    void assemble_emojiParcelName_keepsDeeplinkIntact() {
        // 🌸 is a 4-byte UTF-8 character (U+1F338) and a UTF-16 surrogate pair.
        String result = builder.assemble(
            "Your auction sold: 🌸 Sakura Plot",
            "Winning bid: L$5,200.",
            "https://slpa.example.com/auction/99");

        assertThat(result).contains("🌸 Sakura Plot");
        assertThat(result).endsWith("https://slpa.example.com/auction/99");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    @Test
    void assemble_longBody_ellipsizesBodyKeepsDeeplinkIntact() {
        String longBody = "x".repeat(2000);
        String deeplink = "https://slpa.example.com/auction/42";
        String result = builder.assemble("Hampton Hills update", longBody, deeplink);

        assertThat(result).contains("…");
        assertThat(result).endsWith(deeplink);
        assertThat(result).startsWith("[SLPA] Hampton Hills update");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    @Test
    void assemble_longBodyWithMultiByteContent_ellipsizesAtCharBoundary() {
        // Body of 1500 CJK characters (4500 bytes UTF-8). Must be trimmed; ellipsis
        // must land at a valid char boundary (no orphaned surrogate halves).
        String longBody = "東".repeat(1500);
        String result = builder.assemble("Hampton Hills update", longBody,
            "https://slpa.example.com/auction/42");

        assertThat(result).endsWith("https://slpa.example.com/auction/42");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
        // Decoding round-trips cleanly (no replacement chars from broken sequences):
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        String roundTrip = new String(bytes, StandardCharsets.UTF_8);
        assertThat(roundTrip).isEqualTo(result);
    }

    @Test
    void assemble_bodyEmpty_returnsTitleAndDeeplink() {
        String result = builder.assemble("Title", "", "https://slpa.example.com/x");
        assertThat(result).isEqualTo("[SLPA] Title\n\n\n\nhttps://slpa.example.com/x");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    @Test
    void assemble_pathologicalCase_titleAndDeeplinkExceedBudget_dropsBodyTrimsTitle() {
        // Title alone is 1500 chars + deeplink 50 chars > 1024.
        String result = builder.assemble("a".repeat(1500), "body content",
            "https://slpa.example.com/auction/42");

        assertThat(result).endsWith("https://slpa.example.com/auction/42");
        assertThat(result).contains("…");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    private int byteLen(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
