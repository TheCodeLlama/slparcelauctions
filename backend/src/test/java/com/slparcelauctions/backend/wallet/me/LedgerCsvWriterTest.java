package com.slparcelauctions.backend.wallet.me;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.wallet.LedgerRow;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.WithdrawalStatus;

/**
 * Unit-level coverage for {@link LedgerCsvWriter}. Asserts the header line,
 * empty-stream behavior, and RFC 4180 escape edge cases (commas, quotes,
 * newlines, null fields, ASCII fast path).
 */
class LedgerCsvWriterTest {

    private static final String EXPECTED_HEADER =
            "id,created_at,entry_type,amount,balance_after,reserved_after,"
                    + "ref_type,ref_id,description,sl_transaction_id,withdrawal_status";

    private static final OffsetDateTime FIXED_TS = OffsetDateTime.parse("2026-04-30T12:00:00Z");

    private static UserLedgerEntry.UserLedgerEntryBuilder baseBuilder() {
        return UserLedgerEntry.builder()
                .id(1L)
                .userId(42L)
                .entryType(UserLedgerEntryType.DEPOSIT)
                .amount(100L)
                .balanceAfter(100L)
                .reservedAfter(0L)
                .refType("AUCTION")
                .refId(7L)
                .description("plain")
                .slTransactionId("tx-001")
                .createdAt(FIXED_TS);
    }

    private static LedgerRow row(UserLedgerEntry e) {
        return new LedgerRow(e, null);
    }

    private static LedgerRow row(UserLedgerEntry e, WithdrawalStatus status) {
        return new LedgerRow(e, status);
    }

    private static String run(Stream<LedgerRow> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LedgerCsvWriter.write(rows, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void emptyStream_producesOnlyHeader() throws Exception {
        String csv = run(Stream.empty());
        assertThat(csv).isEqualTo(EXPECTED_HEADER + "\n");
    }

    @Test
    void headerRowMatchesExpected() throws Exception {
        String csv = run(Stream.of(row(baseBuilder().build())));
        String[] lines = csv.split("\n", -1);
        assertThat(lines[0]).isEqualTo(EXPECTED_HEADER);
    }

    @Test
    void asciiFastPath_addsNoQuotes() throws Exception {
        UserLedgerEntry e = baseBuilder()
                .description("simpleAscii")
                .refType("AUCTION")
                .slTransactionId("tx-001")
                .build();
        String csv = run(Stream.of(row(e)));
        String dataLine = csv.split("\n", -1)[1];
        // No double-quotes anywhere in the data row.
        assertThat(dataLine).doesNotContain("\"");
        assertThat(dataLine).isEqualTo(
                "1,2026-04-30T12:00Z,DEPOSIT,100,100,0,AUCTION,7,simpleAscii,tx-001,");
    }

    @Test
    void commaInDescription_isQuoted() throws Exception {
        UserLedgerEntry e = baseBuilder()
                .description("hello, world")
                .build();
        String csv = run(Stream.of(row(e)));
        String dataLine = csv.split("\n", -1)[1];
        assertThat(dataLine).contains(",\"hello, world\",");
    }

    @Test
    void quoteInDescription_isDoubledAndQuoted() throws Exception {
        UserLedgerEntry e = baseBuilder()
                .description("she said \"hi\"")
                .build();
        String csv = run(Stream.of(row(e)));
        String dataLine = csv.split("\n", -1)[1];
        // " becomes "" and the whole field is wrapped in quotes.
        assertThat(dataLine).contains(",\"she said \"\"hi\"\"\",");
    }

    @Test
    void newlineInDescription_isQuoted() throws Exception {
        UserLedgerEntry e = baseBuilder()
                .description("line1\nline2")
                .build();
        String csv = run(Stream.of(row(e)));
        // Header + (data line containing literal newline + trailing newline).
        // We check the raw bytes around the description field rather than splitting on \n.
        assertThat(csv).contains(",\"line1\nline2\",");
    }

    @Test
    void carriageReturnInDescription_isQuoted() throws Exception {
        UserLedgerEntry e = baseBuilder()
                .description("line1\rline2")
                .build();
        String csv = run(Stream.of(row(e)));
        assertThat(csv).contains(",\"line1\rline2\",");
    }

    @Test
    void nullRefIdAndDescriptionAndSlTransactionId_emitEmptyFieldsNoQuotes() throws Exception {
        UserLedgerEntry e = baseBuilder()
                .refId(null)
                .description(null)
                .slTransactionId(null)
                .refType(null)
                .build();
        String csv = run(Stream.of(row(e)));
        String dataLine = csv.split("\n", -1)[1];
        // refType,ref_id,description,sl_transaction_id should all be empty.
        // Format: id,created_at,entry_type,amount,balance_after,reserved_after,ref_type,ref_id,description,sl_transaction_id
        assertThat(dataLine).isEqualTo("1,2026-04-30T12:00Z,DEPOSIT,100,100,0,,,,,");
    }

    @Test
    void withdrawalStatusColumn_populatedForWithdrawQueuedRow() throws Exception {
        UserLedgerEntry e = baseBuilder()
                .id(99L)
                .entryType(UserLedgerEntryType.WITHDRAW_QUEUED)
                .description("withdraw")
                .build();
        String csv = run(Stream.of(row(e, WithdrawalStatus.COMPLETED)));
        String dataLine = csv.split("\n", -1)[1];
        assertThat(dataLine).endsWith(",COMPLETED");
    }

    @Test
    void withdrawalStatusColumn_emptyForNonWithdrawalRow() throws Exception {
        UserLedgerEntry e = baseBuilder().build();
        String csv = run(Stream.of(row(e)));
        String dataLine = csv.split("\n", -1)[1];
        assertThat(dataLine).endsWith(",");
    }

    @Test
    void multipleRows_eachOnItsOwnLine() throws Exception {
        UserLedgerEntry a = baseBuilder().id(1L).description("first").build();
        UserLedgerEntry b = baseBuilder().id(2L).description("second").build();
        String csv = run(Stream.of(row(a), row(b)));
        String[] lines = csv.split("\n", -1);
        // header, row1, row2, trailing empty (because final \n).
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).isEqualTo(EXPECTED_HEADER);
        assertThat(lines[1]).contains(",first,");
        assertThat(lines[2]).contains(",second,");
        assertThat(lines[3]).isEmpty();
    }
}
