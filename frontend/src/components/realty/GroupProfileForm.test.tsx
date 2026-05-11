import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, userEvent, waitFor } from "@/test/render";
import { server } from "@/test/msw/server";
import type {
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";
import { GroupProfileForm } from "./GroupProfileForm";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/dashboard/groups/g/manage",
}));

function makeGroup(
  overrides: Partial<RealtyGroupPublicDto> = {},
): RealtyGroupPublicDto {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    description: "A friendly brokerage.",
    website: null,
    logoUrl: null,
    coverUrl: null,
    memberSince: "2026-04-01T10:00:00Z",
    leader: {
      userPublicId: "11111111-1111-1111-1111-111111111111",
      displayName: "Leader",
      avatarUrl: null,
    },
    agents: [],
    agentFeeRate: "0.0000",
    agentFeeSplit: "0.5000",
    memberSeatLimit: 50,
    memberCount: 1,
    ...overrides,
  };
}

function permSet(...flags: RealtyGroupPermission[]) {
  return new Set(flags);
}

describe("GroupProfileForm", () => {
  it("renders fields with current group values", () => {
    renderWithProviders(
      <GroupProfileForm
        group={makeGroup()}
        callerPermissions={permSet("EDIT_GROUP_PROFILE", "CONFIGURE_FEES")}
        isLeader={false}
      />,
    );
    expect(screen.getByTestId("group-profile-name")).toHaveValue(
      "Mainland Realty",
    );
    expect(screen.getByTestId("group-profile-description")).toHaveValue(
      "A friendly brokerage.",
    );
    expect(screen.getByTestId("group-profile-fee-rate")).toHaveValue("0.0000");
  });

  it("disables profile fields when caller lacks EDIT_GROUP_PROFILE", () => {
    renderWithProviders(
      <GroupProfileForm
        group={makeGroup()}
        callerPermissions={permSet("CONFIGURE_FEES")}
        isLeader={false}
      />,
    );
    expect(screen.getByTestId("group-profile-name")).toBeDisabled();
    expect(screen.getByTestId("group-profile-description")).toBeDisabled();
    expect(screen.getByTestId("group-profile-website")).toBeDisabled();
    expect(screen.getByTestId("group-profile-fee-rate")).not.toBeDisabled();
  });

  it("disables fee fields when caller lacks CONFIGURE_FEES", () => {
    renderWithProviders(
      <GroupProfileForm
        group={makeGroup()}
        callerPermissions={permSet("EDIT_GROUP_PROFILE")}
        isLeader={false}
      />,
    );
    expect(screen.getByTestId("group-profile-fee-rate")).toBeDisabled();
    expect(screen.getByTestId("group-profile-fee-split")).toBeDisabled();
    expect(screen.getByTestId("group-profile-name")).not.toBeDisabled();
  });

  it("enables all fields when caller is leader regardless of perms set", () => {
    renderWithProviders(
      <GroupProfileForm
        group={makeGroup()}
        callerPermissions={permSet()}
        isLeader={true}
      />,
    );
    expect(screen.getByTestId("group-profile-name")).not.toBeDisabled();
    expect(screen.getByTestId("group-profile-fee-rate")).not.toBeDisabled();
  });

  it("submits and shows a success toast", async () => {
    server.use(
      http.patch("*/api/v1/realty-groups/:id", () =>
        HttpResponse.json({
          ...makeGroup(),
          name: "Mainland Realty 2",
        }),
      ),
    );
    renderWithProviders(
      <GroupProfileForm
        group={makeGroup()}
        callerPermissions={permSet()}
        isLeader={true}
      />,
    );
    const name = screen.getByTestId("group-profile-name");
    await userEvent.clear(name);
    await userEvent.type(name, "Mainland Realty 2");
    await userEvent.click(screen.getByTestId("group-profile-submit"));
    await waitFor(() =>
      expect(screen.getByText(/Group updated/i)).toBeInTheDocument(),
    );
  });

  it("surfaces the rename-cooldown toast when the backend rejects", async () => {
    server.use(
      http.patch("*/api/v1/realty-groups/:id", () =>
        HttpResponse.json(
          {
            type: "https://slpa.example/problems/realty",
            title: "GROUP_RENAME_COOLDOWN",
            status: 409,
            detail: "GROUP_RENAME_COOLDOWN",
            code: "GROUP_RENAME_COOLDOWN",
            cooldownEndsAt: "2026-06-01T00:00:00Z",
          },
          { status: 409 },
        ),
      ),
    );
    renderWithProviders(
      <GroupProfileForm
        group={makeGroup()}
        callerPermissions={permSet()}
        isLeader={true}
      />,
    );
    const name = screen.getByTestId("group-profile-name");
    await userEvent.clear(name);
    await userEvent.type(name, "Renamed");
    await userEvent.click(screen.getByTestId("group-profile-submit"));
    await waitFor(() =>
      expect(
        screen.getByText(/Renames are limited to once every 30 days/i),
      ).toBeInTheDocument(),
    );
  });
});
