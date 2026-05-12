import { describe, expect, it } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { realtySlGroupHandlers } from "@/test/msw/handlers";
import type { RealtyGroupSlGroup } from "@/types/realty";
import { SlGroupListRow } from "./SlGroupListRow";

const GROUP_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const SL_GROUP_ID = "11111111-1111-1111-1111-111111111111";

function pendingRow(overrides: Partial<RealtyGroupSlGroup> = {}): RealtyGroupSlGroup {
  return {
    publicId: SL_GROUP_ID,
    slGroupUuid: "22222222-2222-2222-2222-222222222222",
    slGroupName: null,
    verified: false,
    verifiedAt: null,
    verifiedVia: null,
    pending: {
      verificationCode: "SLPA-1A2B3C4D5E6F",
      verificationCodeExpiresAt: new Date(
        Date.now() + 30 * 60 * 1000,
      ).toISOString(),
      lastPolledAt: null,
      pollAttempts: 0,
    },
    founderAvatarUuid: null,
    ...overrides,
  };
}

function verifiedRow(overrides: Partial<RealtyGroupSlGroup> = {}): RealtyGroupSlGroup {
  return {
    publicId: SL_GROUP_ID,
    slGroupUuid: "22222222-2222-2222-2222-222222222222",
    slGroupName: "Sunset Estates",
    verified: true,
    verifiedAt: "2026-05-12T20:00:00Z",
    verifiedVia: "FOUNDER_TERMINAL",
    pending: null,
    founderAvatarUuid: "33333333-3333-3333-3333-333333333333",
    ...overrides,
  };
}

function renderRow(row: RealtyGroupSlGroup) {
  return renderWithProviders(
    <table>
      <tbody>
        <SlGroupListRow groupPublicId={GROUP_ID} row={row} />
      </tbody>
    </table>,
  );
}

describe("SlGroupListRow", () => {
  it("renders the pending state with code, countdown, and Check now button", () => {
    renderRow(pendingRow());
    expect(screen.getByTestId("status-pending")).toBeInTheDocument();
    expect(screen.getByTestId("verification-code").textContent).toBe(
      "SLPA-1A2B3C4D5E6F",
    );
    expect(screen.getByTestId("expiry-countdown")).toBeInTheDocument();
    expect(screen.getByTestId("recheck-button")).toBeInTheDocument();
    expect(screen.queryByTestId("verified-via")).not.toBeInTheDocument();
  });

  it("renders the verified state with name, verified-via, and no Check now button", () => {
    renderRow(verifiedRow());
    expect(screen.getByTestId("status-verified")).toBeInTheDocument();
    expect(screen.getByTestId("sl-group-name").textContent).toContain(
      "Sunset Estates",
    );
    expect(screen.getByTestId("verified-via").textContent).toBe(
      "Founder terminal",
    );
    expect(screen.queryByTestId("recheck-button")).not.toBeInTheDocument();
    expect(screen.getByTestId("unregister-button")).toBeInTheDocument();
  });

  it("opens the unregister confirmation modal when Unregister is clicked", async () => {
    renderRow(verifiedRow());
    await userEvent.click(screen.getByTestId("unregister-button"));
    expect(
      screen.getByRole("dialog", { name: /Unregister SL group/i }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("confirm-unregister-button")).toBeInTheDocument();
  });

  it("dismisses the modal on a successful unregister", async () => {
    server.use(realtySlGroupHandlers.unregisterSuccess());
    renderRow(verifiedRow());
    await userEvent.click(screen.getByTestId("unregister-button"));
    await userEvent.click(screen.getByTestId("confirm-unregister-button"));
    // Wait a tick for the mutation to resolve and the modal to close.
    await screen.findByTestId("sl-group-row");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("shows the active-listings error when unregister is blocked by 409", async () => {
    server.use(realtySlGroupHandlers.unregisterBlockedByListings());
    renderRow(pendingRow());
    await userEvent.click(screen.getByTestId("unregister-button"));
    await userEvent.click(screen.getByTestId("confirm-unregister-button"));
    expect(
      await screen.findByTestId("unregister-error"),
    ).toBeInTheDocument();
  });
});
