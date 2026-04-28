export type AdminDisputeAction =
  | "RECOGNIZE_PAYMENT"
  | "RESET_TO_FUNDED"
  | "RESUME_TRANSFER"
  | "MARK_EXPIRED";

export type EscrowDisputeReasonCategory =
  | "SELLER_NOT_RESPONSIVE"
  | "WRONG_PARCEL_TRANSFERRED"
  | "PAYMENT_NOT_CREDITED"
  | "FRAUD_SUSPECTED"
  | "OTHER";

export type EscrowState =
  | "ESCROW_PENDING"
  | "FUNDED"
  | "TRANSFER_PENDING"
  | "COMPLETED"
  | "DISPUTED"
  | "EXPIRED"
  | "FROZEN";

export type AdminDisputeQueueRow = {
  escrowId: number;
  auctionId: number;
  auctionTitle: string;
  sellerEmail: string;
  winnerEmail: string;
  salePriceL: number;
  status: EscrowState;
  reasonCategory: EscrowDisputeReasonCategory | null;
  openedAt: string;
  ageMinutes: number;
  winnerEvidenceCount: number;
  sellerEvidenceCount: number;
};

export type DisputeEvidenceImageDto = {
  s3Key: string;
  contentType: string;
  size: number;
  uploadedAt: string;
  presignedUrl: string;
  presignedUntil: string;
};

export type EscrowLedgerEntry = {
  at: string;
  type: string;
  amount: number | null;
  detail: string;
};

export type AdminDisputeDetail = {
  escrowId: number;
  auctionId: number;
  auctionTitle: string;
  sellerEmail: string;
  sellerUserId: number;
  winnerEmail: string;
  winnerUserId: number;
  salePriceL: number;
  status: EscrowState;
  reasonCategory: EscrowDisputeReasonCategory | null;
  winnerDescription: string;
  slTransactionKey: string | null;
  winnerEvidence: DisputeEvidenceImageDto[];
  sellerEvidenceText: string | null;
  sellerEvidenceSubmittedAt: string | null;
  sellerEvidence: DisputeEvidenceImageDto[];
  openedAt: string;
  ledger: EscrowLedgerEntry[];
};

export type AdminDisputeResolveRequest = {
  action: AdminDisputeAction;
  alsoCancelListing?: boolean;
  adminNote: string;
};

export type AdminDisputeResolveResponse = {
  escrowId: number;
  newState: EscrowState;
  refundQueued: boolean;
  listingCancelled: boolean;
  resolvedAt: string;
};

export type AdminDisputeFilters = {
  status?: EscrowState;
  reasonCategory?: EscrowDisputeReasonCategory;
  page?: number;
  size?: number;
};
