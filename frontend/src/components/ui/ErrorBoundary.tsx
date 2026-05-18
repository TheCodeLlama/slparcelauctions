"use client";

import { Component, type ReactNode } from "react";

export interface ErrorBoundaryProps {
  /** The subtree to guard. */
  children: ReactNode;
  /**
   * Fallback UI shown when a descendant throws during render. Kept generic so
   * the same boundary can wrap any region; callers scope it tightly (e.g.
   * just the ledger list, not the whole wallet balance card).
   */
  fallback: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
}

/**
 * Minimal client error boundary. React only supports error boundaries as
 * class components, so this stays a class deliberately.
 *
 * Motivation: a single unhandled ledger entry type used to make the wallet
 * renderers throw, and with no boundary the entire wallet page white-screened.
 * Wrapping just the transaction list means a future render exception degrades
 * to an inline message while the balance card and the rest of the app keep
 * working.
 */
export class ErrorBoundary extends Component<
  ErrorBoundaryProps,
  ErrorBoundaryState
> {
  state: ErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  render(): ReactNode {
    if (this.state.hasError) {
      return this.props.fallback;
    }
    return this.props.children;
  }
}
