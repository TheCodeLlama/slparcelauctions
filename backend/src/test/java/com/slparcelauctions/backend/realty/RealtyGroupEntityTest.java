package com.slparcelauctions.backend.realty;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtyGroupEntityTest {

    @Test
    void newGroupDefaultsWalletColumnsToZeroAndNullDormancy() {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme Realty")
            .slug("acme-realty")
            .leaderId(1L)
            .build();

        assertThat(g.getBalanceLindens()).isEqualTo(0L);
        assertThat(g.getReservedLindens()).isEqualTo(0L);
        assertThat(g.getWalletDormancyStartedAt()).isNull();
        assertThat(g.getWalletDormancyPhase()).isNull();
    }

    @Test
    void availableLindensIsBalanceMinusReserved() {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(10_000L)
            .reservedLindens(2_500L)
            .build();
        assertThat(g.availableLindens()).isEqualTo(7_500L);
    }
}
