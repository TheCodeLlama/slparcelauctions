import { describe, expect, it } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { realtyGroupWalletHandlers } from "@/test/msw/handlers";
import {
  GroupWalletLedgerTable,
  entryTypeLabel,
  entryVisual,
} from "./GroupWalletLedgerTable";
import { humanizeEntryType } from "@/lib/wallet/humanizeEntryType";
import type { GroupLedgerEntry, GroupLedgerEntryType } from "@/types/realty";

// Every value in the GroupLedgerEntryType union — mirror of the backend
// RealtyGroupLedgerEntryType enum (11 values).
const ALL_GROUP_ENTRY_TYPES: GroupLedgerEntryType[] = [
  "LISTING_FEE_DEBIT",
  "LISTING_FEE_REFUND",
  "AGENT_FEE_CREDIT",
  "LISTING_PAYOUT",
  "WITHDRAW_QUEUED",
  "WITHDRAW_COMPLETED",
  "WITHDRAW_REVERSED",
  "DORMANCY_AUTO_RETURN",
  "ADJUSTMENT",
  "ADMIN_ADJUSTMENT",
  "MEMBER_DEPOSIT",
];

const GROUP_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

function makeLedgerEntry(
  overrides: Partial<GroupLedgerEntry> = {},
): GroupLedgerEntry {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    entryType: "AGENT_FEE_CREDIT",
    amount: 1200,
    balanceAfter: 12500,
    reservedAfter: 0,
    refType: "AUCTION",
    refPublicId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
    actor: undefined,
    createdAt: "2026-05-10T10:00:00Z",
    ...overrides,
  };
}

describe("GroupWalletLedgerTable entryTypeLabel / entryVisual exhaustiveness", () => {
  it("covers all 11 group ledger entry types", () => {
    expect(ALL_GROUP_ENTRY_TYPES).toHaveLength(11);
  });

  it("returns a non-empty label and a defined visual for every union value", () => {
    for (const entryType of ALL_GROUP_ENTRY_TYPES) {
      const label = entryTypeLabel(entryType);
      expect(label, entryType).toBeTruthy();
      expect(typeof label).toBe("string");

      const visual = entryVisual(entryType);
      expect(visual, entryType).toBeDefined();
      expect(visual.Icon, entryType).toBeTruthy();
      expect(["function", "object"], entryType).toContain(typeof visual.Icon);
      expect(visual.tone, entryType).toBeTruthy();
    }
  });

  it("humanizes an unknown deploy-skew type instead of throwing", () => {
    const unknown = "SOME_FUTURE_TYPE_99" as GroupLedgerEntryType;
    expect(() => entryTypeLabel(unknown)).not.toThrow();
    expect(() => entryVisual(unknown)).not.toThrow();
    expect(entryTypeLabel(unknown)).toBe(humanizeEntryType("SOME_FUTURE_TYPE_99"));
    expect(entryTypeLabel(unknown)).toBe("Some future type 99");

    const visual = entryVisual(unknown);
    expect(visual.Icon).toBeTruthy();
    expect(visual.tone).toBe("text-fg-muted");
  });
});

describe("GroupWalletLedgerTable", () => {
  it("shows empty state when the ledger is empty", async () => {
    // Default handler already returns empty ledger
    renderWithProviders(<GroupWalletLedgerTable publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("ledger-empty")).toBeInTheDocument(),
    );
  });

  it("renders ledger rows when entries exist", async () => {
    const entry = makeLedgerEntry();
    server.use(realtyGroupWalletHandlers.ledgerSuccess(GROUP_ID, [entry]));
    renderWithProviders(<GroupWalletLedgerTable publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("ledger-table")).toBeInTheDocument(),
    );
    expect(screen.getAllByTestId("ledger-row")).toHaveLength(1);
  });

  it("renders positive signed amount for AGENT_FEE_CREDIT", async () => {
    const entry = makeLedgerEntry({ entryType: "AGENT_FEE_CREDIT", amount: 500 });
    server.use(realtyGroupWalletHandlers.ledgerSuccess(GROUP_ID, [entry]));
    renderWithProviders(<GroupWalletLedgerTable publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("ledger-amount")).toHaveTextContent("+L$500"),
    );
  });

  it("renders negative signed amount for LISTING_FEE_DEBIT", async () => {
    const entry = makeLedgerEntry({ entryType: "LISTING_FEE_DEBIT", amount: 250 });
    server.use(realtyGroupWalletHandlers.ledgerSuccess(GROUP_ID, [entry]));
    renderWithProviders(<GroupWalletLedgerTable publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("ledger-amount")).toHaveTextContent("-L$250"),
    );
  });

  it("renders an auction link for entries with refType AUCTION", async () => {
    const entry = makeLedgerEntry({
      refType: "AUCTION",
      refPublicId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
    });
    server.use(realtyGroupWalletHandlers.ledgerSuccess(GROUP_ID, [entry]));
    renderWithProviders(<GroupWalletLedgerTable publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByRole("link", { name: /Auction/i })).toHaveAttribute(
        "href",
        "/auction/cccccccc-cccc-cccc-cccc-cccccccccccc",
      ),
    );
  });

  it("renders actor display name when actor is present", async () => {
    const entry = makeLedgerEntry({
      actor: { publicId: "dddddddd-dddd-dddd-dddd-dddddddddddd", displayName: "Alice" },
    });
    server.use(realtyGroupWalletHandlers.ledgerSuccess(GROUP_ID, [entry]));
    renderWithProviders(<GroupWalletLedgerTable publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByText("Alice")).toBeInTheDocument(),
    );
  });

  it("shows load-more button when there are more pages", async () => {
    // Return exactly 50 entries (pageSize default) to trigger hasNextPage
    const entries = Array.from({ length: 50 }, (_, i) =>
      makeLedgerEntry({
        publicId: `entry-${i}`,
        entryType: "AGENT_FEE_CREDIT",
        amount: 100,
        balanceAfter: (i + 1) * 100,
      }),
    );
    server.use(realtyGroupWalletHandlers.ledgerSuccess(GROUP_ID, entries));
    renderWithProviders(<GroupWalletLedgerTable publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("load-more-button")).toBeInTheDocument(),
    );
  });

  it("does not show load-more when the page is partial (fewer than 50 entries)", async () => {
    const entries = [makeLedgerEntry()];
    server.use(realtyGroupWalletHandlers.ledgerSuccess(GROUP_ID, entries));
    renderWithProviders(<GroupWalletLedgerTable publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("ledger-table")).toBeInTheDocument(),
    );
    expect(screen.queryByTestId("load-more-button")).not.toBeInTheDocument();
  });

  // Before the fix, LISTING_PAYOUT / ADMIN_ADJUSTMENT had no case in
  // entryVisual(), it returned undefined, the LedgerRow destructure threw,
  // and the whole group wallet white-screened.
  it("renders LISTING_PAYOUT and ADMIN_ADJUSTMENT rows without crashing", async () => {
    const entries = [
      makeLedgerEntry({
        publicId: "00000000-0000-0000-0000-0000000000aa",
        entryType: "LISTING_PAYOUT",
        amount: 9000,
      }),
      makeLedgerEntry({
        publicId: "00000000-0000-0000-0000-0000000000bb",
        entryType: "ADMIN_ADJUSTMENT",
        amount: 250,
      }),
    ];
    server.use(realtyGroupWalletHandlers.ledgerSuccess(GROUP_ID, entries));
    renderWithProviders(<GroupWalletLedgerTable publicId={GROUP_ID} />);
    await waitFor(() =>
      expect(screen.getByTestId("ledger-table")).toBeInTheDocument(),
    );
    expect(screen.getAllByTestId("ledger-row")).toHaveLength(2);
    expect(screen.getByText("Listing payout")).toBeInTheDocument();
    expect(screen.getByText("Admin adjustment")).toBeInTheDocument();
  });
});
