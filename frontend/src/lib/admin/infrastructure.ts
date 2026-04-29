export type BotPoolHealthRow = {
  workerId: number;
  name: string;
  slUuid: string;
  registeredAt: string;
  lastSeenAt: string;
  sessionState: string | null;
  currentRegion: string | null;
  currentTaskKey: string | null;
  currentTaskType: string | null;
  isAlive: boolean;
};

export type AdminTerminalRow = {
  terminalId: string;
  regionName: string | null;
  httpInUrl: string;
  lastSeenAt: string;
  lastHeartbeatAt: string | null;
  lastReportedBalance: number | null;
  currentSecretVersion: number | null;
};

export type TerminalPushResult = {
  terminalId: string;
  terminalName: string;
  success: boolean;
  errorMessage: string | null;
};

export type TerminalRotationResponse = {
  newVersion: number;
  secretValue: string;
  terminalPushResults: TerminalPushResult[];
};

export type ReconciliationStatus = "BALANCED" | "MISMATCH" | "ERROR";

export type ReconciliationRunRow = {
  id: number;
  ranAt: string;
  status: ReconciliationStatus;
  expected: number;
  observed: number | null;
  drift: number | null;
  errorMessage: string | null;
};

export type WithdrawalStatus = "PENDING" | "COMPLETED" | "FAILED";

export type WithdrawalRow = {
  id: number;
  amount: number;
  recipientUuid: string;
  adminUserId: number;
  notes: string | null;
  status: WithdrawalStatus;
  requestedAt: string;
  completedAt: string | null;
  failureReason: string | null;
};

export type WithdrawalRequest = {
  amount: number;
  recipientUuid: string;
  notes: string;
};

export type AdminOwnershipRecheckResponse = {
  ownerMatch: boolean;
  observedOwner: string | null;
  expectedOwner: string | null;
  checkedAt: string;
  auctionStatus: string;
};
