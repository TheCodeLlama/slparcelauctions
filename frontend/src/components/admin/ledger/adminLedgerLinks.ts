import type { AdminLedgerRow } from "@/lib/admin/types";

/**
 * Returns the "open in source view" drill-down URL for a ledger row.
 * The URL is null when the row's primary user is missing AND the kind needs
 * a user context (e.g. an ESCROW_TXN commission with no resolved user — the
 * caller should hide the drill link in that case).
 *
 * The wallet/infrastructure landing pages read the row-id query param via
 * `useScrollToRowFromQueryParam` and scroll-to-and-highlight the matching
 * row.
 */
export function rowDrillLink(row: AdminLedgerRow): string | null {
  switch (row.kind) {
    case "USER_LEDGER": {
      if (!row.userPublicId) return null;
      return `/admin/users/${row.userPublicId}?tab=wallet&ledgerEntryId=${row.nativeId}`;
    }
    case "ESCROW_TXN": {
      // refId is the escrow id; we don't have the auction publicId in the
      // row. Fall back to the resolved user's wallet tab when present.
      if (row.userPublicId) {
        return `/admin/users/${row.userPublicId}?tab=wallet&escrowId=${row.refId ?? ""}`;
      }
      return null;
    }
    case "TERMINAL_CMD": {
      if (row.entryType === "WALLET_WITHDRAWAL" && row.userPublicId) {
        return `/admin/users/${row.userPublicId}?tab=wallet&terminalCommandId=${row.nativeId}`;
      }
      return `/admin/infrastructure?tab=terminals&commandId=${row.nativeId}`;
    }
    case "WITHDRAWAL":
      return `/admin/infrastructure?tab=withdrawals&withdrawalId=${row.nativeId}`;
    case "BID_RESERVATION": {
      // refId is the bid id; without the auction publicId, best landing is
      // the user's wallet tab (which shows the paired BID_RESERVED/BID_RELEASED rows).
      if (row.userPublicId) {
        return `/admin/users/${row.userPublicId}?tab=wallet`;
      }
      return null;
    }
    default:
      return null;
  }
}
