import { describe, it, expect } from "vitest";
import { deriveStatusChip } from "./status-chip";

function base(
  overrides: Partial<Parameters<typeof deriveStatusChip>[0]> = {},
) {
  return {
    status: "ACTIVE",
    endOutcome: null,
    endsAt: new Date(Date.now() + 5 * 3600_000).toISOString(),
    ...overrides,
  };
}

describe("deriveStatusChip", () => {
  it("LIVE when active and ends_at > 1h away", () => {
    const chip = deriveStatusChip(base());
    expect(chip.label).toBe("LIVE");
    expect(chip.tone).toBe("live");
  });

  it("ENDING SOON when active and ends_at <= 1h away", () => {
    const chip = deriveStatusChip(
      base({ endsAt: new Date(Date.now() + 30 * 60_000).toISOString() }),
    );
    expect(chip.label).toBe("ENDING SOON");
    expect(chip.tone).toBe("ending_soon");
  });

  it("SOLD on endOutcome SOLD/BOUGHT_NOW", () => {
    expect(
      deriveStatusChip(base({ status: "COMPLETED", endOutcome: "SOLD" })).label,
    ).toBe("SOLD");
    expect(
      deriveStatusChip(base({ status: "COMPLETED", endOutcome: "BOUGHT_NOW" }))
        .label,
    ).toBe("SOLD");
  });

  it("RESERVE NOT MET / NO BIDS / CANCELLED / SUSPENDED", () => {
    expect(
      deriveStatusChip(base({ status: "ENDED", endOutcome: "RESERVE_NOT_MET" }))
        .label,
    ).toBe("RESERVE NOT MET");
    expect(
      deriveStatusChip(base({ status: "ENDED", endOutcome: "NO_BIDS" })).label,
    ).toBe("NO BIDS");
    expect(
      deriveStatusChip(base({ status: "CANCELLED", endOutcome: null })).label,
    ).toBe("CANCELLED");
    expect(
      deriveStatusChip(base({ status: "SUSPENDED", endOutcome: null })).label,
    ).toBe("SUSPENDED");
  });

  it("falls back to ENDED for unknown status", () => {
    expect(
      deriveStatusChip(base({ status: "MYSTERY", endOutcome: null })).label,
    ).toBe("ENDED");
  });
});
