import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Textarea } from "./Textarea";

describe("Textarea", () => {
  it("renders with a label", () => {
    render(<Textarea label="Message" />);
    expect(screen.getByLabelText("Message")).toBeInTheDocument();
  });

  it("renders helperText when no error", () => {
    render(<Textarea label="Message" helperText="Max 10000 chars" />);
    expect(screen.getByText("Max 10000 chars")).toBeInTheDocument();
  });

  it("renders error text when error is set", () => {
    render(<Textarea label="Message" error="Required" />);
    expect(screen.getByText("Required")).toBeInTheDocument();
  });

  it("accepts user input via the rows prop", async () => {
    const u = userEvent.setup();
    render(<Textarea label="Message" rows={8} />);
    const ta = screen.getByLabelText("Message") as HTMLTextAreaElement;
    expect(ta.rows).toBe(8);
    await u.type(ta, "Hello");
    expect(ta.value).toBe("Hello");
  });
});
