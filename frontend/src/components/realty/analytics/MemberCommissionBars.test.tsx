import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import {
  bucketWidthClass,
  MemberCommissionBars,
  type MemberCommissionBarRow,
} from "./MemberCommissionBars";

function row(
  overrides: Partial<MemberCommissionBarRow> = {},
): MemberCommissionBarRow {
  return {
    memberPublicId: "11111111-1111-1111-1111-111111111111",
    displayName: "Alice",
    lifetimeLindens: 1000,
    last30DaysLindens: 100,
    ...overrides,
  };
}

describe("MemberCommissionBars", () => {
  it("renders nothing when there are zero rows", () => {
    renderWithProviders(<MemberCommissionBars rows={[]} />);
    expect(screen.queryByTestId("member-commission-bars")).not.toBeInTheDocument();
  });

  it("renders one row per member with the display name and lifetime total", () => {
    renderWithProviders(
      <MemberCommissionBars
        rows={[
          row({ memberPublicId: "aaa", displayName: "Alice", lifetimeLindens: 5000 }),
          row({ memberPublicId: "bob", displayName: "Bob", lifetimeLindens: 2500 }),
        ]}
      />,
    );
    expect(screen.getByTestId("member-commission-bar-row-aaa")).toBeInTheDocument();
    expect(screen.getByTestId("member-commission-bar-row-bob")).toBeInTheDocument();
    expect(screen.getByText("Alice")).toBeInTheDocument();
    expect(screen.getByText("Bob")).toBeInTheDocument();
    expect(screen.getByTestId("member-commission-bar-totals-aaa")).toHaveTextContent(
      "L$ 5,000",
    );
    expect(screen.getByTestId("member-commission-bar-totals-bob")).toHaveTextContent(
      "L$ 2,500",
    );
  });

  it("bars_scale_relative_to_max — max-row gets 100% width, half-max gets ~50%", () => {
    renderWithProviders(
      <MemberCommissionBars
        rows={[
          row({ memberPublicId: "top", displayName: "Top", lifetimeLindens: 10000 }),
          row({ memberPublicId: "mid", displayName: "Mid", lifetimeLindens: 5000 }),
          row({ memberPublicId: "low", displayName: "Low", lifetimeLindens: 0 }),
        ]}
      />,
    );
    expect(
      screen.getByTestId("member-commission-bar-lifetime-top").getAttribute("data-bar-width"),
    ).toBe("w-[100%]");
    expect(
      screen.getByTestId("member-commission-bar-lifetime-mid").getAttribute("data-bar-width"),
    ).toBe("w-[50%]");
    expect(
      screen.getByTestId("member-commission-bar-lifetime-low").getAttribute("data-bar-width"),
    ).toBe("w-[0%]");
  });

  it("zero-max input renders every bar at 0% width without dividing by zero", () => {
    renderWithProviders(
      <MemberCommissionBars
        rows={[
          row({ memberPublicId: "a", displayName: "A", lifetimeLindens: 0, last30DaysLindens: 0 }),
          row({ memberPublicId: "b", displayName: "B", lifetimeLindens: 0, last30DaysLindens: 0 }),
        ]}
      />,
    );
    expect(
      screen.getByTestId("member-commission-bar-lifetime-a").getAttribute("data-bar-width"),
    ).toBe("w-[0%]");
    expect(
      screen.getByTestId("member-commission-bar-recent-b").getAttribute("data-bar-width"),
    ).toBe("w-[0%]");
  });
});

describe("bucketWidthClass", () => {
  it("clamps to 0% at and below 0", () => {
    expect(bucketWidthClass(0)).toBe("w-[0%]");
    expect(bucketWidthClass(-1)).toBe("w-[0%]");
  });

  it("clamps to 100% at and above 1", () => {
    expect(bucketWidthClass(1)).toBe("w-[100%]");
    expect(bucketWidthClass(1.5)).toBe("w-[100%]");
  });

  it("rounds to the nearest 5% bucket in the middle", () => {
    expect(bucketWidthClass(0.5)).toBe("w-[50%]");
    expect(bucketWidthClass(0.49)).toBe("w-[50%]");
    expect(bucketWidthClass(0.04)).toBe("w-[5%]");
  });

  it("falls back to 0% on non-finite input", () => {
    expect(bucketWidthClass(Number.NaN)).toBe("w-[0%]");
    expect(bucketWidthClass(Number.POSITIVE_INFINITY)).toBe("w-[0%]");
  });
});
