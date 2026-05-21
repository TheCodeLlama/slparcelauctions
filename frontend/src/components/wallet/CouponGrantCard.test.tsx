import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import {
  CouponGrantCard,
  summarizeDiscount,
  expiryLabel,
} from "./CouponGrantCard";
import type {
  CouponDiscountDto,
  CouponGrantDto,
  CouponGrantState,
  CouponGrantSource,
} from "@/types/coupon";

function makeDiscount(
  overrides: Partial<CouponDiscountDto> = {},
): CouponDiscountDto {
  return {
    target: "LISTING_FEE",
    op: "OVERRIDE",
    value: "0",
    sortOrder: 0,
    ...overrides,
  };
}

function makeGrant(overrides: Partial<CouponGrantDto> = {}): CouponGrantDto {
  return {
    publicId: "00000000-0000-0000-0000-0000000000aa",
    couponPublicId: "00000000-0000-0000-0000-0000000000bb",
    code: "WELCOME",
    grantedAt: "2026-05-01T10:00:00Z",
    expiresAt: null,
    remainingCount: null,
    state: "ACTIVE" as CouponGrantState,
    source: "REDEMPTION" as CouponGrantSource,
    discounts: [makeDiscount()],
    ...overrides,
  };
}

describe("summarizeDiscount", () => {
  it("renders LISTING_FEE OVERRIDE 0 as Free listings", () => {
    expect(
      summarizeDiscount(makeDiscount({ target: "LISTING_FEE", op: "OVERRIDE", value: "0" })),
    ).toBe("Free listings");
  });

  it("renders LISTING_FEE OVERRIDE non-zero as L$N listings", () => {
    expect(
      summarizeDiscount(makeDiscount({ target: "LISTING_FEE", op: "OVERRIDE", value: "50" })),
    ).toBe("L$50 listings");
  });

  it("renders LISTING_FEE PERCENT_OFF", () => {
    expect(
      summarizeDiscount(
        makeDiscount({ target: "LISTING_FEE", op: "PERCENT_OFF", value: "25" }),
      ),
    ).toBe("25% off listing fees");
  });

  it("renders LISTING_FEE FLAT_OFF as L$N off listing fee", () => {
    expect(
      summarizeDiscount(
        makeDiscount({ target: "LISTING_FEE", op: "FLAT_OFF", value: "10" }),
      ),
    ).toBe("L$10 off listing fee");
  });

  it("renders COMMISSION_RATE OVERRIDE 0 as Zero commission", () => {
    expect(
      summarizeDiscount(
        makeDiscount({ target: "COMMISSION_RATE", op: "OVERRIDE", value: "0" }),
      ),
    ).toBe("Zero commission");
  });

  it("renders COMMISSION_RATE OVERRIDE fractional as N% commission", () => {
    expect(
      summarizeDiscount(
        makeDiscount({
          target: "COMMISSION_RATE",
          op: "OVERRIDE",
          value: "0.025",
        }),
      ),
    ).toBe("2.5% commission");
  });

  it("renders COMMISSION_RATE PERCENT_OFF", () => {
    expect(
      summarizeDiscount(
        makeDiscount({
          target: "COMMISSION_RATE",
          op: "PERCENT_OFF",
          value: "50",
        }),
      ),
    ).toBe("50% off commission");
  });
});

describe("expiryLabel", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-05-20T12:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("returns Never expires for null", () => {
    expect(expiryLabel(null)).toEqual({ text: "Never expires", expired: false });
  });

  it("returns Expires in N days for future > 1d", () => {
    expect(expiryLabel("2026-06-02T12:00:00Z")).toEqual({
      text: "Expires in 13 days",
      expired: false,
    });
  });

  it("singularizes day", () => {
    expect(expiryLabel("2026-05-21T13:00:00Z")).toEqual({
      text: "Expires in 1 day",
      expired: false,
    });
  });

  it("returns Expires in N hours for sub-day futures", () => {
    expect(expiryLabel("2026-05-20T16:00:00Z")).toEqual({
      text: "Expires in 4 hours",
      expired: false,
    });
  });

  it("returns Expires soon for sub-hour futures", () => {
    expect(expiryLabel("2026-05-20T12:30:00Z")).toEqual({
      text: "Expires soon",
      expired: false,
    });
  });

  it("returns Expired for past timestamps", () => {
    expect(expiryLabel("2026-05-19T12:00:00Z")).toEqual({
      text: "Expired",
      expired: true,
    });
  });
});

describe("<CouponGrantCard />", () => {
  it("renders the code prominently", () => {
    renderWithProviders(
      <CouponGrantCard grant={makeGrant({ code: "WELCOME10" })} />,
    );
    expect(screen.getByText("WELCOME10")).toBeInTheDocument();
  });

  it("renders one discount summary line per discount in the bundle", () => {
    renderWithProviders(
      <CouponGrantCard
        grant={makeGrant({
          discounts: [
            makeDiscount({ target: "LISTING_FEE", op: "OVERRIDE", value: "0" }),
            makeDiscount({
              target: "COMMISSION_RATE",
              op: "OVERRIDE",
              value: "0.025",
            }),
          ],
        })}
      />,
    );
    expect(screen.getByText("Free listings")).toBeInTheDocument();
    expect(screen.getByText("2.5% commission")).toBeInTheDocument();
  });

  it("renders Never expires when expiresAt is null", () => {
    renderWithProviders(
      <CouponGrantCard grant={makeGrant({ expiresAt: null })} />,
    );
    expect(screen.getByText("Never expires")).toBeInTheDocument();
  });

  it("renders the remaining-count line when remainingCount is set", () => {
    renderWithProviders(
      <CouponGrantCard grant={makeGrant({ remainingCount: 3 })} />,
    );
    expect(screen.getByText("3 uses remaining")).toBeInTheDocument();
  });

  it("singularizes the remaining-count line at 1", () => {
    renderWithProviders(
      <CouponGrantCard grant={makeGrant({ remainingCount: 1 })} />,
    );
    expect(screen.getByText("1 use remaining")).toBeInTheDocument();
  });

  it("hides the remaining-count line when remainingCount is null", () => {
    renderWithProviders(
      <CouponGrantCard grant={makeGrant({ remainingCount: null })} />,
    );
    expect(screen.queryByText(/use(s)? remaining/)).not.toBeInTheDocument();
  });

  it("hides the state badge for ACTIVE grants", () => {
    renderWithProviders(
      <CouponGrantCard grant={makeGrant({ state: "ACTIVE" })} />,
    );
    expect(screen.queryByText("Active")).not.toBeInTheDocument();
  });

  it("shows a state badge for EXPIRED grants", () => {
    renderWithProviders(
      <CouponGrantCard grant={makeGrant({ state: "EXPIRED" })} />,
    );
    expect(screen.getByText("Expired")).toBeInTheDocument();
  });

  it("shows a state badge for EXHAUSTED grants", () => {
    renderWithProviders(
      <CouponGrantCard grant={makeGrant({ state: "EXHAUSTED" })} />,
    );
    expect(screen.getByText("Used up")).toBeInTheDocument();
  });

  it("shows a state badge for REVOKED grants", () => {
    renderWithProviders(
      <CouponGrantCard grant={makeGrant({ state: "REVOKED" })} />,
    );
    expect(screen.getByText("Revoked")).toBeInTheDocument();
  });
});
