"use client";

import { useState } from "react";
import {
  type AdminFeaturedBoardRow,
  releaseSlot,
  moveSlot,
} from "@/lib/api/adminFeaturedBoards";

interface Props {
  initial: AdminFeaturedBoardRow[];
  slotCount: number;
}

export function AdminFeaturedBoardsTable({ initial, slotCount }: Props) {
  const [rows, setRows] = useState(initial);

  async function handleRelease(slotPublicId: string) {
    if (!confirm("Force-release this slot? No refund is issued.")) return;
    await releaseSlot(slotPublicId);
    setRows(rs => rs.filter(r => r.slotPublicId !== slotPublicId));
  }

  async function handleMove(slotPublicId: string, boardIndex: number, position: number) {
    await moveSlot(slotPublicId, boardIndex, position);
    setRows(rs =>
      rs.map(r =>
        r.slotPublicId === slotPublicId ? { ...r, boardIndex, position } : r,
      ),
    );
  }

  // Group by boardIndex for the by-board layout.
  const byBoard = new Map<number, AdminFeaturedBoardRow[]>();
  for (let i = 1; i <= slotCount; i++) byBoard.set(i, []);
  for (const r of rows) {
    const list = byBoard.get(r.boardIndex) ?? [];
    list.push(r);
    byBoard.set(r.boardIndex, list);
  }

  return (
    <div className="flex gap-4">
      {[...byBoard.entries()].map(([boardIndex, queue]) => (
        <div key={boardIndex} className="flex-1 border border-slate-700 rounded p-3">
          <h3 className="text-lg font-semibold">Board {boardIndex}</h3>
          {queue.length === 0 ? (
            <p className="text-sm text-slate-400 mt-2">empty</p>
          ) : (
            queue.map(r => (
              <div key={r.slotPublicId} className="mt-3 border-t border-slate-700 pt-2">
                <div className="text-sm font-medium">{r.auctionTitle}</div>
                <div className="text-xs text-slate-400">
                  pos {r.position} for L${r.currentBid.toLocaleString()} ends{" "}
                  {new Date(r.endsAt).toLocaleDateString()}
                </div>
                <div className="mt-2 flex gap-2 text-xs">
                  <button
                    onClick={() => handleRelease(r.slotPublicId)}
                    className="px-2 py-1 rounded bg-rose-700 text-white"
                  >
                    Release
                  </button>
                  <select
                    value={r.boardIndex}
                    onChange={e =>
                      handleMove(r.slotPublicId, Number(e.target.value), r.position)
                    }
                    className="bg-slate-800 text-white rounded"
                  >
                    {[...byBoard.keys()].map(b => (
                      <option key={b} value={b}>
                        Board {b}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
            ))
          )}
        </div>
      ))}
    </div>
  );
}
