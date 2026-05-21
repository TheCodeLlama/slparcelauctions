import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import type {
  AdminReplyRequest,
  SupportTicketAttachmentDto,
  SupportTicketCategory,
  SupportTicketDto,
  SupportTicketMessageDto,
  SupportTicketStatus,
} from "@/types/support";

// ─── next/navigation ─────────────────────────────────────────────────────
// Sub-components (Modal, dropzone) may transitively read the router; mirror
// the user-thread test's mock so the component renders cleanly.
vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/support/t-1"),
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: vi.fn(() => new URLSearchParams()),
}));

// ─── Auth ────────────────────────────────────────────────────────────────
const CALLER_ADMIN_ID = "admin-0000-0000-0000-000000000001";

vi.mock("@/lib/auth/hooks", () => ({
  useAuth: () => ({
    status: "authenticated" as const,
    user: {
      publicId: CALLER_ADMIN_ID,
      username: "admin1",
      email: "admin1@example.com",
      displayName: "Admin One",
      slAvatarUuid: null,
      verified: true,
      role: "ADMIN" as const,
    },
  }),
}));

// ─── Ticket query hook ───────────────────────────────────────────────────
const ticketHookState: {
  data: SupportTicketDto | undefined;
  isLoading: boolean;
  isError: boolean;
} = {
  data: undefined,
  isLoading: false,
  isError: false,
};

vi.mock("@/hooks/admin/useAdminSupportTicket", () => ({
  useAdminSupportTicket: () => ({
    data: ticketHookState.data,
    isLoading: ticketHookState.isLoading,
    isError: ticketHookState.isError,
  }),
  adminSupportTicketKey: (p: string) => ["admin-support-ticket", p] as const,
}));

// ─── Mutation hooks ──────────────────────────────────────────────────────
type ReplyCall = {
  req: AdminReplyRequest;
  options?: { onSuccess?: () => void };
};
const replyMutateCalls: ReplyCall[] = [];
const replyMutateMock = vi.fn(
  (req: AdminReplyRequest, options?: { onSuccess?: () => void }) => {
    replyMutateCalls.push({ req, options });
  },
);
let replyMutationState: {
  isPending: boolean;
  isError: boolean;
  error: unknown;
} = { isPending: false, isError: false, error: null };

vi.mock("@/hooks/admin/useAdminSupportReply", () => ({
  useAdminSupportReply: () => ({
    mutate: replyMutateMock,
    isPending: replyMutationState.isPending,
    isError: replyMutationState.isError,
    error: replyMutationState.error,
  }),
}));

const resolveMutateMock = vi.fn();
vi.mock("@/hooks/admin/useAdminSupportResolve", () => ({
  useAdminSupportResolve: () => ({
    mutate: resolveMutateMock,
    isPending: false,
  }),
}));

const reopenMutateMock = vi.fn();
vi.mock("@/hooks/admin/useAdminSupportReopen", () => ({
  useAdminSupportReopen: () => ({
    mutate: reopenMutateMock,
    isPending: false,
  }),
}));

const assignMutateMock = vi.fn();
vi.mock("@/hooks/admin/useAdminSupportAssign", () => ({
  useAdminSupportAssign: () => ({
    mutate: assignMutateMock,
    isPending: false,
  }),
}));

const patchCategoryMutateMock = vi.fn();
vi.mock("@/hooks/admin/useAdminSupportPatchCategory", () => ({
  useAdminSupportPatchCategory: () => ({
    mutate: patchCategoryMutateMock,
    isPending: false,
  }),
}));

// ─── Signed attachment URL ───────────────────────────────────────────────
vi.mock("@/hooks/useSignedAttachmentUrl", () => ({
  useSignedAttachmentUrl: () => ({
    data: { url: "https://signed.example/att.png" },
    isPending: false,
    isError: false,
  }),
  signedAttachmentUrlKey: (p: string) =>
    ["support-attachment-signed-url", p] as const,
}));

// ─── Dropzone stub ───────────────────────────────────────────────────────
vi.mock("@/components/support/SupportAttachmentDropzone", () => ({
  SupportAttachmentDropzone: ({
    attachmentKeys,
    onAttachmentKeyAdded,
    maxAttachments,
  }: {
    attachmentKeys: string[];
    onAttachmentKeyAdded: (k: string) => void;
    onAttachmentKeyRemoved: (k: string) => void;
    maxAttachments?: number;
    disabled?: boolean;
  }) => (
    <div data-testid="mock-dropzone">
      <span data-testid="mock-dropzone-count">{attachmentKeys.length}</span>
      <span data-testid="mock-dropzone-max">{maxAttachments ?? "default"}</span>
      <button
        type="button"
        data-testid="mock-dropzone-stage"
        onClick={() => onAttachmentKeyAdded("stub-key-1")}
      >
        stage
      </button>
    </div>
  ),
}));

// Import AFTER mocks so the component picks up the stubbed modules.
import { AdminSupportTicketDetail } from "./AdminSupportTicketDetail";

// ─── Fixtures ────────────────────────────────────────────────────────────
function attachment(
  overrides: Partial<SupportTicketAttachmentDto> = {},
): SupportTicketAttachmentDto {
  return {
    publicId: "att-0000-0000-0000-000000000001",
    mimeType: "image/png",
    sizeBytes: 1234,
    width: 800,
    height: 600,
    ...overrides,
  };
}

function message(
  overrides: Partial<SupportTicketMessageDto> = {},
): SupportTicketMessageDto {
  return {
    publicId: "msg-0000-0000-0000-000000000001",
    authorPublicId: "user-0001",
    authorDisplayName: "Sample User",
    authorRole: "USER",
    body: "Initial message body.",
    visibleToUser: true,
    createdAt: new Date(Date.now() - 60_000).toISOString(),
    attachments: [],
    ...overrides,
  };
}

function ticket(
  overrides: Partial<SupportTicketDto> = {},
): SupportTicketDto {
  const now = new Date().toISOString();
  return {
    publicId: "ticket-0000-0000-0000-000000000001",
    submitterPublicId: "user-0001",
    submitterDisplayName: "Sample User",
    subject: "Wallet deposit stuck",
    category: "WALLET",
    status: "OPEN" as SupportTicketStatus,
    assignedAdminPublicId: null,
    assignedAdminDisplayName: null,
    lastMessageAt: now,
    lastMessageAuthor: "USER",
    resolvedAt: null,
    createdAt: now,
    updatedAt: now,
    messages: [],
    ...overrides,
  };
}

function setTicket(t: SupportTicketDto | undefined) {
  ticketHookState.data = t;
  ticketHookState.isLoading = false;
  ticketHookState.isError = false;
}

// ─── Tests ───────────────────────────────────────────────────────────────
beforeEach(() => {
  replyMutateCalls.length = 0;
  replyMutateMock.mockClear();
  resolveMutateMock.mockClear();
  reopenMutateMock.mockClear();
  assignMutateMock.mockClear();
  patchCategoryMutateMock.mockClear();
  replyMutationState = { isPending: false, isError: false, error: null };
});

describe("<AdminSupportTicketDetail />", () => {
  it("renders subject, category dropdown, and status pill", () => {
    setTicket(
      ticket({
        subject: "Bid never went through",
        category: "BIDDING",
        status: "OPEN",
      }),
    );
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);

    expect(
      screen.getByTestId("admin-support-detail-subject"),
    ).toHaveTextContent("Bid never went through");

    const categorySelect = screen.getByTestId(
      "admin-support-detail-category-select",
    ) as HTMLSelectElement;
    expect(categorySelect.value).toBe("BIDDING");

    expect(
      screen.getByTestId("admin-support-detail-status-pill"),
    ).toHaveTextContent("Open");
  });

  it("renders user, admin public, and admin internal-note bubbles with the right styling", () => {
    setTicket(
      ticket({
        assignedAdminPublicId: CALLER_ADMIN_ID,
        assignedAdminDisplayName: "Admin One",
        messages: [
          message({
            publicId: "msg-user-1",
            authorRole: "USER",
            authorDisplayName: "Buyer Bob",
            body: "User message body.",
            visibleToUser: true,
          }),
          message({
            publicId: "msg-admin-public-1",
            authorRole: "ADMIN",
            authorDisplayName: "Admin Sara",
            body: "Public admin reply.",
            visibleToUser: true,
          }),
          message({
            publicId: "msg-admin-internal-1",
            authorRole: "ADMIN",
            authorDisplayName: "Admin Sara",
            body: "Internal admin note body.",
            visibleToUser: false,
          }),
        ],
      }),
    );
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);

    const userBubble = screen.getByTestId("support-message-msg-user-1");
    const publicBubble = screen.getByTestId(
      "support-message-msg-admin-public-1",
    );
    const internalBubble = screen.getByTestId(
      "support-message-msg-admin-internal-1",
    );

    expect(userBubble).toHaveAttribute("data-role", "USER");
    expect(userBubble).toHaveAttribute("data-internal", "false");

    expect(publicBubble).toHaveAttribute("data-role", "ADMIN");
    expect(publicBubble).toHaveAttribute("data-internal", "false");

    expect(internalBubble).toHaveAttribute("data-role", "ADMIN");
    expect(internalBubble).toHaveAttribute("data-internal", "true");

    // Internal-note label is the literal text "Internal note" (no emoji).
    expect(
      screen.getByTestId("support-message-role-msg-admin-internal-1"),
    ).toHaveTextContent(/Internal note/);

    // Lucide EyeOff icon is rendered alongside the label.
    expect(
      screen.getByTestId("support-message-internal-icon-msg-admin-internal-1"),
    ).toBeInTheDocument();

    // Internal-note bubble has the warning palette class.
    const internalBody = screen.getByTestId(
      "support-message-body-msg-admin-internal-1",
    );
    expect(internalBody.className).toMatch(/border-warning/);
    expect(internalBody.className).toMatch(/bg-warning-bg/);
  });

  it("shows the Resolve button when status=OPEN", () => {
    setTicket(ticket({ status: "OPEN" }));
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);
    expect(
      screen.getByTestId("admin-support-detail-resolve"),
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId("admin-support-detail-reopen"),
    ).not.toBeInTheDocument();
  });

  it("shows the Reopen button when status=RESOLVED", () => {
    setTicket(
      ticket({
        status: "RESOLVED",
        resolvedAt: new Date().toISOString(),
      }),
    );
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);
    expect(
      screen.getByTestId("admin-support-detail-reopen"),
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId("admin-support-detail-resolve"),
    ).not.toBeInTheDocument();
  });

  it("clicking Resolve calls the resolve mutation", async () => {
    setTicket(ticket({ status: "OPEN" }));
    const user = userEvent.setup();
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);
    await user.click(screen.getByTestId("admin-support-detail-resolve"));
    await waitFor(() => expect(resolveMutateMock).toHaveBeenCalledTimes(1));
  });

  it("clicking Reopen calls the reopen mutation", async () => {
    setTicket(
      ticket({
        status: "RESOLVED",
        resolvedAt: new Date().toISOString(),
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);
    await user.click(screen.getByTestId("admin-support-detail-reopen"));
    await waitFor(() => expect(reopenMutateMock).toHaveBeenCalledTimes(1));
  });

  it("'Assign to me' is visible when ticket is unassigned and calls assign with caller's publicId", async () => {
    setTicket(
      ticket({
        assignedAdminPublicId: null,
        assignedAdminDisplayName: null,
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);
    await user.click(screen.getByTestId("admin-support-detail-assign-me"));
    await waitFor(() =>
      expect(assignMutateMock).toHaveBeenCalledWith(CALLER_ADMIN_ID),
    );
  });

  it("'Unassign' is visible when ticket is assigned and calls assign with null", async () => {
    setTicket(
      ticket({
        assignedAdminPublicId: "some-other-admin",
        assignedAdminDisplayName: "Other Admin",
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);
    await user.click(screen.getByTestId("admin-support-detail-unassign"));
    await waitFor(() => expect(assignMutateMock).toHaveBeenCalledWith(null));
  });

  it("'Assign to me' is hidden when the ticket is already assigned to the caller", () => {
    setTicket(
      ticket({
        assignedAdminPublicId: CALLER_ADMIN_ID,
        assignedAdminDisplayName: "Admin One",
      }),
    );
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);
    expect(
      screen.queryByTestId("admin-support-detail-assign-me"),
    ).not.toBeInTheDocument();
    expect(
      screen.getByTestId("admin-support-detail-unassign"),
    ).toBeInTheDocument();
  });

  it("changing the category select calls the patch-category mutation", async () => {
    setTicket(ticket({ category: "WALLET" }));
    const user = userEvent.setup();
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);
    await user.selectOptions(
      screen.getByTestId("admin-support-detail-category-select"),
      "BIDDING",
    );
    await waitFor(() =>
      expect(patchCategoryMutateMock).toHaveBeenCalledWith(
        "BIDDING" satisfies SupportTicketCategory,
      ),
    );
  });

  it("reply submit (without internal note) calls reply with internalNote=false", async () => {
    setTicket(ticket({ status: "OPEN" }));
    const user = userEvent.setup();
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);

    await user.type(
      screen.getByTestId("admin-support-detail-reply-textarea"),
      "Looking into this.",
    );
    await user.click(screen.getByTestId("mock-dropzone-stage"));
    await user.click(screen.getByTestId("admin-support-detail-reply-submit"));

    await waitFor(() => expect(replyMutateMock).toHaveBeenCalledTimes(1));
    const call = replyMutateCalls[0];
    expect(call.req.body).toBe("Looking into this.");
    expect(call.req.attachmentKeys).toEqual(["stub-key-1"]);
    expect(call.req.internalNote).toBe(false);
  });

  it("reply submit (with internal-note checked) calls reply with internalNote=true and composer wrapper styling switches", async () => {
    setTicket(ticket({ status: "OPEN" }));
    const user = userEvent.setup();
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);

    const wrapper = screen.getByTestId("admin-support-detail-composer-wrapper");
    // Before toggling: not internal.
    expect(wrapper).toHaveAttribute("data-internal", "false");
    expect(wrapper.className).not.toMatch(/border-warning/);

    await user.click(
      screen.getByTestId("admin-support-detail-internal-note-checkbox"),
    );

    // After toggling: wrapper flips its styling.
    expect(wrapper).toHaveAttribute("data-internal", "true");
    expect(wrapper.className).toMatch(/border-warning/);
    expect(wrapper.className).toMatch(/bg-warning-bg/);

    await user.type(
      screen.getByTestId("admin-support-detail-reply-textarea"),
      "Quick note for the team.",
    );
    await user.click(screen.getByTestId("admin-support-detail-reply-submit"));

    await waitFor(() => expect(replyMutateMock).toHaveBeenCalledTimes(1));
    const call = replyMutateCalls[0];
    expect(call.req.body).toBe("Quick note for the team.");
    expect(call.req.internalNote).toBe(true);
  });

  it("dropzone receives maxAttachments=3", () => {
    setTicket(ticket({ status: "OPEN" }));
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);
    expect(screen.getByTestId("mock-dropzone-max")).toHaveTextContent("3");
  });

  it("clicking an attachment thumbnail opens the lightbox", async () => {
    setTicket(
      ticket({
        messages: [
          message({
            publicId: "msg-with-att",
            attachments: [
              attachment({ publicId: "att-lightbox-1" }),
            ],
          }),
        ],
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AdminSupportTicketDetail publicId="t-1" />);

    expect(
      screen.queryByTestId("support-attachment-lightbox"),
    ).not.toBeInTheDocument();

    await user.click(screen.getByTestId("support-attachment-att-lightbox-1"));

    expect(
      screen.getByTestId("support-attachment-lightbox"),
    ).toBeInTheDocument();
    const img = screen.getByTestId("support-attachment-lightbox-image");
    expect(img).toHaveAttribute("src", "https://signed.example/att.png");
  });
});
