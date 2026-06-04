import { api } from "@/lib/api";

export interface AdminFeaturedBoardRow {
  slotPublicId: string;
  boardIndex: number;
  position: number;
  auctionPublicId: string;
  auctionTitle: string;
  currentBid: number;
  endsAt: string;
  assignedAt: string;
}

export function listAdminFeaturedBoards(): Promise<AdminFeaturedBoardRow[]> {
  return api.get<AdminFeaturedBoardRow[]>("/api/v1/admin/featured-boards");
}

export function releaseSlot(slotPublicId: string): Promise<void> {
  return api.post<void>(`/api/v1/admin/featured-boards/${slotPublicId}/release`, {});
}

export function moveSlot(
  slotPublicId: string,
  boardIndex: number,
  position: number,
): Promise<void> {
  return api.patch<void>(
    `/api/v1/admin/featured-boards/${slotPublicId}/move`,
    { boardIndex, position },
  );
}
