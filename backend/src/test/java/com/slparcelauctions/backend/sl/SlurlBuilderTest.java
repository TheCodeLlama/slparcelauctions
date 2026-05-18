package com.slparcelauctions.backend.sl;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SlurlBuilderTest {
    @Test void mapUrl_encodesRegion_andUsesCoords() {
        assertThat(SlurlBuilder.mapUrl("Da Boom", 12.0, 34.0, 56.0))
            .isEqualTo("https://maps.secondlife.com/secondlife/Da%20Boom/12/34/56");
    }
    @Test void viewerUrl_keepsRegionRaw() {
        assertThat(SlurlBuilder.viewerUrl("Da Boom", 12.0, 34.0, 56.0))
            .isEqualTo("secondlife:///app/teleport/Da Boom/12/34/56");
    }
    @Test void nullOrZeroPosition_fallsBackToRegionCentre() {
        assertThat(SlurlBuilder.mapUrl("R", null, 0.0, null))
            .isEqualTo("https://maps.secondlife.com/secondlife/R/128/128/0");
    }
}
