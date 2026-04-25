import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { SuspensionErrorModal } from "./SuspensionErrorModal";

describe("SuspensionErrorModal", () => {
  it("does not render anything when code is null", () => {
    renderWithProviders(<SuspensionErrorModal code={null} onClose={vi.fn()} />);
    expect(
      screen.queryByTestId("suspension-error-modal"),
    ).not.toBeInTheDocument();
  });

  it("renders the PENALTY_OWED variant", () => {
    renderWithProviders(
      <SuspensionErrorModal code="PENALTY_OWED" onClose={vi.fn()} />,
    );
    const modal = screen.getByTestId("suspension-error-modal");
    expect(modal.dataset.code).toBe("PENALTY_OWED");
    expect(modal).toHaveTextContent(/penalty owed/i);
    expect(modal).toHaveTextContent(/outstanding penalty balance/i);
    expect(modal).toHaveTextContent(/SLPA terminal/i);
  });

  it("renders the TIMED_SUSPENSION variant", () => {
    renderWithProviders(
      <SuspensionErrorModal code="TIMED_SUSPENSION" onClose={vi.fn()} />,
    );
    const modal = screen.getByTestId("suspension-error-modal");
    expect(modal.dataset.code).toBe("TIMED_SUSPENSION");
    expect(modal).toHaveTextContent(/temporarily suspended/i);
  });

  it("renders the PERMANENT_BAN variant", () => {
    renderWithProviders(
      <SuspensionErrorModal code="PERMANENT_BAN" onClose={vi.fn()} />,
    );
    const modal = screen.getByTestId("suspension-error-modal");
    expect(modal.dataset.code).toBe("PERMANENT_BAN");
    expect(modal).toHaveTextContent(/permanently suspended/i);
    expect(modal).toHaveTextContent(/contact support/i);
  });

  it("includes a link back to the dashboard", () => {
    renderWithProviders(
      <SuspensionErrorModal code="PENALTY_OWED" onClose={vi.fn()} />,
    );
    expect(
      screen.getByRole("link", { name: /go to dashboard/i }),
    ).toHaveAttribute("href", "/dashboard");
  });
});
