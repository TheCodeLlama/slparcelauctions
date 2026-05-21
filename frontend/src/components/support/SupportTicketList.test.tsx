import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import type { SupportTicketSummaryDto } from "@/types/support";
import type { Page } from "@/types/page";

const mockReplace = vi.fn();

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/support"),
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

vi.mock("@/hooks/useMySupportTickets", () => ({
  useMySupportTickets: vi.fn(),
  mySupportTicketsKey: (p: unknown) => ["me-support-tickets", p] as const,
}));

import { useMySupportTickets } from "@/hooks/useMySupportTickets";
import { SupportTicketList } from "./SupportTicketList";

type HookReturn = ReturnType<typeof useMySupportTickets>;

function summary(
  overrides: Partial<SupportTicketSummaryDto> = {},
): SupportTicketSummaryDto {
  return {
    publicId: "00000000-0000-0000-0000-0000000000a1",
    subject: "Wallet deposit stuck",
    category: "WALLET",
    status: "OPEN",
    lastMessageAuthor: "USER",
    lastMessageAt: new Date().toISOString(),
    ...overrides,
  };
}

function page(
  content: SupportTicketSummaryDto[],
  overrides: Partial<Page<SupportTicketSummaryDto>> = {},
): Page<SupportTicketSummaryDto> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 20,
    ...overrides,
  };
}

function setHook(
  value: Partial<HookReturn> & { data?: Page<SupportTicketSummaryDto> },
) {
  vi.mocked(useMySupportTickets).mockReturnValue({
    isLoading: false,
    isError: false,
    ...value,
  } as unknown as HookReturn);
}

describe("<SupportTicketList />", () => {
  beforeEach(() => {
    mockReplace.mockReset();
    vi.clearAllMocks();
  });

  it("renders empty state when no tickets", () => {
    setHook({ data: page([]) });
    renderWithProviders(<SupportTicketList />);
    expect(screen.getByTestId("support-tickets-empty")).toBeInTheDocument();
    expect(screen.getByText(/no tickets yet/i)).toBeInTheDocument();
    // Empty state ships its own "New ticket" CTA so a brand-new user has the
    // affordance front-and-center, not just in the header.
    expect(screen.getByTestId("empty-new-ticket-btn")).toBeInTheDocument();
    expect(
      screen.queryByTestId("support-tickets-table"),
    ).not.toBeInTheDocument();
  });

  it("renders ticket rows with subject link, category, and status pill", () => {
    setHook({
      data: page([
        summary({
          publicId: "00000000-0000-0000-0000-0000000000a1",
          subject: "My bid never went through",
          category: "BIDDING",
          status: "OPEN",
          lastMessageAuthor: "ADMIN",
        }),
      ]),
    });
    renderWithProviders(<SupportTicketList />);
    expect(screen.getByTestId("support-tickets-table")).toBeInTheDocument();
    expect(
      screen.getByText("My bid never went through"),
    ).toBeInTheDocument();
    // Category label is the title-cased form, not the enum literal.
    expect(screen.getByText("Bidding")).toBeInTheDocument();
    // Admin-replied sub-label only renders for OPEN + lastAuthor=ADMIN.
    expect(screen.getByText(/admin replied/i)).toBeInTheDocument();
  });

  it("subject cell links to the detail page", () => {
    setHook({
      data: page([
        summary({
          publicId: "00000000-0000-0000-0000-0000000000aa",
          subject: "Need help",
        }),
      ]),
    });
    renderWithProviders(<SupportTicketList />);
    const link = screen.getByTestId(
      "support-ticket-subject-00000000-0000-0000-0000-0000000000aa",
    );
    expect(link).toHaveAttribute(
      "href",
      "/support/00000000-0000-0000-0000-0000000000aa",
    );
  });

  it("does NOT render the admin-replied sub-label when the ticket is resolved", () => {
    setHook({
      data: page([
        summary({
          status: "RESOLVED",
          lastMessageAuthor: "ADMIN",
        }),
      ]),
    });
    renderWithProviders(<SupportTicketList />);
    expect(screen.queryByText(/admin replied/i)).not.toBeInTheDocument();
    // Status pill flips to the resolved label so the row still reads
    // correctly without the sub-line. Scope to the table body so the
    // status-filter dropdown's "Resolved" option doesn't collide.
    const table = screen.getByTestId("support-tickets-table");
    expect(table).toHaveTextContent("Resolved");
  });

  it("does NOT render the admin-replied sub-label when last author is the user", () => {
    setHook({
      data: page([
        summary({
          status: "OPEN",
          lastMessageAuthor: "USER",
        }),
      ]),
    });
    renderWithProviders(<SupportTicketList />);
    expect(screen.queryByText(/admin replied/i)).not.toBeInTheDocument();
  });

  it("status select updates the URL with the chosen filter", async () => {
    setHook({ data: page([]) });
    renderWithProviders(<SupportTicketList />);
    const select = screen.getByTestId("status-select");
    await userEvent.selectOptions(select, "OPEN");
    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining("status=OPEN"),
      expect.anything(),
    );
  });

  it("status select clears the param when 'all' is selected", async () => {
    setHook({ data: page([]) });
    renderWithProviders(<SupportTicketList />);
    const select = screen.getByTestId("status-select");
    await userEvent.selectOptions(select, "all");
    // "all" is the no-filter sentinel; it must not leak into the URL.
    expect(mockReplace).toHaveBeenCalledWith("/support", expect.anything());
  });

  it("renders pagination when totalPages > 1", () => {
    setHook({
      data: page([summary()], {
        totalPages: 3,
        totalElements: 60,
        number: 0,
      }),
    });
    renderWithProviders(<SupportTicketList />);
    expect(
      screen.getByRole("navigation", { name: /pagination/i }),
    ).toBeInTheDocument();
  });

  it("hides pagination when totalPages <= 1", () => {
    setHook({ data: page([summary()]) });
    renderWithProviders(<SupportTicketList />);
    expect(
      screen.queryByRole("navigation", { name: /pagination/i }),
    ).not.toBeInTheDocument();
  });

  it("renders loading state while the query is pending", () => {
    setHook({ data: undefined, isLoading: true });
    renderWithProviders(<SupportTicketList />);
    expect(screen.getByText(/loading tickets/i)).toBeInTheDocument();
  });

  it("renders an error state when the query fails", () => {
    setHook({ data: undefined, isError: true });
    renderWithProviders(<SupportTicketList />);
    expect(
      screen.getByText(/could not load tickets/i),
    ).toBeInTheDocument();
  });

  it("header 'New ticket' button links to /support/new", () => {
    setHook({ data: page([]) });
    renderWithProviders(<SupportTicketList />);
    const btn = screen.getByTestId("new-ticket-btn");
    // The Button is wrapped in a Next <Link>, so the closest anchor carries
    // the href.
    expect(btn.closest("a")).toHaveAttribute("href", "/support/new");
  });
});
