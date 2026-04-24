import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { VisitInSecondLifeBlock } from "./VisitInSecondLifeBlock";

describe("VisitInSecondLifeBlock", () => {
  it("renders viewer + map buttons with correct hrefs", () => {
    renderWithProviders(
      <VisitInSecondLifeBlock
        regionName="Heterocera"
        positionX={20}
        positionY={30}
        positionZ={40}
      />,
    );
    expect(screen.getByTestId("visit-in-sl-viewer")).toHaveAttribute(
      "href",
      "secondlife:///app/teleport/Heterocera/20/30/40",
    );
    expect(screen.getByTestId("visit-in-sl-map")).toHaveAttribute(
      "href",
      "https://maps.secondlife.com/secondlife/Heterocera/20/30/40",
    );
  });

  it("keeps spaces raw in the viewer href and percent-encodes them in the map href", () => {
    renderWithProviders(
      <VisitInSecondLifeBlock
        regionName="Bay City"
        positionX={20}
        positionY={30}
        positionZ={40}
      />,
    );
    expect(screen.getByTestId("visit-in-sl-viewer").getAttribute("href")).toBe(
      "secondlife:///app/teleport/Bay City/20/30/40",
    );
    expect(screen.getByTestId("visit-in-sl-map").getAttribute("href")).toBe(
      "https://maps.secondlife.com/secondlife/Bay%20City/20/30/40",
    );
  });

  it("falls back to the region-centre 128/128/0 when positions are null", () => {
    renderWithProviders(
      <VisitInSecondLifeBlock
        regionName="Heterocera"
        positionX={null}
        positionY={null}
        positionZ={null}
      />,
    );
    expect(screen.getByTestId("visit-in-sl-viewer").getAttribute("href")).toBe(
      "secondlife:///app/teleport/Heterocera/128/128/0",
    );
    expect(screen.getByTestId("visit-in-sl-map").getAttribute("href")).toBe(
      "https://maps.secondlife.com/secondlife/Heterocera/128/128/0",
    );
  });

  it("opens the map link in a new tab", () => {
    renderWithProviders(
      <VisitInSecondLifeBlock
        regionName="Heterocera"
        positionX={20}
        positionY={30}
        positionZ={40}
      />,
    );
    const mapLink = screen.getByTestId("visit-in-sl-map");
    expect(mapLink).toHaveAttribute("target", "_blank");
    expect(mapLink).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("renders an explanatory line", () => {
    renderWithProviders(
      <VisitInSecondLifeBlock
        regionName="Heterocera"
        positionX={20}
        positionY={30}
        positionZ={40}
      />,
    );
    expect(screen.getByText(/Preview the parcel in-world/)).toBeInTheDocument();
  });

  it("renders under the dark theme without crashing", () => {
    renderWithProviders(
      <VisitInSecondLifeBlock
        regionName="Heterocera"
        positionX={20}
        positionY={30}
        positionZ={40}
      />,
      { theme: "dark", forceTheme: true },
    );
    expect(screen.getByTestId("visit-in-sl-block")).toBeInTheDocument();
  });
});
