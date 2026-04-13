// frontend/src/components/marketing/LivePill.test.tsx

import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { LivePill } from "./LivePill";

describe("LivePill", () => {
  it("renders the label text", () => {
    renderWithProviders(<LivePill>Live Auctions Active</LivePill>);
    expect(screen.getByText("Live Auctions Active")).toBeInTheDocument();
  });

  it("includes an element with animate-ping class for the pulse effect", () => {
    const { container } = renderWithProviders(
      <LivePill>Live Auctions Active</LivePill>
    );
    const pingElement = container.querySelector(".animate-ping");
    expect(pingElement).not.toBeNull();
  });
});
