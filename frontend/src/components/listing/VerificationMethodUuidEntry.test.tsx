import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { act } from "react";
import { renderWithProviders, screen } from "@/test/render";
import { VerificationMethodUuidEntry } from "./VerificationMethodUuidEntry";

describe("VerificationMethodUuidEntry", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders the checking copy immediately", () => {
    renderWithProviders(<VerificationMethodUuidEntry />);
    expect(
      screen.getByText(/Checking ownership with the Second Life World API/i),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/This is taking longer than usual/i),
    ).toBeNull();
  });

  it("surfaces the slow-indicator copy after 10 seconds", () => {
    renderWithProviders(<VerificationMethodUuidEntry />);
    act(() => {
      vi.advanceTimersByTime(10_100);
    });
    expect(
      screen.getByText(/This is taking longer than usual/i),
    ).toBeInTheDocument();
  });
});
