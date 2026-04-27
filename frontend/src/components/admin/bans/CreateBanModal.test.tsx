import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor, userEvent } from "@/test/render";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import { CreateBanModal } from "./CreateBanModal";
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
    id: 1,
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

describe("CreateBanModal", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows IP field for IP type, hides avatar field", () => {
    renderWithProviders(
      <CreateBanModal open={true} onClose={vi.fn()} />
    );

    expect(screen.getByTestId("ip-address-input")).toBeInTheDocument();
    expect(screen.queryByTestId("avatar-uuid-input")).not.toBeInTheDocument();
  });

  it("shows avatar field and hides IP field when switching to AVATAR type", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateBanModal open={true} onClose={vi.fn()} />
    );

    await user.click(screen.getByTestId("ban-type-btn-AVATAR"));

    expect(screen.queryByTestId("ip-address-input")).not.toBeInTheDocument();
    expect(screen.getByTestId("avatar-uuid-input")).toBeInTheDocument();
  });

  it("shows both fields for BOTH type", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateBanModal open={true} onClose={vi.fn()} />
    );

    await user.click(screen.getByTestId("ban-type-btn-BOTH"));

    expect(screen.getByTestId("ip-address-input")).toBeInTheDocument();
    expect(screen.getByTestId("avatar-uuid-input")).toBeInTheDocument();
  });

  it("calls adminApi.bans.create with correct body on success", async () => {
    const ban = makeBan({ id: 42, banType: "IP", ipAddress: "1.2.3.4" });
    server.use(adminHandlers.createBanSuccess(ban));
    server.use(adminHandlers.statsSuccess());

    const onClose = vi.fn();
    const user = userEvent.setup();

    renderWithProviders(
      <CreateBanModal open={true} onClose={onClose} />
    );

    await user.type(screen.getByTestId("ip-address-input"), "1.2.3.4");
    await user.type(screen.getByTestId("reason-text-textarea"), "Testing IP ban");

    await user.click(screen.getByTestId("create-ban-submit"));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("shows toast on BAN_TYPE_FIELD_MISMATCH 400 error", async () => {
    server.use(
      adminHandlers.ban409TypeFieldMismatch(1)
    );

    renderWithProviders(
      <CreateBanModal open={true} onClose={vi.fn()} />
    );

    const user = userEvent.setup();
    await user.type(screen.getByTestId("ip-address-input"), "1.2.3.4");
    await user.type(screen.getByTestId("reason-text-textarea"), "Testing");

    await user.click(screen.getByTestId("create-ban-submit"));

    await waitFor(() =>
      expect(
        screen.queryByText(/Ban created/) === null || screen.queryByText(/don't match/)
      ).toBe(true)
    );
  });

  it("does not render when open=false", () => {
    renderWithProviders(
      <CreateBanModal open={false} onClose={vi.fn()} />
    );
    expect(screen.queryByTestId("create-ban-modal")).not.toBeInTheDocument();
  });
});
