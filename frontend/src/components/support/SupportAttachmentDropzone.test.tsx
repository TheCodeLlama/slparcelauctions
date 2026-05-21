import { beforeEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { ApiError, type ProblemDetail } from "@/lib/api";

// Mock the upload mutation hook before importing the component under test.
// Each test can rewire `mutateAsyncMock` for the happy path or to surface
// a thrown ApiError.
const mutateAsyncMock = vi.fn();
let uploadIsPending = false;

vi.mock("@/hooks/useUploadSupportAttachment", () => ({
  useUploadSupportAttachment: () => ({
    mutateAsync: mutateAsyncMock,
    isPending: uploadIsPending,
  }),
}));

import { SupportAttachmentDropzone } from "./SupportAttachmentDropzone";

// jsdom doesn't define URL.createObjectURL — stub it (and revoke) so the
// component can build local previews without throwing.
beforeEach(() => {
  let n = 0;
  URL.createObjectURL = vi.fn(() => `blob:mock-${n++}`);
  URL.revokeObjectURL = vi.fn();
  mutateAsyncMock.mockReset();
  uploadIsPending = false;
});

function Harness({
  initial = [] as string[],
  max,
  disabled = false,
}: {
  initial?: string[];
  max?: number;
  disabled?: boolean;
}) {
  const [keys, setKeys] = useState<string[]>(initial);
  return (
    <SupportAttachmentDropzone
      attachmentKeys={keys}
      onAttachmentKeyAdded={(k) => setKeys((prev) => [...prev, k])}
      onAttachmentKeyRemoved={(k) =>
        setKeys((prev) => prev.filter((x) => x !== k))
      }
      maxAttachments={max}
      disabled={disabled}
    />
  );
}

describe("<SupportAttachmentDropzone />", () => {
  it("renders the dropzone when attachmentKeys is below max", () => {
    renderWithProviders(<Harness />);
    expect(screen.getByTestId("support-attachment-zone")).toBeInTheDocument();
  });

  it("hides the dropzone when at the default max of 3", () => {
    renderWithProviders(
      <Harness initial={["k1", "k2", "k3"]} />,
    );
    expect(
      screen.queryByTestId("support-attachment-zone"),
    ).not.toBeInTheDocument();
  });

  it("uploads a picked file and calls onAttachmentKeyAdded with the returned key", async () => {
    mutateAsyncMock.mockImplementation(async (file: File) => ({
      attachmentKey: `key-${file.name}`,
    }));
    renderWithProviders(<Harness />);

    const input = screen.getByTestId(
      "support-attachment-input",
    ) as HTMLInputElement;
    const file = new File(["x"], "shot.png", { type: "image/png" });
    await userEvent.upload(input, file);

    await waitFor(() => expect(mutateAsyncMock).toHaveBeenCalledTimes(1));
    expect(mutateAsyncMock).toHaveBeenCalledWith(file);

    // The Harness mirrors the added key back into the prop, so the thumb
    // should appear after the mutation resolves.
    await screen.findByTestId("support-attachment-thumb-key-shot.png");
  });

  it("renders a thumbnail for each attachmentKey", () => {
    renderWithProviders(<Harness initial={["alpha", "beta"]} />);
    expect(
      screen.getByTestId("support-attachment-thumb-alpha"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("support-attachment-thumb-beta"),
    ).toBeInTheDocument();
  });

  it("removes a key when its X button is clicked", async () => {
    renderWithProviders(<Harness initial={["alpha", "beta"]} />);

    await userEvent.click(
      screen.getByTestId("support-attachment-remove-alpha"),
    );

    expect(
      screen.queryByTestId("support-attachment-thumb-alpha"),
    ).not.toBeInTheDocument();
    // beta still present
    expect(
      screen.getByTestId("support-attachment-thumb-beta"),
    ).toBeInTheDocument();
  });

  it("surfaces an inline error when upload throws INVALID_ATTACHMENT", async () => {
    const problem: ProblemDetail = {
      status: 400,
      title: "Bad attachment",
      code: "INVALID_ATTACHMENT",
    };
    mutateAsyncMock.mockRejectedValue(new ApiError(problem));

    renderWithProviders(<Harness />);
    const input = screen.getByTestId(
      "support-attachment-input",
    ) as HTMLInputElement;
    const file = new File(["x"], "bad.png", { type: "image/png" });
    await userEvent.upload(input, file);

    const err = await screen.findByTestId("support-attachment-error");
    expect(err).toHaveTextContent(/too large or unsupported format/i);
  });

  it("disables the dropzone and remove buttons when disabled", () => {
    renderWithProviders(
      <Harness initial={["alpha"]} disabled />,
    );

    // Dropzone is still rendered (we are below max) but reports aria-disabled,
    // and the file input is disabled at the DOM level.
    const zone = screen.getByTestId("support-attachment-zone");
    expect(zone).toHaveAttribute("aria-disabled", "true");
    expect(zone).toHaveAttribute("tabindex", "-1");

    const input = screen.getByTestId(
      "support-attachment-input",
    ) as HTMLInputElement;
    expect(input).toBeDisabled();

    // Remove button reports disabled so the user can't pop pending
    // attachments mid-submit.
    expect(
      screen.getByTestId("support-attachment-remove-alpha"),
    ).toBeDisabled();
  });
});
