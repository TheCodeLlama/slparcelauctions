import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { RespondModal } from "./RespondModal";

describe("RespondModal", () => {
  it("does not render when open=false", () => {
    renderWithProviders(
      <RespondModal reviewId={5} open={false} onClose={() => {}} />,
      { auth: "authenticated" },
    );
    expect(screen.queryByTestId("respond-modal")).not.toBeInTheDocument();
  });

  it("disables submit until the textarea has non-whitespace content", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RespondModal reviewId={5} open onClose={() => {}} />,
      { auth: "authenticated" },
    );
    const submit = screen.getByTestId("respond-modal-submit");
    expect(submit).toBeDisabled();
    await user.type(
      screen.getByTestId("respond-modal-textarea"),
      "   ",
    );
    expect(submit).toBeDisabled();
    await user.type(
      screen.getByTestId("respond-modal-textarea"),
      "Thanks!",
    );
    expect(submit).not.toBeDisabled();
  });

  it("shows a live character counter", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RespondModal reviewId={5} open onClose={() => {}} />,
      { auth: "authenticated" },
    );
    await user.type(screen.getByTestId("respond-modal-textarea"), "hi");
    expect(screen.getByTestId("respond-modal-counter").textContent).toContain(
      "2 / 500",
    );
  });

  it("submits and closes on success", async () => {
    const onClose = vi.fn();
    server.use(
      http.post("*/api/v1/reviews/:id/respond", () =>
        HttpResponse.json(
          { id: 99, text: "ok", createdAt: "2026-04-22T00:00:00Z" },
          { status: 201 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <RespondModal reviewId={5} open onClose={onClose} />,
      { auth: "authenticated" },
    );
    await user.type(
      screen.getByTestId("respond-modal-textarea"),
      "Thanks!",
    );
    await user.click(screen.getByTestId("respond-modal-submit"));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("closes on 409 duplicate-response even though the mutation errors", async () => {
    const onClose = vi.fn();
    server.use(
      http.post("*/api/v1/reviews/:id/respond", () =>
        HttpResponse.json({ status: 409 }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <RespondModal reviewId={5} open onClose={onClose} />,
      { auth: "authenticated" },
    );
    await user.type(
      screen.getByTestId("respond-modal-textarea"),
      "Thanks!",
    );
    await user.click(screen.getByTestId("respond-modal-submit"));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});
