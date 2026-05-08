import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { DeleteDraftModal } from "./DeleteDraftModal";
import type { SellerAuctionResponse } from "@/types/auction";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const fixture = {
  publicId: "abc",
  status: "DRAFT",
} as unknown as SellerAuctionResponse;

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

describe("DeleteDraftModal", () => {
  it("renders DRAFT-specific copy", () => {
    renderWithProviders(
      <DeleteDraftModal open onClose={vi.fn()} auction={fixture} />,
    );
    expect(screen.getByText(/Delete this draft\?/i)).toBeInTheDocument();
    expect(screen.getByText(/can't be undone/i)).toBeInTheDocument();
  });

  it("calls cancelAuction on confirm", async () => {
    let called = false;
    server.use(
      http.put("*/api/v1/auctions/abc/cancel", async () => {
        called = true;
        return HttpResponse.json({ ...fixture, status: "CANCELLED" });
      }),
    );
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <DeleteDraftModal open onClose={onClose} auction={fixture} />,
    );
    await user.click(screen.getByTestId("delete-draft-modal-confirm"));
    await vi.waitFor(() => expect(called).toBe(true));
  });
});
