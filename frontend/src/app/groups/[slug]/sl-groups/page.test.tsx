import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import SlGroupsPageRoute from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));
vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({
    data: { publicId: "g-1", slug: "sunset-realty" },
    isPending: false,
  }),
}));
vi.mock("@/components/realty/slgroup/SlGroupsPage", () => ({
  SlGroupsPage: ({ groupPublicId }: { groupPublicId: string }) => (
    <div data-testid="sl-groups-page">{groupPublicId}</div>
  ),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/sl-groups", () => {
  it("passes resolved publicId into SlGroupsPage", () => {
    wrap(<SlGroupsPageRoute />);
    expect(screen.getByTestId("sl-groups-page")).toHaveTextContent("g-1");
  });
});
