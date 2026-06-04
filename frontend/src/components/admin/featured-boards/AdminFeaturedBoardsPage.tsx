"use client";

import { useEffect, useState } from "react";
import {
  type AdminFeaturedBoardRow,
  listAdminFeaturedBoards,
} from "@/lib/api/adminFeaturedBoards";
import { AdminFeaturedBoardsTable } from "./AdminFeaturedBoardsTable";

const SLOT_COUNT = 5;

export function AdminFeaturedBoardsPage() {
  const [rows, setRows] = useState<AdminFeaturedBoardRow[] | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    listAdminFeaturedBoards()
      .then(setRows)
      .catch(() => setError(true));
  }, []);

  if (error) {
    return <p className="text-danger text-sm">Failed to load featured board slots.</p>;
  }

  if (rows === null) {
    return <p className="text-sm text-slate-400">Loading...</p>;
  }

  return (
    <main className="p-6">
      <h1 className="text-2xl font-semibold">Featured boards</h1>
      <p className="text-sm text-slate-400 mt-1">
        {rows.length} active slot{rows.length === 1 ? "" : "s"} across {SLOT_COUNT} boards.
      </p>
      <div className="mt-6">
        <AdminFeaturedBoardsTable initial={rows} slotCount={SLOT_COUNT} />
      </div>
    </main>
  );
}
