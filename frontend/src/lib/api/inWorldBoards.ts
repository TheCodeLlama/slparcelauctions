import { api } from "@/lib/api";
import type { FeaturedBoardPayload } from "@/types/promotion";

export function fetchFeaturedBoard(boardIndex: number): Promise<FeaturedBoardPayload> {
  return api.get<FeaturedBoardPayload>(`/api/v1/in-world/featured-board/${boardIndex}`);
}

export function fetchPlaceholderBoard(): Promise<FeaturedBoardPayload> {
  return api.get<FeaturedBoardPayload>("/api/v1/in-world/board/placeholder");
}
