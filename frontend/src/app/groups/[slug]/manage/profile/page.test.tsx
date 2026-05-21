import { beforeEach, describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import GroupProfilePage from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));

const useRealtyGroupBySlug = vi.fn();
vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: (slug: string) => useRealtyGroupBySlug(slug),
  // GroupProfileForm pulls upload + delete mutations for both surfaces; we
  // don't exercise them here, so minimal idle-state stubs keep the form's
  // submit/upload/delete branches dormant.
  useUpdateGroup: () => ({
    mutate: vi.fn(),
    mutateAsync: vi.fn(),
    isPending: false,
  }),
  useUploadLogo: () => ({ mutate: vi.fn(), isPending: false }),
  useDeleteLogo: () => ({ mutate: vi.fn(), isPending: false }),
  useUploadCover: () => ({ mutate: vi.fn(), isPending: false }),
  useDeleteCover: () => ({ mutate: vi.fn(), isPending: false }),
}));

const useCurrentUser = vi.fn();
vi.mock("@/lib/user", () => ({
  useCurrentUser: () => useCurrentUser(),
}));

function makeGroup({
  leaderPublicId,
  agents = [],
}: {
  leaderPublicId: string;
  agents?: Array<{ userPublicId: string; permissions: string[] }>;
}) {
  return {
    publicId: "g-1",
    slug: "sunset-realty",
    name: "Sunset Realty",
    description: null,
    website: null,
    logoLightUrl: null, logoDarkUrl: null,
    coverLightUrl: null, coverDarkUrl: null,
    memberSince: "2026-01-01T00:00:00Z",
    memberCount: 1 + agents.length,
    memberSeatLimit: 50,
    leader: {
      userPublicId: leaderPublicId,
      displayName: "Leader",
      avatarUrl: null,
    },
    agents: agents.map((a) => ({
      userPublicId: a.userPublicId,
      displayName: "Agent",
      avatarUrl: null,
      permissions: a.permissions,
      role: "AGENT" as const,
    })),
  };
}

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/profile", () => {
  beforeEach(() => {
    useRealtyGroupBySlug.mockReset();
    useCurrentUser.mockReset();
  });

  it("renders the profile form for the leader", () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: makeGroup({ leaderPublicId: "u-me" }),
      isPending: false,
    });
    useCurrentUser.mockReturnValue({
      data: { publicId: "u-me" },
      isPending: false,
    });

    wrap(<GroupProfilePage />);

    // The Name input is enabled for the leader.
    const nameInput = screen.getByTestId("group-profile-name");
    expect(nameInput).toBeInTheDocument();
    expect(nameInput).not.toBeDisabled();
  });

  it("renders disabled fields for an agent without EDIT_GROUP_PROFILE", () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: makeGroup({
        leaderPublicId: "u-leader",
        agents: [{ userPublicId: "u-me", permissions: [] }],
      }),
      isPending: false,
    });
    useCurrentUser.mockReturnValue({
      data: { publicId: "u-me" },
      isPending: false,
    });

    wrap(<GroupProfilePage />);

    expect(screen.getByTestId("group-profile-name")).toBeDisabled();
    expect(screen.getByTestId("group-profile-description")).toBeDisabled();
    expect(screen.getByTestId("group-profile-website")).toBeDisabled();
  });

  it("enables fields for an agent who holds EDIT_GROUP_PROFILE", () => {
    useRealtyGroupBySlug.mockReturnValue({
      data: makeGroup({
        leaderPublicId: "u-leader",
        agents: [
          { userPublicId: "u-me", permissions: ["EDIT_GROUP_PROFILE"] },
        ],
      }),
      isPending: false,
    });
    useCurrentUser.mockReturnValue({
      data: { publicId: "u-me" },
      isPending: false,
    });

    wrap(<GroupProfilePage />);

    expect(screen.getByTestId("group-profile-name")).not.toBeDisabled();
  });

  it("renders a loading spinner while the group is pending", () => {
    useRealtyGroupBySlug.mockReturnValue({ data: undefined, isPending: true });
    useCurrentUser.mockReturnValue({ data: undefined, isPending: false });

    wrap(<GroupProfilePage />);

    expect(screen.getByText(/loading profile/i)).toBeInTheDocument();
  });

  it("renders nothing when group data is unavailable post-load", () => {
    useRealtyGroupBySlug.mockReturnValue({ data: undefined, isPending: false });
    useCurrentUser.mockReturnValue({
      data: { publicId: "u-me" },
      isPending: false,
    });

    const { container } = wrap(<GroupProfilePage />);
    expect(container).toBeEmptyDOMElement();
  });
});
