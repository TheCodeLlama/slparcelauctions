// Mirrors backend ParcelTagResponse + ParcelTagGroupResponse
// (com.slparcelauctions.backend.parceltag.dto). Backend groups tags by
// category and orders within each category alphabetically by label.

export interface ParcelTagDto {
  code: string;
  label: string;
  category: string;
  description: string | null;
}

export interface ParcelTagGroupDto {
  category: string;
  tags: ParcelTagDto[];
}
