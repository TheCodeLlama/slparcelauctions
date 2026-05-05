"use client";
import { useState } from "react";
import type { ReactNode } from "react";
import { useRotateSecret } from "@/lib/admin/infrastructureHooks";

export function RotateSecretModal({ onClose }: { onClose: () => void }) {
  const rotate = useRotateSecret();
  const [confirmed, setConfirmed] = useState(false);

  if (rotate.data) {
    return (
      <Backdrop onClose={() => {}}>
        <h2 className="text-sm font-semibold mb-2">New secret. Save it now.</h2>
        <p className="text-[11px] opacity-70 mb-3">
          This value will <strong>not</strong> be shown again. Copy it before closing.
        </p>
        <div className="bg-bg-subtle rounded p-3 font-mono text-xs break-all mb-3">
          {rotate.data.secretValue}
        </div>
        <button
          type="button"
          onClick={() => navigator.clipboard.writeText(rotate.data!.secretValue)}
          className="px-3 py-1.5 border border-border rounded text-xs mb-3"
        >Copy to clipboard</button>
        <h3 className="text-xs font-semibold mb-2">Push results</h3>
        <ul className="text-xs space-y-1 mb-3">
          {rotate.data.terminalPushResults.map((r) => (
            <li key={r.terminalId} className={r.success ? "text-success" : "text-danger"}>
              {r.success ? "✓" : "✗"} {r.terminalName}
              {r.errorMessage && <span className="opacity-70">: {r.errorMessage}</span>}
            </li>
          ))}
        </ul>
        <button
          type="button"
          onClick={onClose}
          className="px-3 py-2 bg-brand text-white rounded text-xs font-semibold w-full"
        >I&apos;ve saved it, close</button>
      </Backdrop>
    );
  }

  return (
    <Backdrop onClose={onClose}>
      <h2 className="text-sm font-semibold mb-2">Rotate shared secret?</h2>
      <p className="text-[11px] opacity-70 mb-3">
        This rotates the active credential for all registered terminals.
        The new secret will be displayed once.
      </p>
      <label className="flex gap-2 text-xs mb-3">
        <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
        I understand the new secret will be shown only once
      </label>
      <div className="flex gap-2">
        <button type="button" onClick={onClose}
                className="flex-1 px-3 py-2 border border-border rounded text-xs">Cancel</button>
        <button
          type="button"
          disabled={!confirmed || rotate.isPending}
          onClick={() => rotate.mutate()}
          className="flex-1 px-3 py-2 bg-info text-white rounded text-xs font-semibold disabled:opacity-50"
        >{rotate.isPending ? "Rotating…" : "Rotate now"}</button>
      </div>
    </Backdrop>
  );
}

function Backdrop({ children, onClose }: { children: ReactNode; onClose: () => void }) {
  return (
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center" onClick={onClose}>
      <div className="bg-bg rounded p-5 max-w-md" onClick={(e) => e.stopPropagation()}>
        {children}
      </div>
    </div>
  );
}
