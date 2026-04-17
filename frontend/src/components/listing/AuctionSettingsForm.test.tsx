import { describe, expect, it, vi } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
} from "@/test/render";
import {
  AuctionSettingsForm,
  type AuctionSettingsValue,
} from "./AuctionSettingsForm";

function baseValue(): AuctionSettingsValue {
  return {
    startingBid: 500,
    reservePrice: null,
    buyNowPrice: null,
    durationHours: 72,
    snipeProtect: true,
    snipeWindowMin: 10,
  };
}

describe("AuctionSettingsForm", () => {
  it("shows a cross-field error when reserve is below starting bid", async () => {
    // Component is controlled — type simulates an uncontrolled parent that
    // passes the latest onChange result back in. We drive the flow by
    // re-rendering with the new value the component requested.
    const Harness = () => {
      const [value, setValue] =
        useStateUnshim<AuctionSettingsValue>(baseValue());
      return <AuctionSettingsForm value={value} onChange={setValue} />;
    };
    renderWithProviders(<Harness />);
    const reserve = screen.getByLabelText(/Reserve price/i);
    await userEvent.clear(reserve);
    await userEvent.type(reserve, "100");
    expect(
      await screen.findByText(/Reserve must be at least the starting bid/i),
    ).toBeInTheDocument();
  });

  it("shows a buy-now-min error referencing the reserve when one is set", async () => {
    const Harness = () => {
      const [value, setValue] = useStateUnshim<AuctionSettingsValue>({
        ...baseValue(),
        reservePrice: 750,
      });
      return <AuctionSettingsForm value={value} onChange={setValue} />;
    };
    renderWithProviders(<Harness />);
    const buyNow = screen.getByLabelText(/Buy-it-now price/i);
    await userEvent.clear(buyNow);
    await userEvent.type(buyNow, "600");
    expect(
      await screen.findByText(/Buy-it-now must be at least L\$750/),
    ).toBeInTheDocument();
  });

  it("hides extension window when snipe protection is off", async () => {
    const Harness = () => {
      const [value, setValue] =
        useStateUnshim<AuctionSettingsValue>(baseValue());
      return <AuctionSettingsForm value={value} onChange={setValue} />;
    };
    renderWithProviders(<Harness />);
    expect(screen.getByLabelText(/Extension window/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("switch"));
    expect(screen.queryByLabelText(/Extension window/i)).toBeNull();
  });

  it("surfaces server-side errors for fields that don't trigger a local rule", () => {
    const value = baseValue();
    renderWithProviders(
      <AuctionSettingsForm
        value={value}
        onChange={vi.fn()}
        errors={{ startingBid: "Must be at least L$1." }}
      />,
    );
    expect(screen.getByText("Must be at least L$1.")).toBeInTheDocument();
  });
});

// Intentionally-local hook so this file stays a single test file. Test-only
// glue that mirrors useState, scoped here to avoid forcing a separate
// "harness" import from the component file.
import { useState as reactUseState } from "react";
function useStateUnshim<T>(initial: T): [T, (v: T) => void] {
  const [v, setV] = reactUseState<T>(initial);
  return [v, setV];
}
