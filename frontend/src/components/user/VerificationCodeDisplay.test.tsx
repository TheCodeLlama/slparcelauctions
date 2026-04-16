import { describe, it, expect } from "vitest";
import { renderWithProviders, screen, waitFor, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { authHandlers, verificationHandlers } from "@/test/msw/handlers";
import { VerificationCodeDisplay } from "./VerificationCodeDisplay";

function setup() {
  server.use(authHandlers.refreshSuccess());
}

describe("VerificationCodeDisplay", () => {
  it("shows Generate button when no active code", async () => {
    setup();
    server.use(verificationHandlers.activeNone());
    renderWithProviders(<VerificationCodeDisplay />, {
      auth: "authenticated",
    });
    expect(
      await screen.findByRole("button", {
        name: /generate verification code/i,
      }),
    ).toBeInTheDocument();
  });

  it("clicking generate triggers mutation and renders the code", async () => {
    setup();
    server.use(
      verificationHandlers.activeNone(),
      verificationHandlers.generateSuccess("654321", "2026-04-14T21:15:00Z"),
    );
    const user = userEvent.setup();
    renderWithProviders(<VerificationCodeDisplay />, {
      auth: "authenticated",
    });

    const btn = await screen.findByRole("button", {
      name: /generate verification code/i,
    });

    // Swap the active handler BEFORE clicking so the post-invalidation
    // refetch (which fires synchronously inside the mutation's onSuccess)
    // returns the newly generated code.
    server.use(
      verificationHandlers.activeExists("654321", "2026-04-14T21:15:00Z"),
    );

    await user.click(btn);

    expect(await screen.findByText("654321")).toBeInTheDocument();
  });

  it("renders existing active code from initial fetch", async () => {
    setup();
    server.use(
      verificationHandlers.activeExists("123456", "2026-04-14T21:00:00Z"),
    );
    renderWithProviders(<VerificationCodeDisplay />, {
      auth: "authenticated",
    });
    expect(await screen.findByText("123456")).toBeInTheDocument();
    expect(
      screen.getByText(
        /enter this code at any slpa verification terminal/i,
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole("timer")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /regenerate code/i }),
    ).toBeInTheDocument();
  });

  it("shows confirm dialog when Regenerate Code is clicked", async () => {
    setup();
    server.use(
      verificationHandlers.activeExists("123456", "2026-04-14T21:00:00Z"),
    );
    const user = userEvent.setup();
    renderWithProviders(<VerificationCodeDisplay />, {
      auth: "authenticated",
    });

    const regenerateBtn = await screen.findByRole("button", {
      name: /regenerate code/i,
    });
    await user.click(regenerateBtn);

    expect(
      screen.getByText(/this will invalidate the current code/i),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /cancel/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /yes, regenerate/i }),
    ).toBeInTheDocument();
  });

  it("cancel on confirm returns to previous state", async () => {
    setup();
    server.use(
      verificationHandlers.activeExists("123456", "2026-04-14T21:00:00Z"),
    );
    const user = userEvent.setup();
    renderWithProviders(<VerificationCodeDisplay />, {
      auth: "authenticated",
    });

    const regenerateBtn = await screen.findByRole("button", {
      name: /regenerate code/i,
    });
    await user.click(regenerateBtn);

    const cancelBtn = screen.getByRole("button", { name: /cancel/i });
    await user.click(cancelBtn);

    await waitFor(() => {
      expect(
        screen.queryByText(/this will invalidate the current code/i),
      ).not.toBeInTheDocument();
    });
    expect(
      screen.getByRole("button", { name: /regenerate code/i }),
    ).toBeInTheDocument();
  });

  it("confirm Yes, regenerate triggers generate mutation", async () => {
    setup();
    server.use(
      verificationHandlers.activeExists("123456", "2026-04-14T21:00:00Z"),
      verificationHandlers.generateSuccess("999888", "2026-04-14T21:30:00Z"),
    );
    const user = userEvent.setup();
    renderWithProviders(<VerificationCodeDisplay />, {
      auth: "authenticated",
    });

    const regenerateBtn = await screen.findByRole("button", {
      name: /regenerate code/i,
    });
    await user.click(regenerateBtn);

    // Swap active handler so post-invalidation refetch returns the new code.
    server.use(
      verificationHandlers.activeExists("999888", "2026-04-14T21:30:00Z"),
    );

    const confirmBtn = screen.getByRole("button", {
      name: /yes, regenerate/i,
    });
    await user.click(confirmBtn);

    expect(await screen.findByText("999888")).toBeInTheDocument();
  });
});
