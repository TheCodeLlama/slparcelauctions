import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import type { ParcelDto } from "@/types/parcel";
import { ParcelLookupCard } from "./ParcelLookupCard";

const parcel: ParcelDto = {
  id: 1,
  slParcelUuid: "00000000-0000-0000-0000-000000000001",
  ownerUuid: "aaaa1111-0000-0000-0000-000000000000",
  ownerType: "agent",
  regionName: "Heterocera",
  gridX: 1000,
  gridY: 1000,
  positionX: 128,
  positionY: 128,
  positionZ: 0,
  continentName: "Heterocera",
  areaSqm: 1024,
  description: "Beachfront retreat",
  snapshotUrl: "https://snapshot.example/p/1.png",
  slurl: "secondlife://Heterocera/128/128/25",
  maturityRating: "GENERAL",
  verified: false,
  verifiedAt: null,
  lastChecked: null,
  createdAt: "2026-04-17T00:00:00Z",
};

describe("ParcelLookupCard", () => {
  it("renders parcel metadata and a Second Life link", () => {
    renderWithProviders(<ParcelLookupCard parcel={parcel} />);
    expect(screen.getByText("Beachfront retreat")).toBeInTheDocument();
    expect(
      screen.getByText(/Heterocera \(1000, 1000\) · 1024 m²/),
    ).toBeInTheDocument();
    expect(screen.getByText(/Owner UUID:.*\(agent\)/)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /Visit in Second Life/i }),
    ).toHaveAttribute("href", parcel.slurl);
  });

  it("shows maturity rating and grid coordinates from spec §4.1.1", () => {
    renderWithProviders(<ParcelLookupCard parcel={parcel} />);
    expect(screen.getByText("General")).toBeInTheDocument();
    expect(
      screen.getByText(/\(1000, 1000\)/),
    ).toBeInTheDocument();
  });

  it("renders a placeholder when the parcel description is blank", () => {
    renderWithProviders(
      <ParcelLookupCard
        parcel={{ ...parcel, description: "   ", snapshotUrl: null }}
      />,
    );
    expect(screen.getByText("(unnamed parcel)")).toBeInTheDocument();
  });
});
