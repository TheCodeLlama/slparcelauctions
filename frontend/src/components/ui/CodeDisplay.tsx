"use client";

import { useCallback } from "react";
import { cn } from "@/lib/cn";
import { Copy } from "@/components/ui/icons";
import { IconButton } from "@/components/ui/IconButton";

type CodeDisplayProps = {
  code: string;
  label: string;
  onCopySuccess?: () => void;
  onCopyError?: (error: unknown) => void;
  className?: string;
};

export function CodeDisplay({
  code,
  label,
  onCopySuccess,
  onCopyError,
  className,
}: CodeDisplayProps) {
  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(code);
      onCopySuccess?.();
    } catch (err) {
      onCopyError?.(err);
    }
  }, [code, onCopySuccess, onCopyError]);

  return (
    <div className={cn("flex flex-col gap-1", className)}>
      <span className="text-label-sm text-on-surface-variant">{label}</span>
      <div className="flex items-center gap-2 rounded-lg bg-surface-container-lowest px-4 py-3">
        <code className="flex-1 font-mono text-body-lg text-on-surface select-all">
          {code}
        </code>
        <IconButton
          aria-label="Copy to clipboard"
          variant="tertiary"
          size="sm"
          onClick={handleCopy}
        >
          <Copy />
        </IconButton>
      </div>
    </div>
  );
}
