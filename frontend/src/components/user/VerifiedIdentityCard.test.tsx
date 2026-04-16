import { describe, it, expect, vi, afterEach } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import {
  mockVerifiedCurrentUser,
  mockPublicProfile,
} from "@/test/msw/fixtures";
import { VerifiedIdentityCard } from "./VerifiedIdentityCard";

describe("VerifiedIdentityCard", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("dashboard variant renders SL identity, account age, pay info, and verifiedAt", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-15T00:00:00Z"));

    renderWithProviders(
      <VerifiedIdentityCard
        user={mockVerifiedCurrentUser}
        variant="dashboard"
      />,
    );

    expect(screen.getByText("TesterBot Resident")).toBeInTheDocument();
    expect(screen.getByText(/15 years/)).toBeInTheDocument();
    expect(screen.getByText("Payment info used")).toBeInTheDocument();
    expect(screen.getByText(/Verified:/)).toBeInTheDocument();
  });

  it("public variant omits pay info and verifiedAt", () => {
    renderWithProviders(
      <VerifiedIdentityCard user={mockPublicProfile} variant="public" />,
    );

    expect(screen.getByText("TesterBot Resident")).toBeInTheDocument();
    expect(screen.queryByText("Payment info used")).not.toBeInTheDocument();
    expect(screen.queryByText(/Verified:/)).not.toBeInTheDocument();
  });

  it("account age < 12 months shows months", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-15T00:00:00Z"));

    const user = {
      ...mockVerifiedCurrentUser,
      slBornDate: "2025-09-20",
    };

    renderWithProviders(
      <VerifiedIdentityCard user={user} variant="dashboard" />,
    );

    expect(screen.getByText(/6 months/)).toBeInTheDocument();
  });

  it("omits account age when slBornDate is null", () => {
    const user = {
      ...mockVerifiedCurrentUser,
      slBornDate: null,
    };

    renderWithProviders(
      <VerifiedIdentityCard user={user} variant="dashboard" />,
    );

    expect(screen.queryByText(/Account age/)).not.toBeInTheDocument();
  });

  it("renders slDisplayName subtitle when distinct from slAvatarName", () => {
    renderWithProviders(
      <VerifiedIdentityCard
        user={mockVerifiedCurrentUser}
        variant="dashboard"
      />,
    );

    // slAvatarName is "TesterBot Resident", slDisplayName is "TesterBot"
    expect(screen.getByText("TesterBot Resident")).toBeInTheDocument();
    expect(screen.getByText("TesterBot")).toBeInTheDocument();
  });
});
