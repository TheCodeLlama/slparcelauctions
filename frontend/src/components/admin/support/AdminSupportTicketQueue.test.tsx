import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import type {
  AdminSupportTicketQueueRow,
} from "@/types/support";
import type { Page } from "@/types/page";

const mockReplace = vi.fn();

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/support"),
  useRouter: () => ({
    push: vi.fn(),
    replace: mockReplace,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: vi.fn(() => new URLSearchParams()),
}));

vi.mock("@/hooks/admin/useAdminSupportTickets", () => ({
  useAdminSupportTickets: vi.fn(),
  adminSupportTicketsKey: (p: unknown) => ["admin-support-tickets", p] as const,
}));

import { useAdminSupportTickets } from "@/hooks/admin/useAdminSupportTickets";
import { AdminSupportTicketQueue } from "./AdminSupportTicketQueue";

type HookReturn = ReturnType<typeof useAdminSupportTickets>;

function row(
  overrides: Partial<AdminSupportTicketQueueRow> = {},
): AdminSupportTicketQueueRow {
  return {
    publicId: "00000000-0000-0000-0000-0000000000a1",
    subject: "Cannot bid on parcel",
    category: "BIDDING",
    status: "OPEN",
    submitterPublicId: "00000000-0000-0000-0000-0000000000b1",
    submitterDisplayName: "Alice Resident",
    assignedAdminPublicId: null,
    assignedAdminDisplayName: null,
    lastMessageAuthor: "USER",
    lastMessageAt: new Date(Date.now() - 60_000).toISOString(),
    ...overrides,
  };
}

function page(
  content: AdminSupportTicketQueueRow[],
  overrides: Partial<Page<AdminSupportTicketQueueRow>> = {},
): Page<AdminSupportTicketQueueRow> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 25,
    ...overrides,
  };
}

function setHook(
  value: Partial<HookReturn> & { data?: Page<AdminSupportTicketQueueRow> },
) {
  vi.mocked(useAdminSupportTickets).mockReturnValue({
    isLoading: false,
    isError: false,
    ...value,
  } as unknown as HookReturn);
}

describe("<AdminSupportTicketQueue />", () => {
  beforeEach(() => {
    mockReplace.mockReset();
    vi.clearAllMocks();
  });

  it("renders the empty state when the page has no rows", () => {
    setHook({ data: page([]) });
    renderWithProviders(<AdminSupportTicketQueue />);
    expect(screen.getByTestId("support-tickets-empty")).toBeInTheDocument();
    expect(
      screen.queryByTestId("support-tickets-table"),
    ).not.toBeInTheDocument();
  });

  it("renders rows with subject, submitter, category, status", () => {
    setHook({
      data: page([
        row({
          publicId: "00000000-0000-0000-0000-0000000000a1",
          subject: "Cannot bid",
          submitterDisplayName: "Alice Resident",
          category: "BIDDING",
          status: "OPEN",
          lastMessageAuthor: "USER",
        }),
        row({
          publicId: "00000000-0000-0000-0000-0000000000a2",
          subject: "Wallet balance wrong",
          submitterDisplayName: "Bob Resident",
          category: "WALLET",
          status: "RESOLVED",
          lastMessageAuthor: "ADMIN",
          assignedAdminDisplayName: "Carol Admin",
        }),
      ]),
    });

    renderWithProviders(<AdminSupportTicketQueue />);

    expect(screen.getByTestId("support-tickets-table")).toBeInTheDocument();
    const subjectLink = screen.getByTestId(
      "support-ticket-subject-00000000-0000-0000-0000-0000000000a1",
    );
    expect(subjectLink).toHaveAttribute(
      "href",
      "/admin/support/00000000-0000-0000-0000-0000000000a1",
    );
    expect(screen.getByText("Alice Resident")).toBeInTheDocument();
    expect(screen.getByText("Bob Resident")).toBeInTheDocument();
    expect(screen.getByText("Carol Admin")).toBeInTheDocument();
    // Status pills + "needs admin reply" hint on the open USER row.
    expect(screen.getByText("needs admin reply")).toBeInTheDocument();
    const table = screen.getByTestId("support-tickets-table");
    expect(table).toHaveTextContent("Open");
    expect(table).toHaveTextContent("Resolved");
  });

  it("status select updates the URL", async () => {
    setHook({ data: page([]) });
    renderWithProviders(<AdminSupportTicketQueue />);
    const select = screen.getByTestId("status-select");
    await userEvent.selectOptions(select, "OPEN");
    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining("status=OPEN"),
      expect.anything(),
    );
  });

  it("last-author filter 'Needs admin reply' updates the URL", async () => {
    setHook({ data: page([]) });
    renderWithProviders(<AdminSupportTicketQueue />);
    const select = screen.getByTestId("last-author-select");
    await userEvent.selectOptions(select, "USER");
    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining("last_author=USER"),
      expect.anything(),
    );
  });

  it("search input commits on Enter", async () => {
    setHook({ data: page([]) });
    renderWithProviders(<AdminSupportTicketQueue />);
    const input = screen.getByTestId("support-search-input");
    await userEvent.type(input, "bid{Enter}");
    await waitFor(() =>
      expect(mockReplace).toHaveBeenCalledWith(
        expect.stringContaining("q=bid"),
        expect.anything(),
      ),
    );
  });

  it("renders pagination when totalPages > 1", () => {
    setHook({
      data: page([row()], {
        totalPages: 3,
        totalElements: 75,
        number: 0,
      }),
    });
    renderWithProviders(<AdminSupportTicketQueue />);
    expect(
      screen.getByRole("navigation", { name: /pagination/i }),
    ).toBeInTheDocument();
  });

  it("renders a loading skeleton", () => {
    setHook({ isLoading: true, data: undefined });
    const { container } = renderWithProviders(<AdminSupportTicketQueue />);
    expect(
      container.querySelector('[aria-busy="true"]'),
    ).toBeInTheDocument();
  });

  it("renders an error state", () => {
    setHook({ isError: true, data: undefined });
    renderWithProviders(<AdminSupportTicketQueue />);
    expect(
      screen.getByText(/Could not load tickets/i),
    ).toBeInTheDocument();
  });
});
