import { apiData, apiFetch } from "./api";

export type AccountLifecycle = {
  status: "ACTIVE" | "SUSPENDED" | "DEACTIVATED" | "PENDING_DELETION" | "DELETED";
  deletionRequestedAt: string | null;
  deletionCompletesAt: string | null;
};

export type AccountStepUp = {
  currentPassword: string;
  mfaCode?: string;
};

export type AccountPauseDuration = 7 | 30 | 90;

export type AccountDataExport = {
  exportedAt: string;
  profile: {
    id: string;
    email: string;
    username: string;
    displayName: string;
    status: string;
    createdAt: string;
    emailVerifiedAt: string | null;
  };
  memberships: Array<{
    organizationId: string;
    organizationName: string;
    role: string;
    status: string;
    joinedAt: string;
  }>;
  createdApiKeys: Array<{
    id: string;
    organizationId: string;
    projectId: string;
    environmentId: string;
    name: string;
    prefix: string;
    scopes: string[];
    status: string;
    createdAt: string;
    expiresAt: string | null;
    lastUsedAt: string | null;
  }>;
};

export function getAccountLifecycle(signal?: AbortSignal): Promise<AccountLifecycle> {
  return apiData<AccountLifecycle>("/api/v1/account/lifecycle", { signal });
}

export function requestAccountDeletion(stepUp: AccountStepUp): Promise<AccountLifecycle> {
  return apiData<AccountLifecycle>("/api/v1/account/deletion", {
    method: "POST",
    body: JSON.stringify(stepUp),
  });
}

export function deactivateAccount(
  stepUp: AccountStepUp & { durationDays: AccountPauseDuration },
): Promise<void> {
  return apiFetch<void>("/api/v1/account/deactivate", {
    method: "POST",
    body: JSON.stringify(stepUp),
  });
}

export function cancelAccountDeletion(stepUp: AccountStepUp): Promise<AccountLifecycle> {
  return apiData<AccountLifecycle>("/api/v1/account/deletion/cancel", {
    method: "POST",
    body: JSON.stringify(stepUp),
  });
}

export function exportAccountData(stepUp: AccountStepUp): Promise<AccountDataExport> {
  return apiFetch<AccountDataExport>("/api/v1/account/export", {
    method: "POST",
    body: JSON.stringify(stepUp),
  });
}
