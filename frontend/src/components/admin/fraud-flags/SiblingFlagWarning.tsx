"use client";

type Props = { count: number };

export function SiblingFlagWarning({ count }: Props) {
  if (count <= 0) return null;
  return (
    <div
      className="rounded-default bg-tertiary-container/30 border border-tertiary/30 px-4 py-3 text-body-sm text-on-tertiary-container"
      data-testid="sibling-flag-warning"
    >
      This auction has <span className="font-medium">{count}</span> other open flag
      {count === 1 ? "" : "s"}. Resolving this one alone doesn&apos;t address{" "}
      {count === 1 ? "it" : "them"} — {count === 1 ? "it needs" : "they need"} separate review.
    </div>
  );
}
