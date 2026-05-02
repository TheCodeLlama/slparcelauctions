"use client";

type Props = { count: number };

export function SiblingFlagWarning({ count }: Props) {
  if (count <= 0) return null;
  return (
    <div
      className="rounded-lg bg-info-bg/30 border border-info/30 px-4 py-3 text-sm text-info"
      data-testid="sibling-flag-warning"
    >
      This auction has <span className="font-medium">{count}</span> other open flag
      {count === 1 ? "" : "s"}. Resolving this one alone doesn&apos;t address{" "}
      {count === 1 ? "it" : "them"} — {count === 1 ? "it needs" : "they need"} separate review.
    </div>
  );
}
