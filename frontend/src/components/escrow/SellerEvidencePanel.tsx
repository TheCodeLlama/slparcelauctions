"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { DisputeEvidenceUploader } from "./DisputeEvidenceUploader";
import { api } from "@/lib/api";
import { escrowKey } from "@/app/auction/[publicId]/escrow/EscrowPageClient";

type Props = { auctionPublicId: string };

export function SellerEvidencePanel({ auctionPublicId }: Props) {
  const [text, setText] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const qc = useQueryClient();

  const submit = useMutation({
    mutationFn: async () => {
      const fd = new FormData();
      fd.append(
        "body",
        new Blob([JSON.stringify({ text })], { type: "application/json" }),
      );
      files.forEach((f) => fd.append("files", f));
      return api.post(
        `/api/v1/auctions/${auctionPublicId}/escrow/dispute/seller-evidence`,
        fd,
      );
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: escrowKey(auctionPublicId) });
    },
  });

  const canSubmit = text.length >= 10 && text.length <= 2000 && !submit.isPending;

  return (
    <section className="bg-bg-muted rounded p-4 space-y-3">
      <h3 className="text-sm font-semibold">Submit your evidence</h3>
      <p className="text-[11px] opacity-65">
        A winner has disputed this sale. Submit your side of the story for admin
        review. Submit-once — you cannot append later.
      </p>
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="Describe what happened. E.g., 'I transferred the parcel at 14:30, here's my receipt.'"
        className="w-full h-24 bg-bg-subtle text-xs p-2 rounded resize-y"
        maxLength={2000}
      />
      <div className="text-[10px] opacity-40">
        {text.length} / 2000 (min 10)
      </div>
      <DisputeEvidenceUploader files={files} onChange={setFiles} />
      <button
        type="button"
        disabled={!canSubmit}
        onClick={() => submit.mutate()}
        className="px-3 py-2 bg-brand text-fg rounded text-xs font-semibold disabled:opacity-50"
      >
        {submit.isPending ? "Submitting…" : "Submit evidence"}
      </button>
      {submit.isError && (
        <p className="text-[10px] text-danger">
          Submit failed: {(submit.error as Error).message}
        </p>
      )}
    </section>
  );
}
