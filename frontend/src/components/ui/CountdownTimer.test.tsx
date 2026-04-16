import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { CountdownTimer } from "./CountdownTimer";
import { act } from "@testing-library/react";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("CountdownTimer", () => {
  it("displays initial MM:SS countdown", () => {
    const expiresAt = new Date(Date.now() + 90_000); // 1:30
    renderWithProviders(<CountdownTimer expiresAt={expiresAt} />);
    expect(screen.getByRole("timer")).toHaveTextContent("01:30");
  });

  it("ticks forward on 1s intervals", () => {
    const expiresAt = new Date(Date.now() + 90_000);
    renderWithProviders(<CountdownTimer expiresAt={expiresAt} />);
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByRole("timer")).toHaveTextContent("01:29");
  });

  it("fires onExpire exactly once at zero", () => {
    const onExpire = vi.fn();
    const expiresAt = new Date(Date.now() + 2_000);
    renderWithProviders(
      <CountdownTimer expiresAt={expiresAt} onExpire={onExpire} />,
    );
    act(() => {
      vi.advanceTimersByTime(2_000);
    });
    expect(onExpire).toHaveBeenCalledTimes(1);
    // further ticks should not fire again
    act(() => {
      vi.advanceTimersByTime(3_000);
    });
    expect(onExpire).toHaveBeenCalledTimes(1);
  });

  it('shows "--:--" after expiry (mm:ss format)', () => {
    const expiresAt = new Date(Date.now() + 1_000);
    renderWithProviders(<CountdownTimer expiresAt={expiresAt} />);
    act(() => {
      vi.advanceTimersByTime(2_000);
    });
    expect(screen.getByRole("timer")).toHaveTextContent("--:--");
  });

  it("supports hh:mm:ss format", () => {
    const expiresAt = new Date(Date.now() + 3_661_000); // 1h 1m 1s
    renderWithProviders(<CountdownTimer expiresAt={expiresAt} format="hh:mm:ss" />);
    expect(screen.getByRole("timer")).toHaveTextContent("01:01:01");
  });

  it("clears interval on unmount", () => {
    const expiresAt = new Date(Date.now() + 60_000);
    const { unmount } = renderWithProviders(
      <CountdownTimer expiresAt={expiresAt} />,
    );
    const clearIntervalSpy = vi.spyOn(globalThis, "clearInterval");
    unmount();
    expect(clearIntervalSpy).toHaveBeenCalled();
    clearIntervalSpy.mockRestore();
  });
});
