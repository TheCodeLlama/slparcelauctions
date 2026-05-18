import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import BrowseError from "./error";

describe("browse/error.tsx route boundary", () => {
  // The component logs the caught error via console.error for log
  // correlation; silence it so the test output stays readable.
  beforeEach(() => {
    vi.spyOn(console, "error").mockImplementation(() => {});
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders the branded fallback heading and copy", () => {
    render(
      <BrowseError error={new Error("boom")} reset={() => {}} />,
    );
    expect(
      screen.getByRole("heading", { name: /couldn't load browse/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /try again/i }),
    ).toBeInTheDocument();
    // Recovery affordance back to an unfiltered browse.
    const link = screen.getByRole("link", { name: /browse with no filters/i });
    expect(link).toHaveAttribute("href", "/browse");
  });

  it("invokes the reset callback when Try again is clicked", async () => {
    const reset = vi.fn();
    render(<BrowseError error={new Error("boom")} reset={reset} />);
    await userEvent.click(
      screen.getByRole("button", { name: /try again/i }),
    );
    expect(reset).toHaveBeenCalledTimes(1);
  });

  it("logs the caught error for log correlation", () => {
    const spy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});
    const err = Object.assign(new Error("kaboom"), { digest: "abc123" });
    render(<BrowseError error={err} reset={() => {}} />);
    expect(spy).toHaveBeenCalledWith(
      expect.stringContaining("[browse] route error boundary caught:"),
      err,
    );
  });
});
