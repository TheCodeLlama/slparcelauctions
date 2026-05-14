import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import GroupWalletPageRoute from "./page";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "sunset-realty" }),
}));

vi.mock("@/hooks/realty/useRealtyGroups", () => ({
  useRealtyGroupBySlug: () => ({
    data: { publicId: "g-1", slug: "sunset-realty", name: "Sunset Realty" },
    isPending: false,
  }),
}));

vi.mock("@/components/realty/wallet/GroupWalletPage", () => ({
  GroupWalletPage: ({ publicId }: { publicId: string }) => (
    <div data-testid="wallet-page">wallet:{publicId}</div>
  ),
}));

function wrap(node: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/groups/[slug]/wallet", () => {
  it("passes resolved publicId into GroupWalletPage", () => {
    wrap(<GroupWalletPageRoute />);
    expect(screen.getByTestId("wallet-page")).toHaveTextContent("wallet:g-1");
  });
});
