"use client";

import { useEffect, useState } from "react";
import { Loader2 } from "@/components/ui/icons";

/**
 * UUID_ENTRY in-progress panel. Synchronous verification that usually
 * resolves in the first poll tick; this UI is a "we're checking" beat
 * so the seller doesn't see the cards blink to a spinner and back.
 *
 * After 10 seconds we append a slow-indicator line — if verification
 * hasn't resolved by then something is probably wrong upstream (SL
 * World API slow/unreachable), and the seller deserves the context.
 */
export function VerificationMethodUuidEntry() {
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    const id = setTimeout(() => setSlow(true), 10_000);
    return () => clearTimeout(id);
  }, []);

  return (
    <section
      aria-live="polite"
      className="flex flex-col items-center gap-3 rounded-lg bg-bg-subtle p-6 text-center"
    >
      <Loader2
        aria-hidden="true"
        className="size-7 animate-spin text-brand"
      />
      <p className="text-sm font-semibold text-fg">
        Checking ownership with the Second Life World API…
      </p>
      {slow && (
        <p className="text-xs text-fg-muted">
          This is taking longer than usual.
        </p>
      )}
    </section>
  );
}
