package com.slparcelauctions.backend.wallet.me;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import com.slparcelauctions.backend.wallet.LedgerRow;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;

/**
 * Writes a stream of {@link LedgerRow} rows to an OutputStream as
 * RFC 4180 CSV. Used by the streaming export endpoint so multi-thousand-row
 * downloads don't hold the whole result in memory.
 *
 * <p>The {@code withdrawal_status} column is populated only for
 * {@code WITHDRAW_QUEUED} rows; empty for every other entry type. Mirrors
 * the user-facing wallet view, so a CSV export reflects what the user
 * actually saw on screen.
 */
public final class LedgerCsvWriter {

    private static final String HEADER =
            "id,created_at,entry_type,amount,balance_after,reserved_after,"
                    + "ref_type,ref_id,description,sl_transaction_id,withdrawal_status";

    private LedgerCsvWriter() {}

    public static void write(Stream<LedgerRow> rows, OutputStream out)
            throws IOException {
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            w.write(HEADER);
            w.write('\n');
            rows.forEach(row -> {
                try {
                    w.write(toCsvLine(row));
                    w.write('\n');
                } catch (IOException ioe) {
                    throw new UncheckedIOException(ioe);
                }
            });
        }
    }

    private static String toCsvLine(LedgerRow row) {
        UserLedgerEntry e = row.entry();
        return e.getId() + ","
                + escape(e.getCreatedAt().toString()) + ","
                + e.getEntryType().name() + ","
                + e.getAmount() + ","
                + e.getBalanceAfter() + ","
                + e.getReservedAfter() + ","
                + escape(e.getRefType()) + ","
                + (e.getRefId() == null ? "" : e.getRefId()) + ","
                + escape(e.getDescription()) + ","
                + escape(e.getSlTransactionId()) + ","
                + (row.withdrawalStatus() == null ? "" : row.withdrawalStatus().name());
    }

    private static String escape(String s) {
        if (s == null) return "";
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        String escaped = s.replace("\"", "\"\"");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }
}
