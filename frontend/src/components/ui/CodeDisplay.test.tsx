import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, fireEvent, waitFor } from "@/test/render";
import { CodeDisplay } from "./CodeDisplay";

const writeTextMock = vi.fn<(text: string) => Promise<void>>();

if (!navigator.clipboard) {
  Object.defineProperty(navigator, "clipboard", {
    value: { writeText: writeTextMock },
    writable: true,
    configurable: true,
  });
}

beforeEach(() => {
  writeTextMock.mockReset();
  (navigator.clipboard as unknown as Record<string, unknown>).writeText = writeTextMock;
});

describe("CodeDisplay", () => {
  it("renders code and label", () => {
    renderWithProviders(
      <CodeDisplay code="abc-123" label="Verification Code" />,
    );
    expect(screen.getByText("Verification Code")).toBeInTheDocument();
    expect(screen.getByText("abc-123")).toBeInTheDocument();
  });

  it("calls navigator.clipboard.writeText on copy click", async () => {
    writeTextMock.mockResolvedValue(undefined);

    renderWithProviders(<CodeDisplay code="abc-123" label="Code" />);
    fireEvent.click(screen.getByRole("button", { name: /copy/i }));

    await waitFor(() => {
      expect(writeTextMock).toHaveBeenCalledWith("abc-123");
    });
  });

  it("fires onCopySuccess after successful write", async () => {
    const onCopySuccess = vi.fn();
    writeTextMock.mockResolvedValue(undefined);

    renderWithProviders(
      <CodeDisplay code="abc-123" label="Code" onCopySuccess={onCopySuccess} />,
    );
    fireEvent.click(screen.getByRole("button", { name: /copy/i }));

    await waitFor(() => {
      expect(onCopySuccess).toHaveBeenCalledTimes(1);
    });
  });

  it("fires onCopyError when clipboard rejects", async () => {
    const onCopyError = vi.fn();
    writeTextMock.mockRejectedValue(new Error("denied"));

    renderWithProviders(
      <CodeDisplay code="abc-123" label="Code" onCopyError={onCopyError} />,
    );
    fireEvent.click(screen.getByRole("button", { name: /copy/i }));

    await waitFor(() => {
      expect(onCopyError).toHaveBeenCalledTimes(1);
    });
  });
});
