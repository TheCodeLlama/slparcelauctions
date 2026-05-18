import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ErrorBoundary } from "./ErrorBoundary";

function Boom(): never {
  throw new Error("ledger render exploded");
}

describe("ErrorBoundary", () => {
  // React logs the caught error to console.error; silence it so the test
  // output stays readable while still asserting the boundary behaviour.
  beforeEach(() => {
    vi.spyOn(console, "error").mockImplementation(() => {});
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders children when nothing throws", () => {
    render(
      <ErrorBoundary fallback={<div>fallback</div>}>
        <div>healthy ledger</div>
      </ErrorBoundary>,
    );
    expect(screen.getByText("healthy ledger")).toBeInTheDocument();
    expect(screen.queryByText("fallback")).not.toBeInTheDocument();
  });

  it("renders the fallback when a child throws, sibling content survives", () => {
    render(
      <div>
        <span>balance card stays</span>
        <ErrorBoundary
          fallback={<div role="alert">Couldn&apos;t display transactions</div>}
        >
          <Boom />
        </ErrorBoundary>
      </div>,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Couldn't display transactions",
    );
    // The boundary is scoped: content outside it is untouched (no white-screen).
    expect(screen.getByText("balance card stays")).toBeInTheDocument();
  });
});
