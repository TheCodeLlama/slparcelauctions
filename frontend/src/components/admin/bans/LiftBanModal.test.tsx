import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { LiftBanModal } from "./LiftBanModal";
import type { AdminBanRow } from "@/lib/admin/types";

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/bans"),
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: vi.fn(() => new URLSearchParams()),
}));

function makeBan(overrides: Partial<AdminBanRow> = {}): AdminBanRow {
  return {
    id: 10,
    banType: "IP",
    ipAddress: "10.0.0.1",
    slAvatarUuid: null,
    avatarLinkedUserId: null,
    avatarLinkedDisplayName: null,
    firstSeenIp: null,
    reasonCategory: "TOS_ABUSE",
    reasonText: "Repeated ToS violations",
    bannedByUserId: 1,
    bannedByDisplayName: "AdminUser",
    expiresAt: null,
    createdAt: "2026-04-01T10:00:00Z",
    liftedAt: null,
    liftedByUserId: null,
    liftedByDisplayName: null,
    liftedReason: null,
    ...overrides,
  };
}

describe("LiftBanModal", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("submit button is disabled when notes are empty", () => {
    renderWithProviders(
      <LiftBanModal ban={makeBan()} onClose={vi.fn()} />
    );
    expect(screen.getByTestId("lift-ban-submit")).toBeDisabled();
  });

  it("calls lift mutation on submit and closes modal", async () => {
    const ban = makeBan({ id: 10 });
    server.use(adminHandlers.liftBanSuccess(ban));
    server.use(adminHandlers.statsSuccess());

    const onClose = vi.fn();
    const user = userEvent.setup();

    renderWithProviders(
      <LiftBanModal ban={ban} onClose={onClose} />
    );

    await user.type(screen.getByTestId("lift-reason-textarea"), "Reason to lift this ban");
    await user.click(screen.getByTestId("lift-ban-submit"));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("shows toast on BAN_ALREADY_LIFTED 409 error", async () => {
    const ban = makeBan({ id: 10 });
    server.use(adminHandlers.ban409AlreadyLifted(10));
    server.use(adminHandlers.bansListSuccess([]));

    const user = userEvent.setup();

    renderWithProviders(
      <LiftBanModal ban={ban} onClose={vi.fn()} />
    );

    await user.type(screen.getByTestId("lift-reason-textarea"), "Lifting it");
    await user.click(screen.getByTestId("lift-ban-submit"));

    await waitFor(() =>
      expect(screen.getByText(/already lifted/i)).toBeInTheDocument()
    );
  });
});
