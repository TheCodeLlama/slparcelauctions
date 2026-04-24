import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type { ParcelDto } from "@/types/parcel";
import { ParcelLookupField } from "./ParcelLookupField";

const VALID_UUID = "00000000-0000-0000-0000-000000000001";

const sampleParcel: ParcelDto = {
  id: 42,
  slParcelUuid: VALID_UUID,
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
  snapshotUrl: null,
  slurl: "secondlife://Heterocera/128/128/25",
  maturityRating: "GENERAL",
  verified: false,
  verifiedAt: null,
  lastChecked: null,
  createdAt: "2026-04-17T00:00:00Z",
};

describe("ParcelLookupField", () => {
  it("calls lookupParcel and surfaces the result", async () => {
    server.use(
      http.post("*/api/v1/parcels/lookup", async () =>
        HttpResponse.json(sampleParcel),
      ),
    );
    const onResolved = vi.fn();
    renderWithProviders(<ParcelLookupField onResolved={onResolved} />);
    await userEvent.type(screen.getByLabelText(/Parcel UUID/i), VALID_UUID);
    await userEvent.click(screen.getByRole("button", { name: /Look up/i }));
    await screen.findByText("Beachfront retreat");
    await waitFor(() =>
      expect(onResolved).toHaveBeenCalledWith(
        expect.objectContaining({ id: 42 }),
      ),
    );
  });

  it("rejects malformed UUIDs without hitting the backend", async () => {
    const onResolved = vi.fn();
    // No MSW handler registered — if the code hits the backend here the
    // "onUnhandledRequest: 'error'" guard in vitest.setup.ts would fail
    // the test.
    renderWithProviders(<ParcelLookupField onResolved={onResolved} />);
    await userEvent.type(screen.getByLabelText(/Parcel UUID/i), "not-a-uuid");
    await userEvent.click(screen.getByRole("button", { name: /Look up/i }));
    expect(
      await screen.findByText(/Enter a valid UUID/i),
    ).toBeInTheDocument();
    expect(onResolved).not.toHaveBeenCalled();
  });

  it("maps 404 to a friendly not-found message", async () => {
    server.use(
      http.post("*/api/v1/parcels/lookup", () =>
        HttpResponse.json(
          { status: 404, title: "Parcel not found" },
          { status: 404 },
        ),
      ),
    );
    renderWithProviders(<ParcelLookupField onResolved={vi.fn()} />);
    await userEvent.type(screen.getByLabelText(/Parcel UUID/i), VALID_UUID);
    await userEvent.click(screen.getByRole("button", { name: /Look up/i }));
    expect(
      await screen.findByText(/couldn't find this parcel/i),
    ).toBeInTheDocument();
  });

  it("shows an initial parcel card and hides the lookup button when locked", () => {
    renderWithProviders(
      <ParcelLookupField
        initialParcel={sampleParcel}
        locked
        onResolved={vi.fn()}
      />,
    );
    expect(screen.getByText("Beachfront retreat")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Look up/i }),
    ).not.toBeInTheDocument();
  });
});
