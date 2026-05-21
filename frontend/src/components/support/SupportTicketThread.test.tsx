import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import type {
  ReplySupportTicketRequest,
  SupportTicketAttachmentDto,
  SupportTicketDto,
  SupportTicketMessageDto,
  SupportTicketStatus,
} from "@/types/support";

// next/navigation isn't directly read by the thread, but the dropzone's
// transitive hook deps touch it. Mirror the other support tests' stubs.
vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/support/abc"),
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

// ─── Mocks ──────────────────────────────────────────────────────────────

const ticketHookState: {
  data: SupportTicketDto | undefined;
  isLoading: boolean;
  isError: boolean;
} = {
  data: undefined,
  isLoading: false,
  isError: false,
};

vi.mock("@/hooks/useMySupportTicket", () => ({
  useMySupportTicket: () => ({
    data: ticketHookState.data,
    isLoading: ticketHookState.isLoading,
    isError: ticketHookState.isError,
  }),
  mySupportTicketKey: (p: string) => ["me-support-ticket", p] as const,
}));

// Capture mutate calls + provide a controllable onSuccess so we can assert
// the "composer clears on success" behaviour.
type MutationCall = {
  req: ReplySupportTicketRequest;
  options?: { onSuccess?: () => void };
};
const replyMutateCalls: MutationCall[] = [];
let replyMutationState: {
  isPending: boolean;
  isError: boolean;
  error: unknown;
} = { isPending: false, isError: false, error: null };

const replyMutateMock = vi.fn(
  (req: ReplySupportTicketRequest, options?: { onSuccess?: () => void }) => {
    replyMutateCalls.push({ req, options });
  },
);

vi.mock("@/hooks/useReplySupportTicket", () => ({
  useReplySupportTicket: () => ({
    mutate: replyMutateMock,
    isPending: replyMutationState.isPending,
    isError: replyMutationState.isError,
    error: replyMutationState.error,
  }),
}));

// Signed-URL hook: keep it predictable so the lightbox always renders an
// <img> with a known src on open.
let signedUrlHookState: {
  data: { url: string } | undefined;
  isPending: boolean;
  isError: boolean;
} = {
  data: { url: "https://signed.example/att.png" },
  isPending: false,
  isError: false,
};

vi.mock("@/hooks/useSignedAttachmentUrl", () => ({
  useSignedAttachmentUrl: () => signedUrlHookState,
  signedAttachmentUrlKey: (p: string) =>
    ["support-attachment-signed-url", p] as const,
}));

// Stub the dropzone so the composer test doesn't depend on file-upload
// plumbing. The mock exposes a "stage attachment" button so reply tests
// that need a non-empty attachmentKeys array can drive the prop callback.
vi.mock(
  "@/components/support/SupportAttachmentDropzone",
  () => ({
    SupportAttachmentDropzone: ({
      attachmentKeys,
      onAttachmentKeyAdded,
    }: {
      attachmentKeys: string[];
      onAttachmentKeyAdded: (k: string) => void;
      onAttachmentKeyRemoved: (k: string) => void;
      disabled?: boolean;
    }) => (
      <div data-testid="mock-dropzone">
        <span data-testid="mock-dropzone-count">{attachmentKeys.length}</span>
        <button
          type="button"
          data-testid="mock-dropzone-stage"
          onClick={() => onAttachmentKeyAdded("stub-key-1")}
        >
          stage
        </button>
      </div>
    ),
  }),
);

// Import AFTER mocks so the component picks up the stubbed modules.
import { SupportTicketThread } from "./SupportTicketThread";

// ─── Fixtures ──────────────────────────────────────────────────────────

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

// ─── Tests ─────────────────────────────────────────────────────────────

beforeEach(() => {
  replyMutateCalls.length = 0;
  replyMutateMock.mockClear();
  replyMutationState = { isPending: false, isError: false, error: null };
  signedUrlHookState = {
    data: { url: "https://signed.example/att.png" },
    isPending: false,
    isError: false,
  };
});

describe("<SupportTicketThread />", () => {
  it("renders subject, category, and status", () => {
    setTicket(
      ticket({
        subject: "Bid never went through",
        category: "BIDDING",
        status: "OPEN",
      }),
    );
    renderWithProviders(<SupportTicketThread publicId="t-1" />);
    expect(screen.getByTestId("support-thread-subject")).toHaveTextContent(
      "Bid never went through",
    );
    expect(screen.getByTestId("support-thread-category")).toHaveTextContent(
      "Bidding",
    );
    expect(screen.getByTestId("support-thread-status")).toHaveTextContent(
      "Open",
    );
  });

  it("renders both user-authored and admin-authored bubbles", () => {
    setTicket(
      ticket({
        messages: [
          message({
            publicId: "msg-user-1",
            authorRole: "USER",
            authorDisplayName: "Buyer Bob",
            body: "Hello support.",
          }),
          message({
            publicId: "msg-admin-1",
            authorRole: "ADMIN",
            authorDisplayName: "Admin Sara",
            body: "Hi Bob, looking into it.",
          }),
        ],
      }),
    );
    renderWithProviders(<SupportTicketThread publicId="t-1" />);
    const userBubble = screen.getByTestId("support-message-msg-user-1");
    const adminBubble = screen.getByTestId("support-message-msg-admin-1");
    expect(userBubble).toBeInTheDocument();
    expect(adminBubble).toBeInTheDocument();
    expect(userBubble).toHaveAttribute("data-role", "USER");
    expect(adminBubble).toHaveAttribute("data-role", "ADMIN");
    // Both bodies are visible to the user.
    expect(userBubble).toHaveTextContent("Hello support.");
    expect(adminBubble).toHaveTextContent("Hi Bob, looking into it.");
  });

  it("shows the 'replying will reopen this ticket' helper when RESOLVED", () => {
    setTicket(
      ticket({
        status: "RESOLVED",
        resolvedAt: new Date().toISOString(),
      }),
    );
    renderWithProviders(<SupportTicketThread publicId="t-1" />);
    const note = screen.getByTestId("support-thread-reopen-note");
    expect(note).toBeInTheDocument();
    expect(note).toHaveTextContent(/replying will reopen this ticket/i);
    // Composer remains functional — the textarea + submit are not disabled
    // beyond the normal "submitting" pending lock.
    expect(
      screen.getByTestId("support-thread-reply-submit"),
    ).not.toBeDisabled();
  });

  it("does NOT show the reopen helper when OPEN", () => {
    setTicket(ticket({ status: "OPEN" }));
    renderWithProviders(<SupportTicketThread publicId="t-1" />);
    expect(
      screen.queryByTestId("support-thread-reopen-note"),
    ).not.toBeInTheDocument();
  });

  it("defensively filters out internal-note messages (visibleToUser=false)", () => {
    // The backend mapper already strips these from the /me/ DTO, but if a
    // regression ever leaks one through the wire shape, the thread must
    // still not render it. Drives the defense-in-depth filter.
    setTicket(
      ticket({
        messages: [
          message({
            publicId: "msg-public",
            body: "Visible to user.",
            visibleToUser: true,
          }),
          message({
            publicId: "msg-internal",
            authorRole: "ADMIN",
            body: "Internal admin note about this ticket.",
            visibleToUser: false,
          }),
        ],
      }),
    );
    renderWithProviders(<SupportTicketThread publicId="t-1" />);
    expect(screen.getByTestId("support-message-msg-public")).toBeInTheDocument();
    expect(
      screen.queryByTestId("support-message-msg-internal"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(/internal admin note about this ticket/i),
    ).not.toBeInTheDocument();
  });

  it("reply composer submit calls the mutation with { body, attachmentKeys }", async () => {
    setTicket(ticket({ status: "OPEN" }));
    const user = userEvent.setup();
    renderWithProviders(<SupportTicketThread publicId="t-1" />);

    // Type a body.
    await user.type(
      screen.getByTestId("support-thread-reply-textarea"),
      "Following up on this.",
    );
    // Stage one attachment via the stub dropzone's "stage" button.
    await user.click(screen.getByTestId("mock-dropzone-stage"));

    await user.click(screen.getByTestId("support-thread-reply-submit"));

    await waitFor(() => expect(replyMutateMock).toHaveBeenCalledTimes(1));
    const call = replyMutateCalls[0];
    expect(call.req.body).toBe("Following up on this.");
    expect(call.req.attachmentKeys).toEqual(["stub-key-1"]);
  });

  it("clears the composer when the reply mutation resolves successfully", async () => {
    setTicket(ticket({ status: "OPEN" }));
    const user = userEvent.setup();
    renderWithProviders(<SupportTicketThread publicId="t-1" />);

    await user.type(
      screen.getByTestId("support-thread-reply-textarea"),
      "Another reply.",
    );
    await user.click(screen.getByTestId("mock-dropzone-stage"));
    await user.click(screen.getByTestId("support-thread-reply-submit"));

    await waitFor(() => expect(replyMutateMock).toHaveBeenCalledTimes(1));
    // Manually invoke the onSuccess callback the component passed — the
    // mock mutate doesn't wire to a real query so we drive the success
    // branch ourselves.
    replyMutateCalls[0].options?.onSuccess?.();

    await waitFor(() => {
      expect(
        (
          screen.getByTestId(
            "support-thread-reply-textarea",
          ) as HTMLTextAreaElement
        ).value,
      ).toBe("");
    });
    // Staged attachments cleared too.
    expect(screen.getByTestId("mock-dropzone-count")).toHaveTextContent("0");
  });

  it("clicking an attachment thumbnail opens the lightbox", async () => {
    setTicket(
      ticket({
        messages: [
          message({
            publicId: "msg-with-att",
            attachments: [
              attachment({ publicId: "att-aaaa-bbbb-cccc-dddd-000000000001" }),
            ],
          }),
        ],
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<SupportTicketThread publicId="t-1" />);

    // Lightbox is not in the DOM before click.
    expect(
      screen.queryByTestId("support-attachment-lightbox"),
    ).not.toBeInTheDocument();

    await user.click(
      screen.getByTestId(
        "support-attachment-att-aaaa-bbbb-cccc-dddd-000000000001",
      ),
    );

    expect(
      screen.getByTestId("support-attachment-lightbox"),
    ).toBeInTheDocument();
    const img = screen.getByTestId("support-attachment-lightbox-image");
    expect(img).toHaveAttribute("src", "https://signed.example/att.png");
  });
});
