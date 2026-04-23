import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import type { ConnectionState } from "@/lib/ws/types";
import { ReconnectingBanner } from "./ReconnectingBanner";

describe("ReconnectingBanner", () => {
  it("renders nothing when state is connected", () => {
    const { container } = render(
      <ReconnectingBanner state={{ status: "connected" }} />,
    );
    expect(container.firstChild).toBeNull();
  });

  it("renders nothing when state is connecting (initial load handled by skeleton)", () => {
    const { container } = render(
      <ReconnectingBanner state={{ status: "connecting" }} />,
    );
    expect(container.firstChild).toBeNull();
  });

  it("renders the reconnecting copy with a spinner when state is reconnecting", () => {
    render(<ReconnectingBanner state={{ status: "reconnecting" }} />);
    const banner = screen.getByTestId("reconnecting-banner");
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveAttribute("data-tone", "warning");
    expect(banner).toHaveTextContent(/Reconnecting/);
    expect(banner).toHaveTextContent(/bids paused/);
    // Loader2 renders as an svg — the `animate-spin` class is the
    // distinguishing signal that the spinner is present.
    expect(banner.querySelector(".animate-spin")).not.toBeNull();
  });

  it("renders an error banner with a Reload button when disconnected", () => {
    const reloadSpy = vi.fn();
    const origLocation = window.location;
    // Override location.reload so the test can observe the click.
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...origLocation, reload: reloadSpy },
    });

    render(<ReconnectingBanner state={{ status: "disconnected" }} />);
    const banner = screen.getByTestId("reconnecting-banner");
    expect(banner).toHaveAttribute("data-tone", "error");
    expect(banner).toHaveTextContent(/Connection lost/);

    const reload = screen.getByTestId("reconnecting-banner-reload");
    fireEvent.click(reload);
    expect(reloadSpy).toHaveBeenCalledTimes(1);

    Object.defineProperty(window, "location", {
      configurable: true,
      value: origLocation,
    });
  });

  it("surfaces state.detail and a sign-in link on a session-expired error", () => {
    const state: ConnectionState = {
      status: "error",
      detail: "Session expired. Please sign in again.",
    };
    render(<ReconnectingBanner state={state} />);
    const banner = screen.getByTestId("reconnecting-banner");
    expect(banner).toHaveTextContent(/Session expired/);
    const signIn = screen.getByTestId("reconnecting-banner-signin");
    expect(signIn).toHaveAttribute("href", "/login");
  });

  it("surfaces state.detail only for a generic error (no sign-in link)", () => {
    const state: ConnectionState = {
      status: "error",
      detail: "Upstream STOMP broker reset the connection",
    };
    render(<ReconnectingBanner state={state} />);
    const banner = screen.getByTestId("reconnecting-banner");
    expect(banner).toHaveTextContent(/STOMP broker reset/);
    expect(screen.queryByTestId("reconnecting-banner-signin")).toBeNull();
  });

  it("falls back to 'Connection error.' when error detail is empty", () => {
    const state: ConnectionState = {
      status: "error",
      detail: "",
    };
    render(<ReconnectingBanner state={state} />);
    const banner = screen.getByTestId("reconnecting-banner");
    expect(banner).toHaveTextContent(/Connection error/);
  });
});
