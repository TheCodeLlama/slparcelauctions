import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import CommissionsAnalyticsPageRoute from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));
vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({
    data: { publicId: "g-1", slug: "sunset-realty" },
    isPending: false,
  }),
}));
vi.mock("@/components/realty/analytics/GroupCommissionAnalyticsPage", () => ({
  GroupCommissionAnalyticsPage: ({
    groupPublicId,
  }: {
    groupPublicId: string;
  }) => <div data-testid="commission-analytics">{groupPublicId}</div>,
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/analytics/commissions", () => {
  it("passes resolved publicId into GroupCommissionAnalyticsPage", () => {
    wrap(<CommissionsAnalyticsPageRoute />);
    expect(screen.getByTestId("commission-analytics")).toHaveTextContent("g-1");
  });
});
