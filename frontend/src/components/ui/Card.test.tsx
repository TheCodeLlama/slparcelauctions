import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { Card } from "./Card";

describe("Card", () => {
  it("renders children and merges consumer className", () => {
    renderWithProviders(
      <Card className="max-w-md" data-testid="card">
        <p>hello</p>
      </Card>
    );
    const card = screen.getByTestId("card");
    expect(card.className).toContain("bg-surface-container-lowest");
    expect(card.className).toContain("rounded-default");
    expect(card.className).toContain("shadow-soft");
    expect(card.className).toContain("max-w-md");
    expect(screen.getByText("hello")).toBeInTheDocument();
  });

  it("renders Card.Header, Card.Body, Card.Footer in compound usage", () => {
    renderWithProviders(
      <Card>
        <Card.Header data-testid="header">Title</Card.Header>
        <Card.Body data-testid="body">Body content</Card.Body>
        <Card.Footer data-testid="footer">Actions</Card.Footer>
      </Card>
    );
    expect(screen.getByTestId("header")).toHaveTextContent("Title");
    expect(screen.getByTestId("body")).toHaveTextContent("Body content");
    expect(screen.getByTestId("footer")).toHaveTextContent("Actions");
  });

  it("does not render any border classes (no-line rule)", () => {
    renderWithProviders(<Card data-testid="card">x</Card>);
    const card = screen.getByTestId("card");
    expect(card.className).not.toMatch(/\bborder\b/);
  });
});
