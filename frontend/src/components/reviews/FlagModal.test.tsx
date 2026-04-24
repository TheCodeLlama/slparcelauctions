import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { FlagModal } from "./FlagModal";

describe("FlagModal", () => {
  it("renders the 5 reason radios", () => {
    renderWithProviders(<FlagModal reviewId={5} open onClose={() => {}} />, {
      auth: "authenticated",
    });
    for (const code of ["SPAM", "ABUSIVE", "OFF_TOPIC", "FALSE_INFO", "OTHER"]) {
      expect(screen.getByTestId(`flag-modal-reason-${code}`)).toBeInTheDocument();
    }
  });

  it("keeps submit disabled until a reason is picked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<FlagModal reviewId={5} open onClose={() => {}} />, {
      auth: "authenticated",
    });
    expect(screen.getByTestId("flag-modal-submit")).toBeDisabled();
    await user.click(screen.getByTestId("flag-modal-reason-SPAM"));
    expect(screen.getByTestId("flag-modal-submit")).not.toBeDisabled();
  });

  it("requires elaboration when reason is OTHER", async () => {
    const user = userEvent.setup();
    renderWithProviders(<FlagModal reviewId={5} open onClose={() => {}} />, {
      auth: "authenticated",
    });
    await user.click(screen.getByTestId("flag-modal-reason-OTHER"));
    expect(screen.getByTestId("flag-modal-submit")).toBeDisabled();
    await user.type(
      screen.getByTestId("flag-modal-elaboration"),
      "Because reasons",
    );
    expect(screen.getByTestId("flag-modal-submit")).not.toBeDisabled();
  });

  it("submits with the selected reason and closes on success", async () => {
    const onClose = vi.fn();
    let capturedBody: unknown = null;
    server.use(
      http.post("*/api/v1/reviews/:id/flag", async ({ request }) => {
        capturedBody = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<FlagModal reviewId={5} open onClose={onClose} />, {
      auth: "authenticated",
    });
    await user.click(screen.getByTestId("flag-modal-reason-SPAM"));
    await user.click(screen.getByTestId("flag-modal-submit"));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(capturedBody).toEqual({ reason: "SPAM" });
  });

  it("includes elaboration in the payload when provided", async () => {
    const onClose = vi.fn();
    let capturedBody: unknown = null;
    server.use(
      http.post("*/api/v1/reviews/:id/flag", async ({ request }) => {
        capturedBody = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<FlagModal reviewId={5} open onClose={onClose} />, {
      auth: "authenticated",
    });
    await user.click(screen.getByTestId("flag-modal-reason-OTHER"));
    await user.type(
      screen.getByTestId("flag-modal-elaboration"),
      "Explains the thing",
    );
    await user.click(screen.getByTestId("flag-modal-submit"));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(capturedBody).toEqual({
      reason: "OTHER",
      elaboration: "Explains the thing",
    });
  });

  it("closes on 409 duplicate-flag", async () => {
    const onClose = vi.fn();
    server.use(
      http.post("*/api/v1/reviews/:id/flag", () =>
        HttpResponse.json({ status: 409 }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<FlagModal reviewId={5} open onClose={onClose} />, {
      auth: "authenticated",
    });
    await user.click(screen.getByTestId("flag-modal-reason-SPAM"));
    await user.click(screen.getByTestId("flag-modal-submit"));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});
