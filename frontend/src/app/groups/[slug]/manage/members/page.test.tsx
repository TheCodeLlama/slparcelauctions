import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import GroupMembersPage from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));
vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({
    data: {
      publicId: "g-1",
      slug: "sunset-realty",
      name: "Sunset Realty",
      leader: {
        userPublicId: "u-me",
        displayName: "Leader",
        avatarUrl: null,
      },
      agents: [],
    },
    isPending: false,
  }),
  useLeaveGroup: () => ({ mutate: vi.fn(), isPending: false }),
  useRemoveMember: () => ({ mutate: vi.fn(), isPending: false }),
}));
vi.mock("@/lib/user", () => ({
  useCurrentUser: () => ({ data: { publicId: "u-me" }, isPending: false }),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/members", () => {
  it("renders the members table for the leader", () => {
    wrap(<GroupMembersPage />);
    // MembersTab renders both the leader's displayName ("Leader") and a
    // "Leader" role badge for the same row, so the text appears multiple
    // times — assert at least one occurrence.
    expect(screen.getAllByText(/leader/i).length).toBeGreaterThan(0);
  });
});
