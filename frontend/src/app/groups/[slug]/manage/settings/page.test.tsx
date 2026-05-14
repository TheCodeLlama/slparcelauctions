import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import GroupSettingsPage from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

let leaderId = "u-me";

vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({
    data: {
      publicId: "g-1",
      slug: "sunset-realty",
      name: "Sunset Realty",
      leader: { userPublicId: leaderId, displayName: "L", avatarUrl: null },
      agents: [],
    },
    isPending: false,
  }),
  useDissolveGroup: () => ({ mutate: vi.fn(), isPending: false }),
  useTransferLeadership: () => ({ mutate: vi.fn(), isPending: false }),
}));

vi.mock("@/lib/user", () => ({
  useCurrentUser: () => ({ data: { publicId: "u-me" }, isPending: false }),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/settings", () => {
  it("renders settings for the leader", () => {
    leaderId = "u-me";
    wrap(<GroupSettingsPage />);
    expect(screen.getAllByText(/transfer/i).length).toBeGreaterThan(0);
  });

  it("renders forbidden notice for non-leader", () => {
    leaderId = "u-someone-else";
    wrap(<GroupSettingsPage />);
    expect(screen.getByText(/leader/i)).toBeInTheDocument();
  });
});
