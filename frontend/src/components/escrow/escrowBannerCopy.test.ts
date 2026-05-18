import { describe, it, expect } from "vitest";
import { escrowBannerCopy, type BannerTone } from "./escrowBannerCopy";

describe("escrowBannerCopy", () => {
  // Wallet-only escrow spec (2026-05-16): ESCROW_PENDING is a
  // transactional intermediate that never persists past commit; both
  // roles see a passive "funding in progress" banner. Only legacy
  // historical rows reach this branch.
  it.each(["winner", "seller"] as const)(
    "ESCROW_PENDING %s = passive funding-in-progress banner",
    (role) => {
      const { headline, tone } = escrowBannerCopy({
        state: "ESCROW_PENDING",
        role,
        transferConfirmedAt: null,
        fundedAt: null,
        sellToConfirmedAt: null,
      });
      expect(headline).toBe("Escrow pending");
      expect(tone).toBe<BannerTone>("waiting");
    },
  );

  it("Set Sell To sub-phase winner = Awaiting sell-to + waiting tone", () => {
    const { headline, detail, tone } = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "winner",
      transferConfirmedAt: null,
      fundedAt: "2026-04-20T00:00:00Z",
      sellToConfirmedAt: null,
    });
    expect(headline).toBe("Awaiting sell-to");
    expect(detail).toContain("seller");
    expect(tone).toBe<BannerTone>("waiting");
  });

  it("Set Sell To sub-phase seller = Set parcel for sale + action tone", () => {
    const { headline, detail, tone } = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "seller",
      transferConfirmedAt: null,
      fundedAt: "2026-04-20T00:00:00Z",
      sellToConfirmedAt: null,
    });
    expect(headline).toBe("Set parcel for sale");
    expect(detail).toContain("L$0");
    expect(tone).toBe<BannerTone>("action");
  });

  it("Buy Parcel sub-phase winner = Buy the parcel + action tone", () => {
    const { headline, detail, tone } = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "winner",
      transferConfirmedAt: null,
      fundedAt: "2026-04-20T00:00:00Z",
      sellToConfirmedAt: "2026-04-20T01:00:00Z",
    });
    expect(headline).toBe("Buy the parcel");
    expect(detail).toContain("L$0");
    expect(tone).toBe<BannerTone>("action");
  });

  it("Buy Parcel sub-phase seller = Awaiting purchase + waiting tone", () => {
    const { headline, detail, tone } = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "seller",
      transferConfirmedAt: null,
      fundedAt: "2026-04-20T00:00:00Z",
      sellToConfirmedAt: "2026-04-20T01:00:00Z",
    });
    expect(headline).toBe("Awaiting purchase");
    expect(detail).toContain("winner");
    expect(tone).toBe<BannerTone>("waiting");
  });

  it("TRANSFER_PENDING post-confirmation winner = Payout pending + waiting tone", () => {
    const { headline, tone } = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "winner",
      transferConfirmedAt: "2026-04-20T01:00:00Z",
      fundedAt: "2026-04-20T00:00:00Z",
      sellToConfirmedAt: "2026-04-20T00:30:00Z",
    });
    expect(headline).toBe("Payout pending");
    expect(tone).toBe<BannerTone>("waiting");
  });

  it("TRANSFER_PENDING post-confirmation seller = Payout pending + waiting tone", () => {
    const { headline, tone } = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "seller",
      transferConfirmedAt: "2026-04-20T01:00:00Z",
      fundedAt: "2026-04-20T00:00:00Z",
      sellToConfirmedAt: "2026-04-20T00:30:00Z",
    });
    expect(headline).toBe("Payout pending");
    expect(tone).toBe<BannerTone>("waiting");
  });

  it("FUNDED Set-Sell-To winner = Awaiting sell-to (treated as TRANSFER_PENDING)", () => {
    const { headline, tone } = escrowBannerCopy({
      state: "FUNDED",
      role: "winner",
      transferConfirmedAt: null,
      fundedAt: "2026-04-20T00:00:00Z",
      sellToConfirmedAt: null,
    });
    expect(headline).toBe("Awaiting sell-to");
    expect(tone).toBe<BannerTone>("waiting");
  });

  it("COMPLETED = Escrow complete + done tone", () => {
    const { headline, detail, tone } = escrowBannerCopy({
      state: "COMPLETED",
      role: "winner",
      transferConfirmedAt: "2026-04-20T01:00:00Z",
      fundedAt: "2026-04-20T00:00:00Z",
      sellToConfirmedAt: "2026-04-20T00:30:00Z",
    });
    expect(headline).toBe("Escrow complete");
    expect(detail).toBe("");
    expect(tone).toBe<BannerTone>("done");
  });

  it("DISPUTED = problem tone", () => {
    const { headline, tone } = escrowBannerCopy({
      state: "DISPUTED",
      role: "winner",
      transferConfirmedAt: null,
      fundedAt: null,
      sellToConfirmedAt: null,
    });
    expect(headline).toBe("Escrow disputed");
    expect(tone).toBe<BannerTone>("problem");
  });

  it("FROZEN = problem tone", () => {
    const { headline, tone } = escrowBannerCopy({
      state: "FROZEN",
      role: "seller",
      transferConfirmedAt: null,
      fundedAt: null,
      sellToConfirmedAt: null,
    });
    expect(headline).toBe("Escrow frozen");
    expect(tone).toBe<BannerTone>("problem");
  });

  it("EXPIRED = muted tone", () => {
    const { headline, detail, tone } = escrowBannerCopy({
      state: "EXPIRED",
      role: "seller",
      transferConfirmedAt: null,
      fundedAt: null,
      sellToConfirmedAt: null,
    });
    expect(headline).toBe("Escrow expired");
    expect(detail).toBe("");
    expect(tone).toBe<BannerTone>("muted");
  });
});
