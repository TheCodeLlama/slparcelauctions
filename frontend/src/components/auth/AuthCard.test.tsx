// frontend/src/components/auth/AuthCard.test.tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { AuthCard } from "./AuthCard";

describe("AuthCard", () => {
  it("always renders the SLParcels brand header and tagline", () => {
    renderWithProviders(
      <AuthCard>
        <AuthCard.Body>content</AuthCard.Body>
      </AuthCard>
    );
    expect(screen.getByText("SLParcels")).toBeInTheDocument();
    expect(screen.getByText("The Digital Curator")).toBeInTheDocument();
  });

  it("renders Title, Subtitle, Body, and Footer subcomponents", () => {
    renderWithProviders(
      <AuthCard>
        <AuthCard.Title>Create Account</AuthCard.Title>
        <AuthCard.Subtitle>Join the curator</AuthCard.Subtitle>
        <AuthCard.Body>form goes here</AuthCard.Body>
        <AuthCard.Footer>footer link</AuthCard.Footer>
      </AuthCard>
    );
    expect(screen.getByText("Create Account")).toBeInTheDocument();
    expect(screen.getByText("Join the curator")).toBeInTheDocument();
    expect(screen.getByText("form goes here")).toBeInTheDocument();
    expect(screen.getByText("footer link")).toBeInTheDocument();
  });
});
