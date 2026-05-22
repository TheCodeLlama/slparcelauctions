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
    logoLightUrl: null,
    logoDarkUrl: null,
    coverLightUrl: null,
    coverDarkUrl: null,
    defaultListingLightUrl: null,
    defaultListingDarkUrl: null,
    memberSince: "2026-04-01T10:00:00Z",
    leader: {
      userPublicId: "11111111-1111-1111-1111-111111111111",
      displayName: "Leader",
      avatarUrl: null,
    },
    agents: [],
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
        callerPermissions={permSet("EDIT_GROUP_PROFILE")}
        isLeader={false}
      />,
    );
    expect(screen.getByTestId("group-profile-name")).toHaveValue(
      "Mainland Realty",
    );
    expect(screen.getByTestId("group-profile-description")).toHaveValue(
      "A friendly brokerage.",
    );
  });

  it("disables profile fields when caller lacks EDIT_GROUP_PROFILE", () => {
    renderWithProviders(
      <GroupProfileForm
        group={makeGroup()}
        callerPermissions={permSet()}
        isLeader={false}
      />,
    );
    expect(screen.getByTestId("group-profile-name")).toBeDisabled();
    expect(screen.getByTestId("group-profile-description")).toBeDisabled();
    expect(screen.getByTestId("group-profile-website")).toBeDisabled();
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
    expect(screen.getByTestId("group-profile-description")).not.toBeDisabled();
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

  // ─── Dual-slot logo + cover ──────────────────────────────────────────────

  describe("logo dual-slot", () => {
    it("renders both Light and Dark slots, each with its own empty + Upload affordance", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup()}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      expect(screen.getByTestId("group-profile-logo-light-slot")).toBeInTheDocument();
      expect(screen.getByTestId("group-profile-logo-dark-slot")).toBeInTheDocument();
      expect(screen.getByTestId("group-profile-logo-light-empty")).toBeInTheDocument();
      expect(screen.getByTestId("group-profile-logo-dark-empty")).toBeInTheDocument();
      expect(screen.getByTestId("group-profile-logo-light-upload-button")).toBeInTheDocument();
      expect(screen.getByTestId("group-profile-logo-dark-upload-button")).toBeInTheDocument();
    });

    it("posts to the light variant endpoint when the light slot picks a file", async () => {
      let lightCalls = 0;
      let darkCalls = 0;
      server.use(
        http.post(
          "*/api/v1/realty-groups/:id/logo/light",
          () => {
            lightCalls += 1;
            return HttpResponse.json(
              makeGroup({
                logoLightUrl: "/api/v1/realty-groups/g/logo/image?variant=light",
              }),
            );
          },
        ),
        http.post(
          "*/api/v1/realty-groups/:id/logo/dark",
          () => {
            darkCalls += 1;
            return HttpResponse.json(makeGroup());
          },
        ),
      );
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup()}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      const input = screen.getByTestId("group-profile-logo-light-input") as HTMLInputElement;
      const file = new File([new Uint8Array([1, 2, 3])], "logo.png", {
        type: "image/png",
      });
      await userEvent.upload(input, file);
      await waitFor(() => expect(lightCalls).toBe(1));
      expect(darkCalls).toBe(0);
    });

    it("renders the variant image when its URL is populated and exposes a Remove button", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup({
            logoLightUrl: "/api/v1/realty-groups/g/logo/image?variant=light",
          })}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      expect(screen.getByTestId("group-profile-logo-light-image")).toBeInTheDocument();
      expect(screen.queryByTestId("group-profile-logo-light-empty")).not.toBeInTheDocument();
      expect(
        screen.getByTestId("group-profile-logo-light-delete-button"),
      ).toBeInTheDocument();
      // Dark slot stays empty + has no Remove affordance.
      expect(screen.getByTestId("group-profile-logo-dark-empty")).toBeInTheDocument();
      expect(
        screen.queryByTestId("group-profile-logo-dark-delete-button"),
      ).not.toBeInTheDocument();
    });

    it("issues DELETE to the variant endpoint when the per-slot Remove is clicked", async () => {
      let darkDeletes = 0;
      server.use(
        http.delete("*/api/v1/realty-groups/:id/logo/dark", () => {
          darkDeletes += 1;
          return HttpResponse.json(makeGroup());
        }),
      );
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup({
            logoDarkUrl: "/api/v1/realty-groups/g/logo/image?variant=dark",
          })}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      await userEvent.click(
        screen.getByTestId("group-profile-logo-dark-delete-button"),
      );
      await waitFor(() => expect(darkDeletes).toBe(1));
    });
  });

  describe("cover dual-slot", () => {
    it("renders both Light and Dark slots", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup()}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      expect(screen.getByTestId("group-profile-cover-light-slot")).toBeInTheDocument();
      expect(screen.getByTestId("group-profile-cover-dark-slot")).toBeInTheDocument();
      expect(screen.getByTestId("group-profile-cover-light-upload-button")).toBeInTheDocument();
      expect(screen.getByTestId("group-profile-cover-dark-upload-button")).toBeInTheDocument();
    });

    it("posts to the dark variant endpoint when the dark slot picks a file", async () => {
      let darkCalls = 0;
      server.use(
        http.post(
          "*/api/v1/realty-groups/:id/cover/dark",
          () => {
            darkCalls += 1;
            return HttpResponse.json(makeGroup());
          },
        ),
      );
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup()}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      const input = screen.getByTestId("group-profile-cover-dark-input") as HTMLInputElement;
      const file = new File([new Uint8Array([1, 2, 3])], "cover.jpg", {
        type: "image/jpeg",
      });
      await userEvent.upload(input, file);
      await waitFor(() => expect(darkCalls).toBe(1));
    });
  });

  describe("default-listing dual-slot", () => {
    it("renders the Default listing picture section heading + subtitle", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup()}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      expect(
        screen.getByText("Default listing picture"),
      ).toBeInTheDocument();
      expect(
        screen.getByText(
          /Used as the first photo on every listing created on behalf of this group/i,
        ),
      ).toBeInTheDocument();
    });

    it("renders both Light and Dark slots with their own Upload affordances", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup()}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      expect(
        screen.getByTestId("group-profile-default-listing-light-slot"),
      ).toBeInTheDocument();
      expect(
        screen.getByTestId("group-profile-default-listing-dark-slot"),
      ).toBeInTheDocument();
      expect(
        screen.getByTestId("group-profile-default-listing-light-empty"),
      ).toBeInTheDocument();
      expect(
        screen.getByTestId("group-profile-default-listing-dark-empty"),
      ).toBeInTheDocument();
      expect(
        screen.getByTestId(
          "group-profile-default-listing-light-upload-button",
        ),
      ).toBeInTheDocument();
      expect(
        screen.getByTestId(
          "group-profile-default-listing-dark-upload-button",
        ),
      ).toBeInTheDocument();
    });

    it("posts to the light variant endpoint when the light slot picks a file", async () => {
      let lightCalls = 0;
      let darkCalls = 0;
      server.use(
        http.post(
          "*/api/v1/realty-groups/:id/default-listing/light",
          () => {
            lightCalls += 1;
            return HttpResponse.json(
              makeGroup({
                defaultListingLightUrl:
                  "/api/v1/realty-groups/g/default-listing/image?variant=light",
              }),
            );
          },
        ),
        http.post(
          "*/api/v1/realty-groups/:id/default-listing/dark",
          () => {
            darkCalls += 1;
            return HttpResponse.json(makeGroup());
          },
        ),
      );
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup()}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      const input = screen.getByTestId(
        "group-profile-default-listing-light-input",
      ) as HTMLInputElement;
      const file = new File([new Uint8Array([1, 2, 3])], "listing.png", {
        type: "image/png",
      });
      await userEvent.upload(input, file);
      await waitFor(() => expect(lightCalls).toBe(1));
      expect(darkCalls).toBe(0);
    });

    it("posts to the dark variant endpoint when the dark slot picks a file", async () => {
      let lightCalls = 0;
      let darkCalls = 0;
      server.use(
        http.post(
          "*/api/v1/realty-groups/:id/default-listing/light",
          () => {
            lightCalls += 1;
            return HttpResponse.json(makeGroup());
          },
        ),
        http.post(
          "*/api/v1/realty-groups/:id/default-listing/dark",
          () => {
            darkCalls += 1;
            return HttpResponse.json(makeGroup());
          },
        ),
      );
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup()}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      const input = screen.getByTestId(
        "group-profile-default-listing-dark-input",
      ) as HTMLInputElement;
      const file = new File([new Uint8Array([1, 2, 3])], "listing.jpg", {
        type: "image/jpeg",
      });
      await userEvent.upload(input, file);
      await waitFor(() => expect(darkCalls).toBe(1));
      expect(lightCalls).toBe(0);
    });

    it("renders the variant image + Remove button when the URL is populated", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup({
            defaultListingLightUrl:
              "/api/v1/realty-groups/g/default-listing/image?variant=light",
          })}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      expect(
        screen.getByTestId("group-profile-default-listing-light-image"),
      ).toBeInTheDocument();
      expect(
        screen.queryByTestId("group-profile-default-listing-light-empty"),
      ).not.toBeInTheDocument();
      expect(
        screen.getByTestId(
          "group-profile-default-listing-light-delete-button",
        ),
      ).toBeInTheDocument();
      // Dark slot stays empty + has no Remove affordance.
      expect(
        screen.getByTestId("group-profile-default-listing-dark-empty"),
      ).toBeInTheDocument();
      expect(
        screen.queryByTestId(
          "group-profile-default-listing-dark-delete-button",
        ),
      ).not.toBeInTheDocument();
    });

    it("issues DELETE to the dark variant endpoint when its Remove is clicked", async () => {
      let lightDeletes = 0;
      let darkDeletes = 0;
      server.use(
        http.delete(
          "*/api/v1/realty-groups/:id/default-listing/light",
          () => {
            lightDeletes += 1;
            return HttpResponse.json(makeGroup());
          },
        ),
        http.delete(
          "*/api/v1/realty-groups/:id/default-listing/dark",
          () => {
            darkDeletes += 1;
            return HttpResponse.json(makeGroup());
          },
        ),
      );
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup({
            defaultListingDarkUrl:
              "/api/v1/realty-groups/g/default-listing/image?variant=dark",
          })}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      await userEvent.click(
        screen.getByTestId(
          "group-profile-default-listing-dark-delete-button",
        ),
      );
      await waitFor(() => expect(darkDeletes).toBe(1));
      expect(lightDeletes).toBe(0);
    });
  });

  describe("default-listing theme-aware preview", () => {
    it("renders the light variant URL in the preview when the theme is light", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup({
            defaultListingLightUrl:
              "/api/v1/realty-groups/g/default-listing/image?variant=light",
            defaultListingDarkUrl:
              "/api/v1/realty-groups/g/default-listing/image?variant=dark",
          })}
          callerPermissions={permSet()}
          isLeader={true}
        />,
        { theme: "light" },
      );
      const preview = screen.getByTestId(
        "group-profile-default-listing-preview-image",
      ) as HTMLImageElement;
      expect(preview.getAttribute("src")).toContain("variant=light");
    });

    it("renders the dark variant URL in the preview when the theme is dark", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup({
            defaultListingLightUrl:
              "/api/v1/realty-groups/g/default-listing/image?variant=light",
            defaultListingDarkUrl:
              "/api/v1/realty-groups/g/default-listing/image?variant=dark",
          })}
          callerPermissions={permSet()}
          isLeader={true}
        />,
        { theme: "dark" },
      );
      const preview = screen.getByTestId(
        "group-profile-default-listing-preview-image",
      ) as HTMLImageElement;
      expect(preview.getAttribute("src")).toContain("variant=dark");
    });

    it("renders an empty-state placeholder when both variants are null", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup()}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      expect(
        screen.getByTestId("group-profile-default-listing-preview-empty"),
      ).toBeInTheDocument();
      expect(
        screen.queryByTestId(
          "group-profile-default-listing-preview-image",
        ),
      ).not.toBeInTheDocument();
    });
  });

  describe("theme-aware preview", () => {
    it("renders the light variant URL in the preview when the current theme is light", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup({
            logoLightUrl: "/api/v1/realty-groups/g/logo/image?variant=light",
            logoDarkUrl: "/api/v1/realty-groups/g/logo/image?variant=dark",
          })}
          callerPermissions={permSet()}
          isLeader={true}
        />,
        { theme: "light" },
      );
      const preview = screen.getByTestId(
        "group-profile-logo-preview-image",
      ) as HTMLImageElement;
      expect(preview.getAttribute("src")).toContain("variant=light");
    });

    it("renders the dark variant URL in the preview when the current theme is dark", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup({
            logoLightUrl: "/api/v1/realty-groups/g/logo/image?variant=light",
            logoDarkUrl: "/api/v1/realty-groups/g/logo/image?variant=dark",
          })}
          callerPermissions={permSet()}
          isLeader={true}
        />,
        { theme: "dark" },
      );
      const preview = screen.getByTestId(
        "group-profile-logo-preview-image",
      ) as HTMLImageElement;
      expect(preview.getAttribute("src")).toContain("variant=dark");
    });

    it("falls back to the sibling variant in the preview when the matched variant is null", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup({
            logoLightUrl: "/api/v1/realty-groups/g/logo/image?variant=light",
            logoDarkUrl: null,
          })}
          callerPermissions={permSet()}
          isLeader={true}
        />,
        { theme: "dark" },
      );
      const preview = screen.getByTestId(
        "group-profile-logo-preview-image",
      ) as HTMLImageElement;
      // dark is null - fall back to light
      expect(preview.getAttribute("src")).toContain("variant=light");
    });

    it("renders an empty-state placeholder when both variants are null", () => {
      renderWithProviders(
        <GroupProfileForm
          group={makeGroup()}
          callerPermissions={permSet()}
          isLeader={true}
        />,
      );
      expect(
        screen.getByTestId("group-profile-logo-preview-empty"),
      ).toBeInTheDocument();
      expect(
        screen.queryByTestId("group-profile-logo-preview-image"),
      ).not.toBeInTheDocument();
    });
  });
});
