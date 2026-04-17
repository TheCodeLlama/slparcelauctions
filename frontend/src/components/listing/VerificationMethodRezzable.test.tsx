import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type { PendingVerification } from "@/types/auction";
import { VerificationMethodRezzable } from "./VerificationMethodRezzable";

function pendingBase(overrides: Partial<PendingVerification> = {}): PendingVerification {
  return {
    method: "REZZABLE",
    code: "PARCEL-7K3A9X",
    codeExpiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    botTaskId: null,
    instructions: null,
    ...overrides,
  };
}

describe("VerificationMethodRezzable", () => {
  it("renders the code and a live countdown when unexpired", () => {
    renderWithProviders(
      <VerificationMethodRezzable auctionId={42} pending={pendingBase()} />,
    );
    expect(screen.getByText("PARCEL-7K3A9X")).toBeInTheDocument();
    expect(screen.getByText(/Expires in/i)).toBeInTheDocument();
  });

  it("exposes a Regenerate button once the code has expired", async () => {
    let called = false;
    server.use(
      http.put("*/api/v1/auctions/42/verify", () => {
        called = true;
        return HttpResponse.json({
          id: 42,
          status: "VERIFICATION_PENDING",
          verificationMethod: "REZZABLE",
          pendingVerification: pendingBase({ code: "PARCEL-NEWONE" }),
          parcel: {},
        });
      }),
    );
    renderWithProviders(
      <VerificationMethodRezzable
        auctionId={42}
        pending={pendingBase({
          codeExpiresAt: new Date(Date.now() - 60_000).toISOString(),
        })}
      />,
      { auth: "authenticated" },
    );
    const regen = await screen.findByRole("button", {
      name: /Regenerate code/i,
    });
    await userEvent.click(regen);
    await waitFor(() => expect(called).toBe(true));
  });
});
