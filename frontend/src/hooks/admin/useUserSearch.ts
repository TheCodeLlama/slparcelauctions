"use client";
import { useState, useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";

function useDebounce<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(t);
  }, [value, delayMs]);
  return debounced;
}

export function useUserSearch(query: string) {
  const debounced = useDebounce(query, 300);
  return useQuery({
    queryKey: ["user-search-autocomplete", debounced],
    queryFn: () => adminApi.users.search({ search: debounced, page: 0, size: 5 }),
    enabled: debounced.length >= 2,
    staleTime: 30_000,
  });
}
