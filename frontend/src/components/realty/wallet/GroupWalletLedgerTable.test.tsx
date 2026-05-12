import { describe, expect, it } from "vitest";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import { realtyGroupWalletHandlers } from "@/test/msw/handlers";
import { GroupWalletLedgerTable } from "./GroupWalletLedgerTable";
import type { GroupLedgerEntry } from "@/types/realty";

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
});
