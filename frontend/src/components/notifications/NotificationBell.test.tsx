import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/msw/server";
import { notificationHandlers, seedNotification, clearNotifications } from "@/test/msw/handlers";
import { NotificationBell } from "./NotificationBell";

// ---------------------------------------------------------------------------
// Auth module mock — controls the status returned by useAuth()
// ---------------------------------------------------------------------------
const authMock = vi.hoisted(() => ({ status: "authenticated" as "authenticated" | "unauthenticated" | "loading" }));

vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ status: authMock.status, user: authMock.status === "authenticated" ? { id: 42 } : null }),
}));

// ---------------------------------------------------------------------------
// useUnreadCount mock — drives the count directly without a real fetch
// ---------------------------------------------------------------------------
const countMock = vi.hoisted(() => ({ count: 0 }));

vi.mock("@/hooks/notifications/useUnreadCount", () => ({
  useUnreadCount: () => ({ data: { count: countMock.count }, isPending: false }),
}));

describe("NotificationBell", () => {
  beforeEach(() => {
    clearNotifications();
    authMock.status = "authenticated";
    countMock.count = 0;
  });

  it("renders null when unauthenticated", () => {
    authMock.status = "unauthenticated";
    renderWithProviders(<NotificationBell />);
    // When unauthenticated, no bell button is rendered
    expect(screen.queryByRole("button", { name: /Notifications/ })).not.toBeInTheDocument();
  });

  it("shows a badge when unread count is positive", () => {
    countMock.count = 3;
    renderWithProviders(<NotificationBell />);
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("caps the badge at 99+ when count exceeds 99", () => {
    countMock.count = 150;
    renderWithProviders(<NotificationBell />);
    expect(screen.getByText("99+")).toBeInTheDocument();
  });

  it("hides the badge when count is 0", () => {
    countMock.count = 0;
    renderWithProviders(<NotificationBell />);
    // Badge span is not rendered when count === 0
    expect(screen.queryByText("0")).not.toBeInTheDocument();
  });

  it("aria-label includes the count when there are unread notifications", () => {
    countMock.count = 5;
    renderWithProviders(<NotificationBell />);
    const button = screen.getByRole("button", { name: /Notifications \(5 unread\)/ });
    expect(button).toBeInTheDocument();
  });

  it("aria-label is generic 'Notifications' when count is 0", () => {
    countMock.count = 0;
    renderWithProviders(<NotificationBell />);
    const button = screen.getByRole("button", { name: "Notifications" });
    expect(button).toBeInTheDocument();
  });
});

// Suppress unused import warning — server is needed when integration tests run alongside
void server;
void notificationHandlers;
void seedNotification;
