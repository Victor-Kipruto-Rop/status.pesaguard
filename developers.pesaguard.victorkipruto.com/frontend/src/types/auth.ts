/**
 * Types mirroring the backend contract exactly.
 *
 * <p>The envelope is `{ data, requestId }` for success and
 * `{ error: { code, message, requestId, timestamp, violations } }` for failure.
 * These are the shapes `GlobalExceptionHandler` and `ApiResponse` actually emit,
 * not a guess — an envelope that drifts is a frontend that silently reads
 * `undefined` and renders an empty screen instead of an error.
 */

export type ApiViolation = {
  field: string;
  message: string;
};

export type ApiErrorBody = {
  code: string;
  message: string;
  requestId: string;
  timestamp: string;
  violations: ApiViolation[];
  /**
   * Organizations offered when the code is ORGANIZATION_SELECTION_REQUIRED.
   *
   * <p>Optional because the backend only sends it for that one challenge. Treated
   * as possibly absent rather than defaulted to `[]` at the type level, so a page
   * that renders a picker cannot silently render an empty one and look like a bug
   * in the chooser rather than a missing field.
   */
  selectableOrganizations?: SelectableOrganization[];
  verificationExpiresAt?: string | null;
  verificationResendAvailableAt?: string | null;
  verificationEmail?: string | null;
  mfaEnrollmentToken?: string | null;
  mfaEnrollmentExpiresAt?: string | null;
  loginChallengeId?: string | null;
  loginChallengeExpiresAt?: string | null;
  loginChallengeResendAvailableAt?: string | null;
  maskedLoginEmail?: string | null;
};

/**
 * One workspace offered on a sign-in challenge.
 *
 * <p>Carries a name and slug as well as an id: a chooser showing bare UUIDs is not
 * something a person can choose from.
 */
export type SelectableOrganization = {
  id: string;
  name: string;
  slug: string;
  role: string;
};

export type ApiEnvelope<T> = {
  data: T;
  requestId: string;
};

export type ApiErrorEnvelope = {
  error: ApiErrorBody;
};

export type AuthUser = {
  id: string;
  email: string;
  displayName: string;
};

export type AuthOrganization = {
  id: string;
  name: string;
  slug: string;
};

export type WorkspaceSummary = {
  id: string;
  name: string;
  slug: string;
  role: string;
  current: boolean;
  status: string;
};

/** Returned by `/auth/login` and `/auth/refresh`. */
export type AuthSession = {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  user: AuthUser;
  organization: AuthOrganization;
};

export type RegistrationResponse = {
  email: string;
  organizationName: string;
  verificationRequired: boolean;
  verificationExpiresAt?: string | null;
  verificationResendAvailableAt?: string | null;
  serverNow?: string | null;
  username?: string;
};

export type DeveloperOnboardingStatus = {
  emailVerified: boolean;
  profileReady: boolean;
  organizationReady: boolean;
  projectReady: boolean;
  environmentReady: boolean;
  nextStep: "profile" | "organization" | "project" | "environment" | "complete";
  complete: boolean;
  wizardStarted: boolean;
  onboardingComplete: boolean;
  skipped: boolean;
  currentStep: "welcome" | "organization" | "project" | "environment" | "api-key"
    | "first-request" | "api-explorer" | "webhook" | "documentation"
    | "production-readiness" | "complete" | "profile";
  completedSteps: string[];
  recommendations: string[];
  organizationName: string;
  organizationDescription: string;
  projectId: string | null;
  projectName: string | null;
  environmentId: string | null;
  environmentName: string | null;
};

export type DeveloperOnboardingStep = DeveloperOnboardingStatus["currentStep"];

export type DeveloperOnboardingWorkflowStatus =
  | "not_started"
  | "in_progress"
  | "skipped"
  | "completed";

export type DeveloperOnboardingStepState = {
  key: string;
  status: "not_started" | "in_progress" | "completed" | "skipped" | "blocked";
  required: boolean;
  conditional: boolean;
  blockedReason: string | null;
  startedAt: string | null;
  completedAt: string | null;
  skippedAt: string | null;
};

export type DeveloperOnboardingWorkflow = {
  status: DeveloperOnboardingWorkflowStatus;
  currentStep: string;
  progress: {
    completed: number;
    total: number;
    percentage: number;
  };
  steps: DeveloperOnboardingStepState[];
  version: number;
};

/** Returned by `/auth/session`. */
export type CurrentSession = {
  sessionId: string;
  user: AuthUser;
  organizationId: string;
  authorities: string[];
};

export type SessionSummary = {
  id: string;
  deviceLabel: string;
  lastIp: string | null;
  lastSeenAt: string;
  expiresAt: string;
  current: boolean;
};

export type MfaStatus = { enabled: boolean; organizationRequired: boolean };

export type MfaEnrolment = {
  secret: string;
  provisioningUri: string;
};

export type BackupCodes = { codes: string[]; session?: AuthSession | null };

export type LoginRequest = {
  email: string;
  password: string;
  organizationId?: string;
};

export type LoginEmailMfaChallenge = {
  challengeId: string;
  expiresAt: string;
  resendAvailableAt: string;
  maskedEmail: string;
};

export type RegisterRequest = {
  email: string;
  password: string;
  displayName: string;
  organizationName: string;
  organizationDescription?: string;
  termsAccepted: boolean;
  username?: string;
  phoneNumber?: string;
};

export type ForgotPasswordRequest = { email: string };

export type ResetPasswordRequest = {
  token: string;
  newPassword: string;
};

export type ChangePasswordRequest = {
  currentPassword: string;
  newPassword: string;
};

export type MfaCodeRequest = { code: string };

export type RefreshRequest = { refreshToken: string };

/** Authentication state machine. Mirrors the backend's login outcomes. */
export type AuthStatus =
  | "checking"
  | "guest"
  | "authenticating"
  | "email_mfa_required"
  | "unverified_email"
  | "authenticated";

/**
 * Why sign-in has not produced a session yet.
 *
 * <p>These are not interchangeable failures. Email MFA means the password was
 * correct and one email code completes the login; EMAIL_NOT_VERIFIED means the
 * address must be verified before password login is allowed. The backend
 * chooses the last-accessed active workspace without asking the user to pick it.
 */
export type AuthChallenge =
  | { kind: "email_mfa_required"; challenge: LoginEmailMfaChallenge }
  | { kind: "email_not_verified"; email: string }
  | null;
