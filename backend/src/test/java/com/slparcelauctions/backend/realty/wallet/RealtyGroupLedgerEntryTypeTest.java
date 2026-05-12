package com.slparcelauctions.backend.realty.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RealtyGroupLedgerEntryTypeTest {

    @Test
    void admin_adjustment_enum_value_present() {
        assertThat(RealtyGroupLedgerEntryType.valueOf("ADMIN_ADJUSTMENT"))
            .isEqualTo(RealtyGroupLedgerEntryType.ADMIN_ADJUSTMENT);
    }
}
