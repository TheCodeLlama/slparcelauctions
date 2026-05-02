import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen, waitFor, userEvent } from "@/test/render";

vi.mock("@/lib/api/reviews", () => ({
  flagReview: vi.fn(),
}));

import { flagReview } from "@/lib/api/reviews";
import { FlagReviewModal } from "./FlagReviewModal";

const mockFlagReview = vi.mocked(flagReview);

const defaultProps = {
  open: true,
  onClose: vi.fn(),
  reviewId: 42,
};

describe("FlagReviewModal", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mockFlagReview.mockResolvedValue(undefined);
  });

  it("renders 5 reason options", () => {
    renderWithProviders(<FlagReviewModal {...defaultProps} />);
    const radios = screen.getAllByRole("radio");
    expect(radios).toHaveLength(5);
  });

  it("submit button is disabled when no reason is picked", () => {
    renderWithProviders(<FlagReviewModal {...defaultProps} />);
    const submitBtn = screen.getByRole("button", { name: /submit flag/i });
    expect(submitBtn).toBeDisabled();
  });

  it("submitting calls flagReview with the chosen reason", async () => {
    const user = userEvent.setup();
    renderWithProviders(<FlagReviewModal {...defaultProps} />);

    // Select the first reason (FALSE_INFO)
    const firstRadio = screen.getAllByRole("radio")[0];
    await user.click(firstRadio);

    const submitBtn = screen.getByRole("button", { name: /submit flag/i });
    expect(submitBtn).not.toBeDisabled();

    await user.click(submitBtn);

    await waitFor(() => {
      expect(mockFlagReview).toHaveBeenCalledWith(42, {
        reason: "FALSE_INFO",
        notes: undefined,
      });
    });
  });

  it("success state replaces body with thank-you message", async () => {
    const user = userEvent.setup();
    mockFlagReview.mockResolvedValue(undefined);

    renderWithProviders(<FlagReviewModal {...defaultProps} />);

    const firstRadio = screen.getAllByRole("radio")[0];
    await user.click(firstRadio);

    await user.click(screen.getByRole("button", { name: /submit flag/i }));

    await waitFor(() => {
      expect(screen.getByText(/we've received your flag/i)).toBeInTheDocument();
    });

    // The reason radios should no longer be visible
    expect(screen.queryAllByRole("radio")).toHaveLength(0);
  });

  it("error state shows error message and stays on the form", async () => {
    const user = userEvent.setup();
    mockFlagReview.mockRejectedValue(new Error("Server error"));

    renderWithProviders(<FlagReviewModal {...defaultProps} />);

    const firstRadio = screen.getAllByRole("radio")[0];
    await user.click(firstRadio);

    await user.click(screen.getByRole("button", { name: /submit flag/i }));

    await waitFor(() => {
      expect(screen.getByText("Server error")).toBeInTheDocument();
    });

    // Form is still visible — radios are still rendered
    expect(screen.getAllByRole("radio")).toHaveLength(5);
  });
});
