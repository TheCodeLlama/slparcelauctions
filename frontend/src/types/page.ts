// Mirrors Spring Data's Page<T> serialization shape. The backend returns
// this envelope for every paginated endpoint (bid history, my-bids,
// user-scoped active listings). Only the fields the frontend actually
// consumes are typed — Spring emits more (`first`, `last`, `sort`,
// `pageable`, `empty`, etc.) but they're never read here, and narrowing the
// surface keeps the DTO honest about what the UI depends on.
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number; // 0-based page index
  size: number;
}
