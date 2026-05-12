package com.slparcelauctions.backend.realty.wallet;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtyGroupLedgerEntryTest {

    @Test
    void builderPopulatesCoreFields() {
        OffsetDateTime now = OffsetDateTime.now();
        RealtyGroupLedgerEntry e = RealtyGroupLedgerEntry.builder()
            .groupId(42L)
            .entryType(RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT)
            .amount(500L)
            .balanceAfter(9500L)
            .reservedAfter(0L)
            .refType("AUCTION")
            .refId(123L)
            .actorUserId(7L)
            .createdAt(now)
            .build();

        assertThat(e.getGroupId()).isEqualTo(42L);
        assertThat(e.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT);
        assertThat(e.getAmount()).isEqualTo(500L);
        assertThat(e.getBalanceAfter()).isEqualTo(9500L);
        assertThat(e.getReservedAfter()).isZero();
        assertThat(e.getRefType()).isEqualTo("AUCTION");
        assertThat(e.getRefId()).isEqualTo(123L);
        assertThat(e.getActorUserId()).isEqualTo(7L);
        assertThat(e.getCreatedAt()).isEqualTo(now);
    }
}
