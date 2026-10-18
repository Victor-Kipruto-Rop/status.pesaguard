/**
 * Typed calls to the identity endpoints.
 *
 * <p>Every request here is declared `anonymous` except the ones that require a
 * live session. That is not tidiness: `apiFetch` would otherwise attach a
 * bearer token to `/auth/login`, and more importantly a 401 from `/auth/login`
 * would trigger a refresh attempt, turning a wrong password into a refresh
 * storm against a session the user does not have yet.
 */

import { apiData } from "./api";
import { createUuid } from "./uuid";
import type {
  AuthSession,
  BackupCodes,
  CurrentSession,
  DeveloperOnboardingStatus,
  DeveloperOnboardingStep,
  DeveloperOnboardingWorkflow,
  ForgotPasswordRequest,
  LoginRequest,
  LoginEmailMfaChallenge,
  MfaEnrolment,
  MfaStatus,
  RefreshRequest,
  RegisterRequest,
  RegistrationResponse,
  ResetPasswordRequest,
  SessionSummary,
  WorkspaceSummary,
} from "../types/auth";
import type { PasskeyCredentialJson, PasskeyOptions } from "./passkeys";

export type InvitationPreview = {
  status: "PENDING" | "ACCEPTED" | "DECLINED" | "REVOKED" | "EXPIRED" | "CANCELLED";
  organizationName: string;
  role: string;
  inviterName: string;
  invitedEmailHint: string;
  expiresAt: string;
};

export function previewInvitation(token: string): Promise<InvitationPreview> {
  return apiData<InvitationPreview>(`/api/v1/invitations/${encodeURIComponent(token)}/preview`, {
    anonymous: true,
    skipRefresh: true,
  });
}

export function acceptInvitation(token: string): Promise<{ organizationId: string }> {
  return apiData<{ organizationId: string }>(`/api/v1/invitations/${encodeURIComponent(token)}/accept`, {
    method: "POST",
  });
}

export function declineInvitation(token: string): Promise<void> {
  return apiData<void>(`/api/v1/invitations/${encodeURIComponent(token)}/decline`, {
    method: "POST",
  });
}

export function login(request: LoginRequest): Promise<AuthSession> {
  return apiData<AuthSession>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify(request),
    anonymous: true,
    skipRefresh: true,
  });
}

export function verifyLoginEmailMfa(challengeId: string, code: string): Promise<AuthSession> {
  return apiData<AuthSession>("/api/v1/auth/login/email-mfa/verify", {
    method: "POST",
    body: JSON.stringify({ challengeId, code }),
    anonymous: true,
    skipRefresh: true,
  });
}

export function resendLoginEmailMfa(challengeId: string): Promise<LoginEmailMfaChallenge> {
  return apiData<LoginEmailMfaChallenge>("/api/v1/auth/login/email-mfa/resend", {
    method: "POST",
    body: JSON.stringify({ challengeId }),
    anonymous: true,
    skipRefresh: true,
  });
}

export function listWorkspaces(): Promise<WorkspaceSummary[]> {
  return apiData<WorkspaceSummary[]>("/api/v1/workspaces");
}

export function switchWorkspace(workspaceId: string): Promise<AuthSession> {
  return apiData<AuthSession>("/api/v1/auth/switch-workspace", {
    method: "POST",
    body: JSON.stringify({ workspaceId }),
  });
}

export function register(request: RegisterRequest): Promise<RegistrationResponse> {
  return apiData<RegistrationResponse>("/api/v1/auth/register", {
    method: "POST",
    body: JSON.stringify(request),
    anonymous: true,
    skipRefresh: true,
  });
}

export function verifyEmail(token: string): Promise<{ verified: boolean }>;
export function verifyEmail(email: string, code: string): Promise<{ verified: boolean }>;
export function verifyEmail(emailOrToken: string, code?: string): Promise<{ verified: boolean }> {
  return apiData<{ verified: boolean }>("/api/v1/auth/verify-email", {
    method: "POST",
    body: JSON.stringify(code ? { email: emailOrToken, code } : { token: emailOrToken }),
    anonymous: true,
    skipRefresh: true,
  });
}

export function completeRegistrationVerification(
  email: string,
  code: string,
  password: string,
): Promise<AuthSession> {
  return apiData<AuthSession>("/api/v1/auth/verify-email/complete-registration", {
    method: "POST",
    body: JSON.stringify({ email, code, password }),
    anonymous: true,
    skipRefresh: true,
  });
}

export function completeRegistrationVerificationByLink(token: string): Promise<AuthSession> {
  return apiData<AuthSession>("/api/v1/auth/verify-email/complete-registration-link", {
    method: "POST",
    body: JSON.stringify({ token }),
    anonymous: true,
    skipRefresh: true,
  });
}

export function resendEmailVerification(email: string): Promise<void> {
  return apiData<void>("/api/v1/auth/verify-email/resend", {
    method: "POST",
    body: JSON.stringify({ email }),
    anonymous: true,
    skipRefresh: true,
  });
}

export function onboardingStatus(): Promise<DeveloperOnboardingStatus> {
  return apiData<DeveloperOnboardingStatus>("/api/v1/onboarding/status");
}

export function onboardingWorkflow(): Promise<DeveloperOnboardingWorkflow> {
  return apiData<DeveloperOnboardingWorkflow>("/api/v1/onboarding");
}

export function startOnboardingStep(step: string): Promise<DeveloperOnboardingWorkflow> {
  return apiData<DeveloperOnboardingWorkflow>(
    `/api/v1/onboarding/steps/${encodeURIComponent(step)}/start`,
    { method: "POST" },
  );
}

export function completeOnboardingStep(step: string): Promise<DeveloperOnboardingWorkflow> {
  return apiData<DeveloperOnboardingWorkflow>(
    `/api/v1/onboarding/steps/${encodeURIComponent(step)}/complete`,
    { method: "POST" },
  );
}

export function skipOnboardingStep(step: string): Promise<DeveloperOnboardingWorkflow> {
  return apiData<DeveloperOnboardingWorkflow>(
    `/api/v1/onboarding/steps/${encodeURIComponent(step)}/skip`,
    { method: "POST" },
  );
}

export function updateOnboardingProfile(displayName: string): Promise<DeveloperOnboardingStatus> {
  return apiData<DeveloperOnboardingStatus>("/api/v1/onboarding/profile", {
    method: "PATCH",
    body: JSON.stringify({ displayName }),
  });
}

export function updateOnboardingOrganization(name: string, description: string): Promise<void> {
  return apiData<void>("/api/v1/onboarding/organization", {
    method: "PATCH",
    body: JSON.stringify({ name, description }),
  });
}

export function updateOnboardingProgress(
  currentStep: DeveloperOnboardingStep,
  completedStep?: Exclude<DeveloperOnboardingStep, "profile" | "complete">,
): Promise<DeveloperOnboardingStatus> {
  return apiData<DeveloperOnboardingStatus>("/api/v1/onboarding/progress", {
    method: "PATCH",
    body: JSON.stringify({ currentStep, ...(completedStep ? { completedStep } : {}) }),
  });
}

export function skipOnboarding(): Promise<DeveloperOnboardingStatus> {
  return apiData<DeveloperOnboardingStatus>("/api/v1/onboarding/skip", { method: "POST" });
}

export function resumeOnboarding(): Promise<DeveloperOnboardingStatus> {
  return apiData<DeveloperOnboardingStatus>("/api/v1/onboarding/resume", { method: "POST" });
}

export function completeOnboarding(): Promise<DeveloperOnboardingStatus> {
  return apiData<DeveloperOnboardingStatus>("/api/v1/onboarding/complete", { method: "POST" });
}

export type OnboardingEnvironment = {
  id: string;
  projectId: string;
  name: string;
  type: string;
  baseUrl: string;
};

export type OnboardingApiKey = {
  id: string;
  name: string;
  key: string;
  prefix: string;
  scopes: string[];
  expiresAt: string;
  baseUrl: string;
};

export function createOnboardingEnvironment(
  projectId: string,
  name: string,
): Promise<OnboardingEnvironment> {
  return apiData<OnboardingEnvironment>(`/api/v1/onboarding/projects/${encodeURIComponent(projectId)}/environment`, {
    method: "POST",
    body: JSON.stringify({ name, type: "SANDBOX" }),
  });
}

export function createOnboardingApiKey(projectId: string, environmentId: string): Promise<OnboardingApiKey> {
  const idempotencyKey = createUuid();
  return apiData<OnboardingApiKey>("/api/v1/onboarding/api-key", {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ projectId, environmentId, idempotencyKey }),
  });
}

export function getUsername(): Promise<{ username: string }> {
  return apiData<{ username: string }>("/api/v1/auth/username");
}

export function updateUsername(username: string): Promise<{ username: string }> {
  return apiData<{ username: string }>("/api/v1/auth/username", {
    method: "PATCH",
    body: JSON.stringify({ username }),
  });
}

export function logout(signal?: AbortSignal): Promise<void> {
  return apiData<void>("/api/v1/auth/logout", { method: "POST", signal });
}

export function currentSession(): Promise<CurrentSession> {
  return apiData<CurrentSession>("/api/v1/auth/session");
}

export function listSessions(): Promise<SessionSummary[]> {
  return apiData<SessionSummary[]>("/api/v1/auth/sessions");
}

export function revokeSession(sessionId: string): Promise<void> {
  return apiData<void>(`/api/v1/auth/sessions/${encodeURIComponent(sessionId)}`, {
    method: "DELETE",
  });
}

export function revokeOtherSessions(): Promise<{ revokedCount: number }> {
  return apiData<{ revokedCount: number }>("/api/v1/auth/sessions/revoke-others", {
    method: "POST",
  });
}

/**
 * Always resolves, whatever the address.
 *
 * <p>The backend answers 202 with no body for both known and unknown addresses,
 * so this cannot and must not distinguish them. Any error here is a transport or
 * rate-limit failure, not a statement about the account.
 */
export function forgotPassword(request: ForgotPasswordRequest): Promise<void> {
  return apiData<void>("/api/v1/auth/forgot-password", {
    method: "POST",
    body: JSON.stringify(request),
    anonymous: true,
    skipRefresh: true,
  });
}

export function resetPassword(request: ResetPasswordRequest): Promise<void> {
  return apiData<void>("/api/v1/auth/reset-password", {
    method: "POST",
    body: JSON.stringify(request),
    anonymous: true,
    skipRefresh: true,
  });
}

export type PasswordChangeChallenge = {
  challengeId: string;
  maskedEmail: string;
  expiresAt: string;
};

export function requestPasswordChange(request: { currentPassword: string; newPassword: string }): Promise<PasswordChangeChallenge> {
  return apiData<PasswordChangeChallenge>("/api/v1/auth/change-password/request", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function verifyPasswordChange(request: {
  challengeId: string;
  code: string;
  currentPassword: string;
  newPassword: string;
}): Promise<void> {
  return apiData<void>("/api/v1/auth/change-password/verify", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function mfaStatus(): Promise<MfaStatus> {
  return apiData<MfaStatus>("/api/v1/auth/mfa/status");
}

export function beginMfaEnrolment(): Promise<MfaEnrolment> {
  return apiData<MfaEnrolment>("/api/v1/auth/mfa/enroll", { method: "POST" });
}

export function beginMfaEnrollmentChallenge(token: string): Promise<MfaEnrolment> {
  return apiData<MfaEnrolment>("/api/v1/auth/mfa/enroll", {
    method: "POST",
    authorizationToken: token,
    skipRefresh: true,
  });
}

type BackupCodesResponse = { backupCodes: string[]; session?: AuthSession | null };

export function confirmMfaEnrolment(code: string): Promise<BackupCodes> {
  return apiData<BackupCodesResponse>("/api/v1/auth/mfa/confirm", {
    method: "POST",
    body: JSON.stringify({ code }),
  }).then((result) => ({ codes: result.backupCodes, session: result.session }));
}

export function confirmMfaEnrollmentChallenge(code: string, token: string): Promise<BackupCodes> {
  return apiData<BackupCodesResponse>("/api/v1/auth/mfa/confirm", {
    method: "POST",
    body: JSON.stringify({ code }),
    authorizationToken: token,
    skipRefresh: true,
  }).then((result) => ({ codes: result.backupCodes, session: result.session }));
}

export function regenerateMfaRecoveryCodes(code: string): Promise<BackupCodes> {
  return apiData<BackupCodesResponse>("/api/v1/auth/mfa/recovery-codes", {
    method: "POST",
    body: JSON.stringify({ code }),
  }).then((result) => ({ codes: result.backupCodes }));
}

export function disableMfa(request: { currentPassword: string; code?: string }): Promise<void> {
  return apiData<void>("/api/v1/auth/mfa/disable", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export type PasskeyCredentialSummary = {
  id: string;
  displayName: string;
  createdAt: string;
  lastUsedAt: string | null;
  backupEligible: boolean;
  backedUp: boolean;
};

export function listPasskeys(): Promise<PasskeyCredentialSummary[]> {
  return apiData<PasskeyCredentialSummary[]>("/api/v1/auth/passkeys");
}

export function beginPasskeyRegistration(): Promise<PasskeyOptions> {
  return apiData<PasskeyOptions>("/api/v1/auth/passkeys/registration/options", { method: "POST" });
}

export function completePasskeyRegistration(
  challengeId: string,
  displayName: string,
  credential: PasskeyCredentialJson,
): Promise<PasskeyCredentialSummary> {
  return apiData<PasskeyCredentialSummary>("/api/v1/auth/passkeys/registration/verify", {
    method: "POST",
    body: JSON.stringify({ challengeId, displayName, credential }),
  });
}

export function removePasskey(id: string, currentPassword: string): Promise<void> {
  return apiData<void>(`/api/v1/auth/passkeys/${encodeURIComponent(id)}`, {
    method: "DELETE",
    body: JSON.stringify({ currentPassword }),
  });
}

export function beginPasswordlessPasskeyLogin(organizationId?: string): Promise<PasskeyOptions> {
  return apiData<PasskeyOptions>("/api/v1/auth/passkeys/login/options", {
    method: "POST",
    body: JSON.stringify(organizationId ? { organizationId } : {}),
    anonymous: true,
    skipRefresh: true,
  });
}

export function completePasswordlessPasskeyLogin(
  challengeId: string,
  credential: PasskeyCredentialJson,
): Promise<AuthSession> {
  return apiData<AuthSession>("/api/v1/auth/passkeys/login/verify", {
    method: "POST",
    body: JSON.stringify({ challengeId, credential }),
    anonymous: true,
    skipRefresh: true,
  });
}

export function beginPasskeyMfa(
  email: string,
  password: string,
  organizationId?: string,
): Promise<PasskeyOptions> {
  return apiData<PasskeyOptions>("/api/v1/auth/passkeys/mfa/options", {
    method: "POST",
    body: JSON.stringify({ email, password, ...(organizationId ? { organizationId } : {}) }),
    anonymous: true,
    skipRefresh: true,
  });
}

export function completePasskeyMfa(
  challengeId: string,
  credential: PasskeyCredentialJson,
): Promise<AuthSession> {
  return apiData<AuthSession>("/api/v1/auth/passkeys/mfa/verify", {
    method: "POST",
    body: JSON.stringify({ challengeId, credential }),
    anonymous: true,
    skipRefresh: true,
  });
}

export type { RefreshRequest };
