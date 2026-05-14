import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "mainland-realty" }),
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
}));

const GROUP_PUBLIC_ID = "00000000-0000-0000-0000-000000000001";

vi.mock("@/hooks/realty/useRealtyGroups", async () => {
  const actual = await vi.importActual<
    typeof import("@/hooks/realty/useRealtyGroups")
  >("@/hooks/realty/useRealtyGroups");
  return {
    ...actual,
    useRealtyGroupBySlug: () => ({
      data: {
        publicId: GROUP_PUBLIC_ID,
        slug: "mainland-realty",
        name: "Mainland Realty",
      },
      isPending: false,
      isError: false,
    }),
  };
});

const adminDetailMock = vi.fn(
  ({ publicId }: { publicId: string }) => (
    <div data-testid="admin-realty-detail-page-stub">{publicId}</div>
  ),
);

vi.mock(
  "@/components/admin/realty-groups/AdminRealtyGroupDetailPage",
  () => ({
    AdminRealtyGroupDetailPage: (props: { publicId: string }) =>
      adminDetailMock(props),
  }),
);

import AdminGroupDetailRoute from "./page";

function wrap(node: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{node}</QueryClientProvider>);
}

describe("/admin/groups/[slug]", () => {
  it("resolves slug to publicId and renders AdminRealtyGroupDetailPage", () => {
    wrap(<AdminGroupDetailRoute />);
    expect(
      screen.getByTestId("admin-realty-detail-page-stub"),
    ).toHaveTextContent(GROUP_PUBLIC_ID);
    expect(adminDetailMock).toHaveBeenCalledWith({ publicId: GROUP_PUBLIC_ID });
  });
});
