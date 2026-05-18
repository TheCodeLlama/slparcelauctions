import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { LedgerTable, entryTypeLabel, entryVisual } from "./LedgerTable";
import { humanizeEntryType } from "@/lib/wallet/humanizeEntryType";
import type { LedgerEntry, UserLedgerEntryType } from "@/types/wallet";

// Every value in the UserLedgerEntryType union — mirror of the backend
// UserLedgerEntryType enum (15 values). The exhaustiveness assertions below
// fail loudly if a value is added to the union without a renderer case.
const ALL_USER_ENTRY_TYPES: UserLedgerEntryType[] = [
  "DEPOSIT",
  "WITHDRAW_QUEUED",
  "WITHDRAW_COMPLETED",
  "WITHDRAW_REVERSED",
  "BID_RESERVED",
  "BID_RELEASED",
  "ESCROW_DEBIT",
  "ESCROW_REFUND",
  "LISTING_FEE_DEBIT",
  "LISTING_FEE_REFUND",
  "PENALTY_DEBIT",
  "ADJUSTMENT",
  "AGENT_FEE_CREDIT",
  "AGENT_COMMISSION_CREDIT",
  "GROUP_WALLET_DEPOSIT_DEBIT",
];

function makeEntry(overrides: Partial<LedgerEntry> = {}): LedgerEntry {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    entryType: "DEPOSIT",
    amount: 100,
    balanceAfter: 100,
    reservedAfter: 0,
    refType: null,
    refId: null,
    description: null,
    createdAt: "2026-05-10T10:00:00Z",
    withdrawalStatus: null,
    ...overrides,
  };
}

describe("LedgerTable entryTypeLabel / entryVisual exhaustiveness", () => {
  it("covers all 15 user ledger entry types", () => {
    expect(ALL_USER_ENTRY_TYPES).toHaveLength(15);
  });

  it("returns a non-empty label and a defined visual for every union value", () => {
    for (const entryType of ALL_USER_ENTRY_TYPES) {
      const entry = makeEntry({ entryType });
      const label = entryTypeLabel(entry);
      expect(label, entryType).toBeTruthy();
      expect(typeof label).toBe("string");

      const visual = entryVisual(entry);
      expect(visual, entryType).toBeDefined();
      expect(visual.Icon, entryType).toBeTruthy();
      expect(["function", "object"], entryType).toContain(typeof visual.Icon);
      expect(visual.tone, entryType).toBeTruthy();
    }
  });

  it("humanizes an unknown deploy-skew type instead of throwing", () => {
    const entry = makeEntry({
      entryType: "SOME_FUTURE_TYPE_99" as UserLedgerEntryType,
    });
    expect(() => entryTypeLabel(entry)).not.toThrow();
    expect(() => entryVisual(entry)).not.toThrow();
    expect(entryTypeLabel(entry)).toBe(humanizeEntryType("SOME_FUTURE_TYPE_99"));
    expect(entryTypeLabel(entry)).toBe("Some future type 99");

    const visual = entryVisual(entry);
    expect(visual.Icon).toBeTruthy();
    expect(visual.tone).toBe("text-fg-muted");
  });
});

describe("LedgerTable render — prod white-screen regression", () => {
  // Before the fix, AGENT_FEE_CREDIT / AGENT_COMMISSION_CREDIT had no case,
  // entryVisual() returned undefined, the destructure threw, and the whole
  // wallet white-screened. This asserts the renderer survives those rows.
  it("renders AGENT_FEE_CREDIT and AGENT_COMMISSION_CREDIT rows without throwing", () => {
    const entries: LedgerEntry[] = [
      makeEntry({
        publicId: "11111111-1111-1111-1111-111111111111",
        entryType: "AGENT_FEE_CREDIT",
        amount: 500,
      }),
      makeEntry({
        publicId: "22222222-2222-2222-2222-222222222222",
        entryType: "AGENT_COMMISSION_CREDIT",
        amount: 750,
      }),
    ];

    expect(() =>
      renderWithProviders(<LedgerTable entries={entries} />),
    ).not.toThrow();

    expect(screen.getByText("Agent fee earned")).toBeInTheDocument();
    expect(screen.getByText("Commission earned")).toBeInTheDocument();
  });

  it("renders an unknown entry type with a humanized label, no crash", () => {
    const entries: LedgerEntry[] = [
      makeEntry({ entryType: "SOME_FUTURE_TYPE_99" as UserLedgerEntryType }),
    ];
    renderWithProviders(<LedgerTable entries={entries} />);
    expect(screen.getByText("Some future type 99")).toBeInTheDocument();
  });
});
