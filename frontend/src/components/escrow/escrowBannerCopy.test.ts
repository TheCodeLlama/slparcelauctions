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
      });
      expect(headline).toBe("Escrow pending");
      expect(tone).toBe<BannerTone>("waiting");
    },
  );

  it("TRANSFER_PENDING pre-confirmation winner = Awaiting transfer + waiting tone", () => {
    const { headline, detail, tone } = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "winner",
      transferConfirmedAt: null,
      fundedAt: "2026-04-20T00:00:00Z",
    });
    expect(headline).toBe("Awaiting transfer");
    expect(detail).toContain("seller");
    expect(tone).toBe<BannerTone>("waiting");
  });

  it("TRANSFER_PENDING pre-confirmation seller = Transfer parcel + action tone", () => {
    const { headline, detail, tone } = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "seller",
      transferConfirmedAt: null,
      fundedAt: "2026-04-20T00:00:00Z",
    });
    expect(headline).toBe("Transfer parcel");
    expect(detail).toContain("L$0");
    expect(tone).toBe<BannerTone>("action");
  });

  it("TRANSFER_PENDING post-confirmation winner = Payout pending + waiting tone", () => {
    const { headline, tone } = escrowBannerCopy({
      state: "TRANSFER_PENDING",
      role: "winner",
      transferConfirmedAt: "2026-04-20T01:00:00Z",
      fundedAt: "2026-04-20T00:00:00Z",
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
    });
    expect(headline).toBe("Payout pending");
    expect(tone).toBe<BannerTone>("waiting");
  });

  it("FUNDED pre-confirmation winner = Awaiting transfer (treated as TRANSFER_PENDING)", () => {
    const { headline, tone } = escrowBannerCopy({
      state: "FUNDED",
      role: "winner",
      transferConfirmedAt: null,
      fundedAt: "2026-04-20T00:00:00Z",
    });
    expect(headline).toBe("Awaiting transfer");
    expect(tone).toBe<BannerTone>("waiting");
  });

  it("COMPLETED = Escrow complete + done tone", () => {
    const { headline, detail, tone } = escrowBannerCopy({
      state: "COMPLETED",
      role: "winner",
      transferConfirmedAt: "2026-04-20T01:00:00Z",
      fundedAt: "2026-04-20T00:00:00Z",
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
    });
    expect(headline).toBe("Escrow expired");
    expect(detail).toBe("");
    expect(tone).toBe<BannerTone>("muted");
  });
});
