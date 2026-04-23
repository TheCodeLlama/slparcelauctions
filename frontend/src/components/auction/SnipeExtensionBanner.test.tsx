import { describe, it, expect, vi, afterEach } from "vitest";
import { act } from "@testing-library/react";
import { renderWithProviders, screen } from "@/test/render";
import {
  SnipeExtensionBanner,
  formatRemainingLabel,
} from "./SnipeExtensionBanner";

afterEach(() => {
  vi.useRealTimers();
});

describe("SnipeExtensionBanner", () => {
  it("renders nothing when isVisible is false", () => {
    renderWithProviders(
      <SnipeExtensionBanner
        isVisible={false}
        extensionMinutes={15}
        remainingAfterExtension="2h 14m now remaining"
        onExpire={() => {}}
      />,
    );
    expect(
      screen.queryByTestId("snipe-extension-banner"),
    ).not.toBeInTheDocument();
  });

  it("renders when isVisible is true", () => {
    renderWithProviders(
      <SnipeExtensionBanner
        isVisible
        extensionMinutes={15}
        remainingAfterExtension="2h 14m now remaining"
        onExpire={() => {}}
      />,
    );
    const banner = screen.getByTestId("snipe-extension-banner");
    expect(banner).toHaveTextContent("Auction extended by 15m");
    expect(banner).toHaveTextContent("2h 14m now remaining");
  });

  it("calls onExpire 4 seconds after becoming visible", () => {
    vi.useFakeTimers();
    const onExpire = vi.fn();
    renderWithProviders(
      <SnipeExtensionBanner
        isVisible
        extensionMinutes={15}
        remainingAfterExtension="2h 14m now remaining"
        onExpire={onExpire}
      />,
    );
    expect(onExpire).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(3_999);
    });
    expect(onExpire).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(2);
    });
    expect(onExpire).toHaveBeenCalledTimes(1);
  });

  it("does not call onExpire when isVisible is false", () => {
    vi.useFakeTimers();
    const onExpire = vi.fn();
    renderWithProviders(
      <SnipeExtensionBanner
        isVisible={false}
        extensionMinutes={15}
        remainingAfterExtension="x"
        onExpire={onExpire}
      />,
    );
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(onExpire).not.toHaveBeenCalled();
  });
});

describe("formatRemainingLabel", () => {
  it("handles hours + minutes", () => {
    expect(formatRemainingLabel(2 * 3_600_000 + 14 * 60_000)).toBe(
      "2h 14m now remaining",
    );
  });

  it("handles whole hours", () => {
    expect(formatRemainingLabel(3 * 3_600_000)).toBe("3h now remaining");
  });

  it("handles minutes only", () => {
    expect(formatRemainingLabel(45 * 60_000)).toBe("45m now remaining");
  });

  it("falls back to 'less than a minute' near zero", () => {
    expect(formatRemainingLabel(30_000)).toBe("less than a minute remaining");
    expect(formatRemainingLabel(-1_000)).toBe("less than a minute remaining");
  });
});
