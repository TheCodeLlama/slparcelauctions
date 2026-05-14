import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import {
  fireEvent,
  renderWithProviders,
  screen,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type { RealtyGroupPublicDto } from "@/types/realty";
import RealtyGroupPublicPage, { dynamic } from "./page";

// next/navigation is mocked by vitest.setup.ts but `notFound` is server-only.
// Override the mock so we can spy on the call.
const notFoundSpy = vi.fn((): never => {
  throw new Error("NEXT_NOT_FOUND");
});
vi.mock("next/navigation", () => ({
  notFound: () => notFoundSpy(),
  usePathname: vi.fn(() => "/groups/mainland-realty"),
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: () => new URLSearchParams(),
}));

function makeGroup(
  overrides: Partial<RealtyGroupPublicDto> = {},
): RealtyGroupPublicDto {
  return {
    publicId: "00000000-0000-0000-0000-000000000001",
    name: "Mainland Realty",
    slug: "mainland-realty",
    description: "Premium Mainland brokerage.",
    website: null,
    logoUrl: null,
    coverUrl: null,
    memberSince: "2026-04-01T10:00:00Z",
    leader: {
      userPublicId: "11111111-1111-1111-1111-111111111111",
      displayName: "Avery Leader",
      avatarUrl: null,
    },
    agents: [],
    memberSeatLimit: 50,
    memberCount: 1,
    activeListingsCount: 0,
    completedSalesCount: 0,
    hasVerifiedSlGroup: false,
    ...overrides,
  };
}

describe("RealtyGroupPublicPage server component (/groups/[slug])", () => {
  beforeEach(() => {
    notFoundSpy.mockClear();
  });

  it("declares force-dynamic to opt out of static prerendering", () => {
    expect(dynamic).toBe("force-dynamic");
  });

  it("renders the template hero (name + tagline + stat grid) on success", async () => {
    server.use(
      http.get(
        "*/api/v1/realty-groups/by-slug/mainland-realty",
        () => HttpResponse.json(makeGroup()),
      ),
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json({ status: 401, title: "Unauthorized" }, { status: 401 }),
      ),
    );
    const ui = await RealtyGroupPublicPage({
      params: Promise.resolve({ slug: "mainland-realty" }),
    });
    renderWithProviders(ui);
    expect(
      screen.getByRole("heading", { level: 1, name: "Mainland Realty" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Premium Mainland brokerage.")).toBeInTheDocument();
    // Stat-grid labels confirm the template chrome is in place. "Lifetime
    // sales" appears only on the stat grid; "Active listings" appears there
    // and on the tab label, so we match the tab role for that one.
    expect(screen.getByText(/lifetime sales/i)).toBeInTheDocument();
    expect(screen.getByText(/^members$/i, { selector: "div" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /active listings/i })).toBeInTheDocument();
  });

  it("renders the Verified SL group badge when hasVerifiedSlGroup is true", async () => {
    server.use(
      http.get(
        "*/api/v1/realty-groups/by-slug/mainland-realty",
        () =>
          HttpResponse.json(makeGroup({ hasVerifiedSlGroup: true })),
      ),
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json({ status: 401, title: "Unauthorized" }, { status: 401 }),
      ),
    );
    const ui = await RealtyGroupPublicPage({
      params: Promise.resolve({ slug: "mainland-realty" }),
    });
    renderWithProviders(ui);
    expect(screen.getByText(/verified sl group/i)).toBeInTheDocument();
  });

  it("renders the leader (and agents) under the Members tab", async () => {
    server.use(
      http.get(
        "*/api/v1/realty-groups/by-slug/mainland-realty",
        () =>
          HttpResponse.json(
            makeGroup({
              memberCount: 3,
              agents: [
                {
                  memberPublicId: "22222222-2222-2222-2222-222222222222",
                  userPublicId: "33333333-3333-3333-3333-333333333333",
                  displayName: "Agent One",
                  avatarUrl: null,
                  role: "AGENT",
                  permissions: null,
                  joinedAt: null,
                  agentCommissionRate: null,
                },
                {
                  memberPublicId: "44444444-4444-4444-4444-444444444444",
                  userPublicId: "55555555-5555-5555-5555-555555555555",
                  displayName: "Agent Two",
                  avatarUrl: null,
                  role: "AGENT",
                  permissions: null,
                  joinedAt: null,
                  agentCommissionRate: null,
                },
                // Backend may include the leader row in `agents`; the component
                // filters it out so the leader is only rendered in the Leader
                // section of the Members tab.
                {
                  memberPublicId: "66666666-6666-6666-6666-666666666666",
                  userPublicId: "11111111-1111-1111-1111-111111111111",
                  displayName: "Avery Leader",
                  avatarUrl: null,
                  role: "LEADER",
                  permissions: null,
                  joinedAt: null,
                  agentCommissionRate: null,
                },
              ],
            }),
          ),
      ),
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json({ status: 401, title: "Unauthorized" }, { status: 401 }),
      ),
    );
    const ui = await RealtyGroupPublicPage({
      params: Promise.resolve({ slug: "mainland-realty" }),
    });
    renderWithProviders(ui);

    // Click the Members tab to flip the in-page state.
    fireEvent.click(screen.getByRole("tab", { name: /members/i }));

    await waitFor(() => {
      expect(screen.getByText("Group leader")).toBeInTheDocument();
    });
    expect(screen.getByText("Avery Leader")).toBeInTheDocument();
    expect(screen.getByText("Agent One")).toBeInTheDocument();
    expect(screen.getByText("Agent Two")).toBeInTheDocument();
    // Leader is filtered out of the Agents block — only one rendering.
    expect(screen.getAllByText("Avery Leader").length).toBe(1);
  });

  it("calls notFound() on a 404 response", async () => {
    server.use(
      http.get(
        "*/api/v1/realty-groups/by-slug/missing",
        () =>
          HttpResponse.json(
            { status: 404, title: "Not Found" },
            { status: 404 },
          ),
      ),
    );
    await expect(
      RealtyGroupPublicPage({
        params: Promise.resolve({ slug: "missing" }),
      }),
    ).rejects.toThrow("NEXT_NOT_FOUND");
    expect(notFoundSpy).toHaveBeenCalled();
  });

  it("renders the dissolved view on a 410 response with last-known name", async () => {
    server.use(
      http.get(
        "*/api/v1/realty-groups/by-slug/old-group",
        () =>
          HttpResponse.json(
            {
              status: 410,
              title: "Gone",
              detail: "GROUP_DISSOLVED",
              name: "Old Group",
              dissolvedAt: "2026-04-15T10:00:00Z",
            },
            { status: 410 },
          ),
      ),
    );
    const ui = await RealtyGroupPublicPage({
      params: Promise.resolve({ slug: "old-group" }),
    });
    renderWithProviders(ui);
    expect(
      screen.getByText("Old Group has been dissolved"),
    ).toBeInTheDocument();
    expect(screen.getByText(/April 15, 2026/)).toBeInTheDocument();
    expect(notFoundSpy).not.toHaveBeenCalled();
  });

  it("renders a generic dissolved message when the 410 body has no extras", async () => {
    server.use(
      http.get(
        "*/api/v1/realty-groups/by-slug/old-bare",
        () =>
          HttpResponse.json(
            { status: 410, title: "Gone", detail: "GROUP_DISSOLVED" },
            { status: 410 },
          ),
      ),
    );
    const ui = await RealtyGroupPublicPage({
      params: Promise.resolve({ slug: "old-bare" }),
    });
    renderWithProviders(ui);
    expect(
      screen.getByText("This realty group has been dissolved"),
    ).toBeInTheDocument();
  });

  it("bubbles 5xx errors so Next's error boundary handles them", async () => {
    server.use(
      http.get(
        "*/api/v1/realty-groups/by-slug/broken",
        () =>
          HttpResponse.json(
            { status: 500, title: "Internal Server Error" },
            { status: 500 },
          ),
      ),
    );
    await expect(
      RealtyGroupPublicPage({
        params: Promise.resolve({ slug: "broken" }),
      }),
    ).rejects.toMatchObject({ status: 500 });
    expect(notFoundSpy).not.toHaveBeenCalled();
  });

  it("renders the Manage group button (linking to /manage/profile) when the viewer is the leader", async () => {
    const leaderUser = {
      publicId: "11111111-1111-1111-1111-111111111111",
      username: "avery",
      email: "avery@example.com",
      displayName: "Avery Leader",
      slAvatarUuid: null,
      verified: true,
      role: "USER" as const,
    };
    server.use(
      http.get(
        "*/api/v1/realty-groups/by-slug/mainland-realty",
        () => HttpResponse.json(makeGroup()),
      ),
      http.get("*/api/v1/users/me", () => HttpResponse.json(leaderUser)),
    );
    const ui = await RealtyGroupPublicPage({
      params: Promise.resolve({ slug: "mainland-realty" }),
    });
    renderWithProviders(ui, { auth: "authenticated", authUser: leaderUser });
    await waitFor(() => {
      expect(screen.getByTestId("manage-group-btn")).toBeInTheDocument();
    });
    expect(screen.getByTestId("manage-group-btn").getAttribute("href")).toBe(
      "/groups/mainland-realty/manage/profile",
    );
    expect(screen.queryByTestId("contact-group-btn")).toBeNull();
  });

  it("renders the Contact group button (template default) for an anonymous viewer", async () => {
    server.use(
      http.get(
        "*/api/v1/realty-groups/by-slug/mainland-realty",
        () => HttpResponse.json(makeGroup()),
      ),
      http.get("*/api/v1/users/me", () =>
        HttpResponse.json({ status: 401, title: "Unauthorized" }, { status: 401 }),
      ),
    );
    const ui = await RealtyGroupPublicPage({
      params: Promise.resolve({ slug: "mainland-realty" }),
    });
    renderWithProviders(ui);
    expect(screen.getByTestId("contact-group-btn")).toBeInTheDocument();
    expect(screen.queryByTestId("manage-group-btn")).toBeNull();
  });
});
