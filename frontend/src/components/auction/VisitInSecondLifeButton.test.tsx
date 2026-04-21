import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import {
  VisitInSecondLifeButton,
  buildViewerHref,
  buildMapHref,
} from "./VisitInSecondLifeButton";

describe("VisitInSecondLifeButton URL builders", () => {
  it("builds a secondlife:// viewer href", () => {
    expect(buildViewerHref("Heterocera", 128, 64, 25)).toBe(
      "secondlife:///app/teleport/Heterocera/128/64/25",
    );
  });

  it("builds a maps.secondlife.com href", () => {
    expect(buildMapHref("Heterocera", 128, 64, 25)).toBe(
      "https://maps.secondlife.com/secondlife/Heterocera/128/64/25",
    );
  });

  it("url-encodes region names that contain spaces", () => {
    expect(buildViewerHref("Bay City", 1, 2, 3)).toBe(
      "secondlife:///app/teleport/Bay%20City/1/2/3",
    );
    expect(buildMapHref("Bay City", 1, 2, 3)).toBe(
      "https://maps.secondlife.com/secondlife/Bay%20City/1/2/3",
    );
  });

  it("url-encodes special characters (apostrophe, punctuation)", () => {
    expect(buildViewerHref("Pirate's Cove", 0, 0, 0)).toBe(
      "secondlife:///app/teleport/Pirate's%20Cove/0/0/0",
    );
  });
});

describe("VisitInSecondLifeButton component", () => {
  let originalLocation: Location;
  let openSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    originalLocation = window.location;
    // Replace window.location with a settable stub so "Open in Viewer"
    // doesn't actually try to navigate the happy-dom page.
    Object.defineProperty(window, "location", {
      value: { href: "" },
      writable: true,
    });
    openSpy = vi.fn();
    vi.stubGlobal("open", openSpy);
  });

  afterEach(() => {
    Object.defineProperty(window, "location", {
      value: originalLocation,
      writable: true,
    });
    vi.unstubAllGlobals();
  });

  it("opens the dropdown on click and renders both options", async () => {
    renderWithProviders(
      <VisitInSecondLifeButton
        regionName="Heterocera"
        positionX={128}
        positionY={64}
        positionZ={25}
      />,
    );

    await userEvent.click(screen.getByText("Visit in Second Life"));

    expect(screen.getByText("Open in Viewer")).toBeInTheDocument();
    expect(screen.getByText("View on Map")).toBeInTheDocument();
  });

  it("navigates via secondlife:// protocol when 'Open in Viewer' is selected", async () => {
    renderWithProviders(
      <VisitInSecondLifeButton
        regionName="Heterocera"
        positionX={128}
        positionY={64}
        positionZ={25}
      />,
    );

    await userEvent.click(screen.getByText("Visit in Second Life"));
    await userEvent.click(screen.getByText("Open in Viewer"));

    expect(window.location.href).toBe(
      "secondlife:///app/teleport/Heterocera/128/64/25",
    );
  });

  it("opens maps.secondlife.com in a new tab when 'View on Map' is selected", async () => {
    renderWithProviders(
      <VisitInSecondLifeButton
        regionName="Bay City"
        positionX={1}
        positionY={2}
        positionZ={3}
      />,
    );

    await userEvent.click(screen.getByText("Visit in Second Life"));
    await userEvent.click(screen.getByText("View on Map"));

    expect(openSpy).toHaveBeenCalledWith(
      "https://maps.secondlife.com/secondlife/Bay%20City/1/2/3",
      "_blank",
      "noopener,noreferrer",
    );
  });
});
