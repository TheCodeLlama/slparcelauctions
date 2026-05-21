import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { ApiError, type ProblemDetail } from "@/lib/api";
import type { CreateSupportTicketRequest } from "@/types/support";

// next/navigation is touched by the create-ticket mutation hook (for the
// success-path redirect). The component itself doesn't read the router,
// but the hook initialisation does, so we stub it the same way the rest
// of the support feature does.
vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/support/new"),
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

// Mock the create mutation. Tests drive the body shape via mutateMock and
// can flip the surface into error/pending states via `mutationState`.
const mutateMock = vi.fn();
let mutationState: {
  isPending: boolean;
  isError: boolean;
  error: unknown;
} = { isPending: false, isError: false, error: null };

vi.mock("@/hooks/useCreateSupportTicket", () => ({
  useCreateSupportTicket: () => ({
    mutate: mutateMock,
    isPending: mutationState.isPending,
    isError: mutationState.isError,
    error: mutationState.error,
  }),
}));

// Mock the upload mutation that the dropzone transitively uses.
const uploadMutateAsyncMock = vi.fn();
vi.mock("@/hooks/useUploadSupportAttachment", () => ({
  useUploadSupportAttachment: () => ({
    mutateAsync: uploadMutateAsyncMock,
    isPending: false,
  }),
}));

import { NewSupportTicketForm } from "./NewSupportTicketForm";

beforeEach(() => {
  mutateMock.mockReset();
  uploadMutateAsyncMock.mockReset();
  mutationState = { isPending: false, isError: false, error: null };

  // The dropzone builds object URLs for previews; jsdom leaves both
  // undefined. Stub them so attachment tests don't throw.
  let n = 0;
  URL.createObjectURL = vi.fn(() => `blob:mock-${n++}`);
  URL.revokeObjectURL = vi.fn();
});

describe("<NewSupportTicketForm />", () => {
  it("blocks submit and surfaces inline errors when fields are empty", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NewSupportTicketForm />);

    await user.click(screen.getByTestId("ticket-submit-btn"));

    // Subject + body errors render under the Input/Textarea via the
    // primitives' built-in error slot, so we assert by text rather than
    // testid. The category-error testid is bespoke.
    expect(screen.getByText(/subject is required/i)).toBeInTheDocument();
    expect(screen.getByTestId("ticket-category-error")).toHaveTextContent(
      /pick a category/i,
    );
    expect(screen.getByText(/message is required/i)).toBeInTheDocument();
    expect(mutateMock).not.toHaveBeenCalled();
  });

  it("submits with a correctly shaped request body", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NewSupportTicketForm />);

    await user.type(
      screen.getByTestId("ticket-subject-input"),
      "Wallet deposit stuck",
    );
    await user.selectOptions(
      screen.getByTestId("ticket-category-select"),
      "WALLET",
    );
    await user.type(
      screen.getByTestId("ticket-body-textarea"),
      "Deposited L$500 two hours ago and the wallet balance hasn't moved.",
    );

    await user.click(screen.getByTestId("ticket-submit-btn"));

    await waitFor(() => expect(mutateMock).toHaveBeenCalledTimes(1));
    const body = mutateMock.mock.calls[0][0] as CreateSupportTicketRequest;
    expect(body.subject).toBe("Wallet deposit stuck");
    expect(body.category).toBe("WALLET");
    expect(body.body).toBe(
      "Deposited L$500 two hours ago and the wallet balance hasn't moved.",
    );
    // No attachments staged in this test, so the field is omitted.
    expect(body.attachmentKeys).toBeUndefined();
  });

  it("character counter goes red past the 10000-char cap", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NewSupportTicketForm />);

    const textarea = screen.getByTestId(
      "ticket-body-textarea",
    ) as HTMLTextAreaElement;
    // userEvent.type with a 10k+ string is too slow; assign via fireEvent.
    const overCap = "x".repeat(10001);
    // user.clear + type would re-render per char; use a direct change event.
    // We import fireEvent indirectly via @testing-library/react re-export
    // through @/test/render, but to keep this file self-contained we just
    // call a userEvent.paste which sets the whole value at once.
    textarea.focus();
    await user.paste(overCap);

    const counter = screen.getByTestId("ticket-body-counter");
    expect(counter).toHaveTextContent("10001 / 10000");
    // Tailwind class swap: when over cap, the counter uses text-danger.
    expect(counter.className).toContain("text-danger");
  });

  it("includes staged attachment keys in the submitted request", async () => {
    uploadMutateAsyncMock.mockImplementation(async (file: File) => ({
      attachmentKey: `att-${file.name}`,
    }));

    const user = userEvent.setup();
    renderWithProviders(<NewSupportTicketForm />);

    // Fill required fields.
    await user.type(screen.getByTestId("ticket-subject-input"), "Need help");
    await user.selectOptions(
      screen.getByTestId("ticket-category-select"),
      "OTHER",
    );
    await user.type(
      screen.getByTestId("ticket-body-textarea"),
      "Some details.",
    );

    // Stage an attachment via the dropzone's hidden input.
    const input = screen.getByTestId(
      "support-attachment-input",
    ) as HTMLInputElement;
    const file = new File(["x"], "screenshot.png", { type: "image/png" });
    await user.upload(input, file);

    await screen.findByTestId(
      "support-attachment-thumb-att-screenshot.png",
    );

    await user.click(screen.getByTestId("ticket-submit-btn"));

    await waitFor(() => expect(mutateMock).toHaveBeenCalledTimes(1));
    const body = mutateMock.mock.calls[0][0] as CreateSupportTicketRequest;
    expect(body.attachmentKeys).toEqual(["att-screenshot.png"]);
  });

  it("renders the RATE_LIMITED server-error message", () => {
    const problem: ProblemDetail = {
      status: 429,
      title: "Too many tickets",
      code: "RATE_LIMITED",
    };
    mutationState = {
      isPending: false,
      isError: true,
      error: new ApiError(problem),
    };

    renderWithProviders(<NewSupportTicketForm />);
    expect(screen.getByTestId("new-ticket-form-error")).toHaveTextContent(
      /sending too many tickets/i,
    );
  });

  it("disables the submit button while the mutation is pending", () => {
    mutationState = { isPending: true, isError: false, error: null };
    renderWithProviders(<NewSupportTicketForm />);
    expect(screen.getByTestId("ticket-submit-btn")).toBeDisabled();
  });
});
