import { describe, it, expect, beforeAll, afterEach, afterAll, vi } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { AdminFeaturedBoardsTable } from "./AdminFeaturedBoardsTable";
import type { AdminFeaturedBoardRow } from "@/lib/api/adminFeaturedBoards";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const rowA: AdminFeaturedBoardRow = {
  slotPublicId: "slot-1",
  boardIndex: 1,
  position: 1,
  auctionPublicId: "auc-1",
  auctionTitle: "Ocean View Parcel",
  currentBid: 5000,
  endsAt: "2026-07-01T00:00:00Z",
  assignedAt: "2026-06-01T00:00:00Z",
};

const rowB: AdminFeaturedBoardRow = {
  slotPublicId: "slot-2",
  boardIndex: 2,
  position: 1,
  auctionPublicId: "auc-2",
  auctionTitle: "Mountain Retreat",
  currentBid: 12500,
  endsAt: "2026-07-05T00:00:00Z",
  assignedAt: "2026-06-02T00:00:00Z",
};

describe("AdminFeaturedBoardsTable", () => {
  it("renders empty boards as 'empty' when no rows", () => {
    renderWithProviders(
      <AdminFeaturedBoardsTable initial={[]} slotCount={3} />,
    );

    // All 3 board headings present.
    expect(screen.getByText("Board 1")).toBeInTheDocument();
    expect(screen.getByText("Board 2")).toBeInTheDocument();
    expect(screen.getByText("Board 3")).toBeInTheDocument();

    // All boards show the empty placeholder.
    const empties = screen.getAllByText("empty");
    expect(empties).toHaveLength(3);
  });

  it("renders rows with auction title and bid", () => {
    renderWithProviders(
      <AdminFeaturedBoardsTable initial={[rowA, rowB]} slotCount={2} />,
    );

    expect(screen.getByText("Ocean View Parcel")).toBeInTheDocument();
    expect(screen.getByText("Mountain Retreat")).toBeInTheDocument();

    // currentBid formatted with toLocaleString -- locale-agnostic check.
    expect(screen.getByText(/5,000|5\.000/)).toBeInTheDocument();
    expect(screen.getByText(/12,500|12\.500/)).toBeInTheDocument();
  });

  it("calls releaseSlot and removes the row on confirm", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);

    let released = "";
    server.use(
      http.post("*/api/v1/admin/featured-boards/:id/release", ({ params }) => {
        released = params.id as string;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(
      <AdminFeaturedBoardsTable initial={[rowA, rowB]} slotCount={2} />,
    );

    const releaseButtons = screen.getAllByText("Release");
    await user.click(releaseButtons[0]);

    await waitFor(() => expect(released).toBe("slot-1"));
    // Row removed from display.
    expect(screen.queryByText("Ocean View Parcel")).not.toBeInTheDocument();
    // Other row still present.
    expect(screen.getByText("Mountain Retreat")).toBeInTheDocument();
  });

  it("does not call releaseSlot when confirm is cancelled", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(false);

    let called = false;
    server.use(
      http.post("*/api/v1/admin/featured-boards/:id/release", () => {
        called = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(
      <AdminFeaturedBoardsTable initial={[rowA]} slotCount={2} />,
    );

    await user.click(screen.getByText("Release"));
    expect(called).toBe(false);
    expect(screen.getByText("Ocean View Parcel")).toBeInTheDocument();
  });
});
