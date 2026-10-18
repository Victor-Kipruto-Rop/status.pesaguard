import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { ArrowRight, Check, CheckCircle2, CircleHelp, Copy, Eye, EyeOff, KeyRound, LoaderCircle, LockKeyhole, Mail, ShieldCheck, Users } from "lucide-react";
import { useAuth } from "../../context/AuthContext";
import { ApiError, apiData, apiFetch, safeUserErrorMessage } from "../../lib/api";
import { copyTextToClipboard } from "../../lib/clipboard";
import * as authApi from "../../lib/authApi";
import type { DeveloperOnboardingStatus, DeveloperOnboardingStep, LoginEmailMfaChallenge } from "../../types/auth";
import "../../styles/onboarding.css";

type Step = "auth" | "verify" | "login-mfa" | "forgot" | "reset" | "locked" | "invitation" | "security" | "profile" | "welcome" | "organization" | "project" | "environment" | "api-key" | "first-request" | "api-explorer" | "webhook" | "documentation" | "production-readiness" | "workspaceReady" | "mfa-enroll";
type Template = "payments" | "events" | "risk";
type VerificationStatus = "idle" | "verifying" | "success" | "error" | "expired" | "locked" | "sending" | "sent";

type BootstrapResult = {
  project: { id: string; name: string; slug: string };
  environment?: { id: string; projectId: string; name: string; type: string; baseUrl: string };
  apiKey?: { id: string; name: string; key: string; prefix: string; scopes: string[]; expiresAt: string; baseUrl: string };
  template: Template;
  firstEndpoint: string;
};
const EMAIL_VERIFICATION_CODE_LENGTH = 6;
const stages = ["Welcome", "Organization", "Project", "Environment", "API key", "First request", "API Explorer", "Webhook", "Docs", "Readiness"];

const wizardStepOrder: DeveloperOnboardingStep[] = [
  "welcome", "organization", "project", "environment", "api-key", "first-request",
  "api-explorer", "webhook", "documentation", "production-readiness", "complete",
];

function redactEmailAddress(emailAddress: string): string {
  const [localPart, domain] = emailAddress.trim().split("@");
  if (!localPart || !domain) return "your email address";
  return `${localPart[0]}•••@${domain}`;
}

function invitationActionError(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) {
    return safeUserErrorMessage(error, fallback);
  }

  switch (error.code) {
    case "INVITATION_EMAIL_MISMATCH":
      return "This invitation was sent to a different email address. Sign in with the invited account to continue.";
    case "INVITATION_EMAIL_NOT_VERIFIED":
      return "Verify the invited email address before accepting this invitation.";
    case "INVITATION_ACCOUNT_DEACTIVATED":
      return "Reactivate your developer account before accepting this invitation.";
    case "INVITATION_ACCOUNT_SUSPENDED":
      return "Your developer account is suspended. Contact your organization administrator for help.";
    case "INVITATION_ACCOUNT_PENDING_DELETION":
      return "Cancel the pending account deletion before accepting this invitation.";
    case "INVITATION_ACCOUNT_DELETED":
      return "This developer account has been deleted. Create a new account to continue.";
    case "MEMBER_ALREADY_EXISTS":
      return "You are already a member of this organization. Switch to it from your workspace menu.";
    default:
      return safeUserErrorMessage(error, fallback);
  }
}

export function DeveloperOnboardingPage({
  onComplete,
  setupRequired = false,
  gateError = "",
  initialMode = "signup",
  initialStep,
  nextStep,
  onboardingStatus,
}: {
  onComplete: () => Promise<boolean>;
  setupRequired?: boolean;
  gateError?: string;
  initialMode?: "signup" | "signin";
  initialStep?: "verify" | "forgot" | "reset" | "locked" | "invitation";
  nextStep?: "profile" | "organization" | "project" | "environment" | "complete";
  onboardingStatus?: DeveloperOnboardingStatus;
}) {
  const { login, verifyLoginEmailMfa, completeRegistrationVerification, completeRegistrationVerificationByLink, resendLoginEmailMfa, register, reload, status, challenge: authChallenge, organization, user, hasPermission, isAuthenticated, switchWorkspace } = useAuth();
  const [invitationToken] = useState(() => (initialStep === "invitation" || window.location.pathname.replace(/\/$/, "") === "/accept-invitation"
    ? new URLSearchParams(window.location.search).get("token")
    : null)
    || new URLSearchParams(window.location.hash.slice(1)).get("invitation"));
  const verificationToken = new URLSearchParams(window.location.hash.slice(1)).get("verify-email")
    || (window.location.pathname === "/verify-email" ? new URLSearchParams(window.location.search).get("token") : null);
  const resetToken = new URLSearchParams(window.location.search).get("token")
    || new URLSearchParams(window.location.hash.slice(1)).get("reset-password");
  const restoredBootstrap: BootstrapResult | null = onboardingStatus?.projectId && onboardingStatus.projectName
    ? {
        project: { id: onboardingStatus.projectId, name: onboardingStatus.projectName, slug: "" },
        ...(onboardingStatus.environmentId ? {
          environment: {
            id: onboardingStatus.environmentId,
            projectId: onboardingStatus.projectId,
            name: onboardingStatus.environmentName ?? "Sandbox",
            type: "SANDBOX",
            baseUrl: "",
          },
        } : {}),
        template: "payments",
        firstEndpoint: "/api/v1/sandbox/transactions",
      }
    : null;
  const [step, setStep] = useState<Step>(() => {
    if (resetToken) return "reset";
    if (status === "authenticated" && setupRequired) {
      if (onboardingStatus?.wizardStarted) {
        if (onboardingStatus.currentStep === "first-request" && !restoredBootstrap?.apiKey) return "api-key";
        return onboardingStatus.currentStep === "complete" ? "workspaceReady" : onboardingStatus.currentStep;
      }
      if (nextStep === "profile") return "profile";
      if (nextStep === "organization") return "organization";
      if (nextStep === "project") return "project";
      if (nextStep === "environment") return "environment";
      return "welcome";
    }

    return invitationToken ? "invitation" : verificationToken ? "verify"
      : status === "email_mfa_required" ? "login-mfa" : initialStep ?? "auth";
  });
  const [authMode, setAuthMode] = useState<"signup" | "signin">(initialMode);
  const [displayName, setDisplayName] = useState(user?.displayName ?? "");
  const [newOrganizationName, setNewOrganizationName] = useState(onboardingStatus?.organizationName ?? organization?.name ?? "");
  const [newOrganizationDescription, setNewOrganizationDescription] = useState(onboardingStatus?.organizationDescription ?? "");
  const [email, setEmail] = useState("");
  const [verificationDigits, setVerificationDigits] = useState<string[]>(
    () => Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, () => ""),
  );
  const [verificationStatus, setVerificationStatus] = useState<VerificationStatus>("idle");
  const [verificationExpiresAt, setVerificationExpiresAt] = useState<number | null>(null);
  const [resendAvailableAt, setResendAvailableAt] = useState<number | null>(null);
  const [clockNow, setClockNow] = useState(() => Date.now());
  const [invitationPreview, setInvitationPreview] = useState<authApi.InvitationPreview | null>(null);
  const [invitationAccepted, setInvitationAccepted] = useState(false);
  const [invitationOrganizationId, setInvitationOrganizationId] = useState("");
  const [invitationDeclined, setInvitationDeclined] = useState(false);
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [loginMfaChallenge, setLoginMfaChallenge] = useState<LoginEmailMfaChallenge | null>(
    () => authChallenge?.kind === "email_mfa_required" ? authChallenge.challenge : null,
  );
  const [loginMfaDigits, setLoginMfaDigits] = useState<string[]>(
    () => Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, () => ""),
  );
  const [loginMfaStatus, setLoginMfaStatus] = useState<"idle" | "verifying" | "error" | "success">("idle");
  const [mfaEnrollmentToken, setMfaEnrollmentToken] = useState("");
  const [mfaEnrollment, setMfaEnrollment] = useState<{ secret: string; provisioningUri: string } | null>(null);
  const [mfaEnrollmentCode, setMfaEnrollmentCode] = useState("");
  const [mfaEnrollmentComplete, setMfaEnrollmentComplete] = useState(false);
  const [mfaRecoveryCodes, setMfaRecoveryCodes] = useState<string[]>([]);
  const [mfaRecoveryCodesCopied, setMfaRecoveryCodesCopied] = useState(false);
  const [mfaRecoveryCodesSaved, setMfaRecoveryCodesSaved] = useState(false);
  const [onboardingWorkflow, setOnboardingWorkflow] = useState<Awaited<ReturnType<typeof authApi.onboardingWorkflow>> | null>(null);
  const workflowLoaded = useRef(false);
  const [acceptedTerms, setAcceptedTerms] = useState(false);
  const [projectName, setProjectName] = useState("");
  const [template, setTemplate] = useState<Template>("payments");
  const [environmentName, setEnvironmentName] = useState("Sandbox");
  const [bootstrap, setBootstrap] = useState<BootstrapResult | null>(restoredBootstrap);
  const [firstRequestResult, setFirstRequestResult] = useState("");
  const [recommendations, setRecommendations] = useState(onboardingStatus?.recommendations ?? []);
  const [recoveryToken] = useState(resetToken);
  const [resetTokenExpired, setResetTokenExpired] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);
  const [verificationNotice, setVerificationNotice] = useState("");
  const verificationStarted = useRef(false);
  const verificationInFlight = useRef(false);
  const authForm = useRef<HTMLFormElement | null>(null);
  const verificationCodeInputs = useRef<Array<HTMLInputElement | null>>([]);
  const loginMfaCodeInputs = useRef<Array<HTMLInputElement | null>>([]);
  const loginMfaInFlight = useRef(false);
  const verificationCode = verificationDigits.join("");
  const loginMfaCode = loginMfaDigits.join("");
  const passwordMismatch = confirmPassword.length > 0 && confirmPassword !== password;
  const passwordMatch = confirmPassword.length > 0 && confirmPassword === password;
  const verificationSeconds = verificationExpiresAt === null
    ? 0
    : Math.max(0, Math.ceil((verificationExpiresAt - clockNow) / 1000));

  async function enterMfaEnrollment(token: string) {
    setMfaEnrollmentToken(token);
    setMfaRecoveryCodes([]);
    setMfaEnrollmentComplete(false);
    setError("");
    try {
      const enrollment = await authApi.beginMfaEnrollmentChallenge(token);
      setMfaEnrollment(enrollment);
      setMfaEnrollmentCode("");
      setStep("mfa-enroll");
      clearCredentialFields();
    } catch (enrollmentError) {
      setMfaEnrollmentToken("");
      setError(safeUserErrorMessage(enrollmentError, "Could not start MFA setup. Sign in again to request a new challenge."));
    }
  }

  async function confirmRequiredMfaEnrollment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!mfaEnrollment || !mfaEnrollmentCode.trim()) return;
    setBusy(true);
    setError("");
    try {
      const result = mfaEnrollmentToken
        ? await authApi.confirmMfaEnrollmentChallenge(mfaEnrollmentCode.trim(), mfaEnrollmentToken)
        : await authApi.confirmMfaEnrolment(mfaEnrollmentCode.trim());
      setMfaRecoveryCodes(result.codes);
      setMfaRecoveryCodesCopied(false);
      setMfaRecoveryCodesSaved(false);
      setMfaEnrollmentComplete(true);
      setMfaEnrollmentToken("");
      setMfaEnrollment(null);
      setMfaEnrollmentCode("");
    } catch (confirmationError) {
      setError(safeUserErrorMessage(confirmationError, "The authenticator code could not be verified."));
    } finally {
      setBusy(false);
    }
  }

  function startVerificationCountdown(
    expiresAt?: string | null,
    resendAt?: string | null,
    serverNow?: string | null,
  ) {
    const now = Date.now();
    const parsedExpiry = expiresAt ? Date.parse(expiresAt) : Number.NaN;
    const parsedResend = resendAt ? Date.parse(resendAt) : Number.NaN;
    const parsedServerNow = serverNow ? Date.parse(serverNow) : Number.NaN;
    const offset = Number.isFinite(parsedServerNow) ? now - parsedServerNow : 0;
    setVerificationExpiresAt(Number.isFinite(parsedExpiry)
      ? parsedExpiry + offset
      : now + 10 * 60 * 1000);
    setResendAvailableAt(Number.isFinite(parsedResend)
      ? parsedResend + offset
      : now + 60 * 1000);
    setClockNow(now);
    setVerificationDigits(Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, () => ""));
    setVerificationStatus("idle");
  }

  function focusVerificationCode(index = 0) {
    window.requestAnimationFrame(() => verificationCodeInputs.current[index]?.focus());
  }

  function clearVerificationCode() {
    setVerificationDigits(Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, () => ""));
  }

  async function verifyVerificationCode(code: string) {
    if (verificationInFlight.current || code.length !== EMAIL_VERIFICATION_CODE_LENGTH) return;
    if (verificationExpiresAt === null || verificationSeconds === 0) {
      setVerificationStatus("expired");
      return;
    }

    verificationInFlight.current = true;
    setBusy(true);
    setError("");
    setVerificationStatus("verifying");
    try {
      if (password) {
        await completeRegistrationVerification(email.trim(), code, password);
      } else {
        await authApi.verifyEmail(email.trim(), code);
      }
    } catch (verificationError) {
      const apiError = verificationError instanceof ApiError ? verificationError : null;
      if (apiError?.code === "EMAIL_OTP_EXPIRED" || apiError?.code === "EXPIRED") {
        const expiredAt = Date.now();
        setVerificationExpiresAt(expiredAt);
        setClockNow(expiredAt);
        setVerificationStatus("expired");
      } else if (apiError?.code === "RATE_LIMITED") {
        setVerificationStatus("error");
        setError(safeUserErrorMessage(apiError, "Too many requests. Wait a moment before trying again."));
        await new Promise<void>((resolve) => window.setTimeout(resolve, 520));
        setVerificationStatus("idle");
        focusVerificationCode(EMAIL_VERIFICATION_CODE_LENGTH - 1);
      } else if (apiError?.code === "EMAIL_OTP_ATTEMPTS_EXCEEDED"
        || apiError?.code === "TOO_MANY_ATTEMPTS" || apiError?.status === 429) {
        const expiredAt = Date.now();
        setVerificationExpiresAt(expiredAt);
        setClockNow(expiredAt);
        setVerificationStatus("locked");
        setError(safeUserErrorMessage(apiError, "Too many attempts. Request a new code to continue."));
      } else if (apiError?.code === "ALREADY_VERIFIED" || apiError?.code === "VERIFIED") {
        setVerificationStatus("success");
        setVerificationNotice("Your email is verified. Sign in to continue.");
        await new Promise<void>((resolve) => window.setTimeout(resolve, 650));
        setAuthMode("signin");
        setStep("auth");
      } else if (apiError?.code === "EMAIL_OTP_INVALID" || apiError?.code === "EMAIL_VERIFICATION_INVALID"
        || apiError?.code === "INVALID_CODE") {
        setVerificationStatus("error");
        setError("Invalid verification code. Check the code and try again.");
        await new Promise<void>((resolve) => window.setTimeout(resolve, 520));
        clearVerificationCode();
        setVerificationStatus("idle");
        focusVerificationCode();
      } else if (apiError?.code === "SESSION_EXPIRED") {
        setVerificationStatus("error");
        setError("Your verification session has expired. Sign in again to continue.");
        await new Promise<void>((resolve) => window.setTimeout(resolve, 520));
        clearCredentialFields();
        setVerificationNotice("Your verification session has expired. Sign in again to continue.");
        setError("");
        setAuthMode("signin");
        setStep("auth");
      } else {
        setVerificationStatus("error");
        setError(apiError?.status && apiError.status >= 500
          ? "The verification service is temporarily unavailable. Please try again."
          : safeUserErrorMessage(verificationError, "We could not verify this code. Please try again."));
        await new Promise<void>((resolve) => window.setTimeout(resolve, 520));
        setVerificationStatus("idle");
        focusVerificationCode(EMAIL_VERIFICATION_CODE_LENGTH - 1);
      }
      setBusy(false);
      verificationInFlight.current = false;
      return;
    }

    setVerificationStatus("success");
    setVerificationNotice("Your email is verified. Continuing securely…");
    await new Promise<void>((resolve) => window.setTimeout(resolve, 650));
    if (!password) {
      setVerificationNotice("Your email is verified. Sign in to continue.");
      setAuthMode("signin");
      setStep("auth");
      setBusy(false);
      verificationInFlight.current = false;
      return;
    }
    clearCredentialFields();
    if (invitationToken) {
      setStep("invitation");
      setBusy(false);
      verificationInFlight.current = false;
      return;
    }
    await onComplete();
    setBusy(false);
    verificationInFlight.current = false;
  }

  function updateVerificationDigit(index: number, value: string) {
    if (busy || verificationStatus === "success" || verificationStatus === "locked"
      || verificationExpiresAt === null || verificationSeconds === 0) return;
    const digits = value.replace(/\D/g, "");
    if (!digits) {
      setVerificationDigits((current) => current.map((digit, position) => position === index ? "" : digit));
      return;
    }

    if (digits.length > 1) {
      const nextDigits = Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, (_, position) => digits[position] ?? "");
      setVerificationDigits(nextDigits);
      setError("");
      if (digits.length >= EMAIL_VERIFICATION_CODE_LENGTH) {
        void verifyVerificationCode(nextDigits.join(""));
      } else {
        verificationCodeInputs.current[Math.min(digits.length, EMAIL_VERIFICATION_CODE_LENGTH) - 1]?.focus();
      }
      return;
    }

    const nextDigits = verificationDigits.map((digit, position) => position === index ? digits : digit);
    setVerificationDigits(nextDigits);
    setError("");
    if (nextDigits.every(Boolean)) {
      void verifyVerificationCode(nextDigits.join(""));
      return;
    }
    if (index < EMAIL_VERIFICATION_CODE_LENGTH - 1) {
      verificationCodeInputs.current[index + 1]?.focus();
    }
  }

  function pasteVerificationCode(event: React.ClipboardEvent<HTMLInputElement>) {
    const digits = event.clipboardData.getData("text").replace(/\D/g, "").slice(0, EMAIL_VERIFICATION_CODE_LENGTH);
    if (!digits) return;

    event.preventDefault();
    if (busy || verificationStatus === "success" || verificationStatus === "locked"
      || verificationExpiresAt === null || verificationSeconds === 0) return;
    const nextDigits = Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, (_, index) => digits[index] ?? "");
    setVerificationDigits(nextDigits);
    setError("");
    if (digits.length === EMAIL_VERIFICATION_CODE_LENGTH) {
      void verifyVerificationCode(nextDigits.join(""));
    } else {
      verificationCodeInputs.current[digits.length - 1]?.focus();
    }
  }

  function handleVerificationCodeKeyDown(event: React.KeyboardEvent<HTMLInputElement>, index: number) {
    if (event.key === "ArrowLeft" && index > 0) {
      event.preventDefault();
      verificationCodeInputs.current[index - 1]?.focus();
    } else if (event.key === "ArrowRight" && index < EMAIL_VERIFICATION_CODE_LENGTH - 1) {
      event.preventDefault();
      verificationCodeInputs.current[index + 1]?.focus();
    } else if (event.key === "Backspace") {
      event.preventDefault();
      if (verificationDigits[index]) {
        setVerificationDigits((current) => current.map((digit, position) => position === index ? "" : digit));
      } else if (index > 0) {
        setVerificationDigits((current) => current.map((digit, position) => position === index - 1 ? "" : digit));
        verificationCodeInputs.current[index - 1]?.focus();
      }
    } else if (event.key === "Delete") {
      event.preventDefault();
      setVerificationDigits((current) => current.map((digit, position) => position === index ? "" : digit));
    }
  }

  useEffect(() => {
    if (step !== "verify" && step !== "login-mfa") return;
    const timer = window.setInterval(() => setClockNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [step]);

  useEffect(() => {
    if (status !== "authenticated" || !setupRequired || workflowLoaded.current) return;
    workflowLoaded.current = true;
    let active = true;
    void authApi.onboardingWorkflow()
      .then(async (workflow) => {
        if (!active) return;
        setOnboardingWorkflow(workflow);
        const stepByKey: Record<string, Step> = {
          PROFILE: "profile",
          ORGANIZATION: "organization",
          PROJECT: "project",
          SANDBOX: "environment",
          API_KEY: "api-key",
          FIRST_API_REQUEST: "first-request",
          API_EXPLORER: "api-explorer",
          WEBHOOK: "webhook",
          DOCUMENTATION: "documentation",
          PRODUCTION_READINESS: "production-readiness",
          COMPLETION: "workspaceReady",
        };
        const persistedStep = stepByKey[workflow.currentStep];
        if (persistedStep) setStep(persistedStep);
        if (workflow.currentStep !== "SECURITY") return;
        const mfa = await authApi.mfaStatus();
        if (!active) return;
        if (mfa.enabled) {
          await authApi.completeOnboardingStep("SECURITY");
          await authApi.startOnboardingStep("PROFILE");
          setStep("profile");
          return;
        }
        const enrollment = await authApi.beginMfaEnrolment();
        if (!active) return;
        setMfaEnrollmentComplete(false);
        setMfaRecoveryCodes([]);
        setMfaEnrollment(enrollment);
        setMfaEnrollmentCode("");
        setStep("security");
      })
      .catch((workflowError: unknown) => {
        if (active) setError(getUserMessage(workflowError, "Your onboarding progress could not be loaded."));
      });
    return () => { active = false; };
  }, [status, setupRequired]);

  useEffect(() => {
    if (step !== "verify" || verificationExpiresAt === null || verificationSeconds > 0
      || verificationStatus === "success" || verificationStatus === "locked"
      || verificationStatus === "expired") return;
    setVerificationStatus("expired");
    setError("");
    clearVerificationCode();
  }, [step, verificationExpiresAt, verificationSeconds, verificationStatus]);

  useEffect(() => {
    if (step !== "verify" || verificationExpiresAt === null || verificationStatus === "success") return;
    focusVerificationCode();
  }, [step, verificationExpiresAt]);

  useEffect(() => {
    if (verificationToken || resetToken || invitationToken) {
      window.history.replaceState(null, "", window.location.pathname);
    }
    if (!verificationToken || verificationStarted.current) return;
    verificationStarted.current = true;
    setBusy(true);
    void completeRegistrationVerificationByLink(verificationToken)
      .then(() => {
        if (invitationToken) setStep("invitation");
        else void onComplete();
      })
      .catch((verificationError: unknown) => {
        setError(getUserMessage(verificationError, "This verification link is invalid or has expired."));
      })
      .finally(() => setBusy(false));
  }, [completeRegistrationVerificationByLink, invitationToken, onComplete, verificationToken]);

  useEffect(() => {
    if (!invitationToken) return;
    let active = true;
    void authApi.previewInvitation(invitationToken)
      .then((preview) => {
        if (active) setInvitationPreview(preview);
      })
      .catch((previewError: unknown) => {
        if (active) setError(getUserMessage(previewError, "This invitation could not be loaded. Ask the organization administrator to send it again."));
      });
    return () => { active = false; };
  }, [invitationToken]);

  function clearCredentialFields() {
    setPassword("");
    setConfirmPassword("");
    setShowPassword(false);
    setShowConfirmPassword(false);
  }

  function changeAuthMode(mode: "signup" | "signin") {
    setAuthMode(mode);
    clearCredentialFields();
    setLoginMfaChallenge(null);
    setLoginMfaDigits(Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, () => ""));
    setVerificationNotice("");
    setError("");
  }

  function captureLoginMfa(loginError: unknown): boolean {
    if (!(loginError instanceof ApiError) || loginError.code !== "LOGIN_EMAIL_MFA_REQUIRED"
      || !loginError.loginChallengeId || !loginError.loginChallengeExpiresAt
      || !loginError.loginChallengeResendAvailableAt || !loginError.maskedLoginEmail) {
      return false;
    }
    setLoginMfaChallenge({
      challengeId: loginError.loginChallengeId,
      expiresAt: loginError.loginChallengeExpiresAt,
      resendAvailableAt: loginError.loginChallengeResendAvailableAt,
      maskedEmail: loginError.maskedLoginEmail,
    });
    setLoginMfaDigits(Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, () => ""));
    setLoginMfaStatus("idle");
    clearCredentialFields();
    setError("");
    setStep("login-mfa");
    return true;
  }

  async function submitAuth(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    if (authMode === "signup" && password !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }
    if (authMode === "signup" && !acceptedTerms) {
      setError("Accept the terms to create an account.");
      return;
    }
    setBusy(true);
    try {
      if (authMode === "signup") {
        setStep("organization");
      } else {
        await login(email.trim(), password);
        clearCredentialFields();
        setStep(invitationToken ? "invitation" : "welcome");
      }

    } catch (authError) {
      if (captureLoginMfa(authError)) {
        return;
      } else if (authError instanceof ApiError && authError.code === "MFA_ENROLLMENT_REQUIRED"
        && authError.mfaEnrollmentToken) {
        await enterMfaEnrollment(authError.mfaEnrollmentToken);
      } else if (authError instanceof ApiError && (authError.code === "RATE_LIMITED" || authError.status === 429)) {
        setError(safeUserErrorMessage(authError, "Too many sign-in requests. Wait a moment before trying again."));
      } else if (authError instanceof ApiError && authError.code === "EMAIL_NOT_VERIFIED") {
        setEmail(authError.verificationEmail || email.trim());
        startVerificationCountdown(
          authError.verificationExpiresAt,
          authError.verificationResendAvailableAt,
          authError.timestamp,
        );
        setVerificationNotice(`Enter the six-digit verification code sent to ${redactEmailAddress(email)}.`);
        setStep("verify");
      } else {
        setError(safeUserErrorMessage(authError, "We could not complete authentication."));
      }
    } finally {
      setBusy(false);
    }
  }

  async function submitOrganizationStep(event: React.FormEvent<HTMLFormElement>) {
    if (status === "authenticated") {
      await saveOrganization(event);
      return;
    }
    event.preventDefault();
    if (password !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }
    if (!acceptedTerms) {
      setError("Accept the terms to create an account.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const result = await register(
        email.trim(),
        password,
        displayName.trim(),
        newOrganizationName.trim(),
        newOrganizationDescription.trim() || undefined,
        acceptedTerms,
      );
      setEmail(result.email);
      if (result.verificationRequired) {
        startVerificationCountdown(
          result.verificationExpiresAt,
          result.verificationResendAvailableAt,
          result.serverNow,
        );
        setVerificationNotice(
          `Enter the six-digit verification code sent to ${redactEmailAddress(result.email)} to finish creating ${result.organizationName}.`,
        );
        setStep("verify");
      } else {
        setVerificationNotice(
          `Your organization ${result.organizationName} is ready. Sign in with your email address.`,
        );
        setAuthMode("signin");
      }
    } catch (registrationError) {
      setError(safeUserErrorMessage(registrationError, "Registration failed. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  async function submitVerificationCode(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void verifyVerificationCode(verificationCode);
  }

  async function submitLoginEmailMfa(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await verifyLoginEmailMfaCode(loginMfaCode);
  }

  async function verifyLoginEmailMfaCode(code: string) {
    if (!loginMfaChallenge || code.length !== EMAIL_VERIFICATION_CODE_LENGTH || busy || loginMfaInFlight.current
      || Date.parse(loginMfaChallenge.expiresAt) <= Date.now()) return;
    loginMfaInFlight.current = true;
    setBusy(true);
    setError("");
    setLoginMfaStatus("verifying");
    try {
      await verifyLoginEmailMfa(loginMfaChallenge.challengeId, code);
      setLoginMfaStatus("success");
      setLoginMfaChallenge(null);
      setLoginMfaDigits(Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, () => ""));
      clearCredentialFields();
      setStep(invitationToken ? "invitation" : "welcome");
    } catch (verificationError) {
      setLoginMfaStatus("error");
      setError(safeUserErrorMessage(verificationError, "We could not verify that sign-in code."));
    } finally {
      loginMfaInFlight.current = false;
      setBusy(false);
    }
  }

  async function resendLoginEmailCode() {
    if (!loginMfaChallenge || loginMfaResendSeconds > 0 || busy) return;
    setBusy(true);
    setError("");
    try {
      const challenge = await resendLoginEmailMfa(loginMfaChallenge.challengeId);
      setLoginMfaChallenge(challenge);
      setLoginMfaDigits(Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, () => ""));
      setLoginMfaStatus("idle");
      setError("");
      setClockNow(Date.now());
    } catch (resendError) {
      setLoginMfaStatus("error");
      setError(safeUserErrorMessage(resendError, "We could not send another sign-in code."));
    } finally {
      setBusy(false);
    }
  }

  function updateLoginMfaDigit(index: number, value: string) {
    if (busy || !loginMfaChallenge || Date.parse(loginMfaChallenge.expiresAt) <= clockNow) return;
    const digits = value.replace(/\D/g, "");
    if (!digits) {
      setLoginMfaDigits((current) => current.map((digit, position) => position === index ? "" : digit));
      setLoginMfaStatus("idle");
      setError("");
      return;
    }

    if (digits.length > 1) {
      const nextDigits = Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, (_, position) => digits[position] ?? "");
      setLoginMfaDigits(nextDigits);
      setLoginMfaStatus("idle");
      setError("");
      if (nextDigits.every(Boolean)) {
        void verifyLoginEmailMfaCode(nextDigits.join(""));
      } else {
        loginMfaCodeInputs.current[Math.min(digits.length, EMAIL_VERIFICATION_CODE_LENGTH) - 1]?.focus();
      }
      return;
    }

    const nextDigits = loginMfaDigits.map((digit, position) => position === index ? digits : digit);
    setLoginMfaDigits(nextDigits);
    setLoginMfaStatus("idle");
    setError("");
    if (nextDigits.every(Boolean)) {
      void verifyLoginEmailMfaCode(nextDigits.join(""));
      return;
    }
    if (index < EMAIL_VERIFICATION_CODE_LENGTH - 1) {
      loginMfaCodeInputs.current[index + 1]?.focus();
    }
  }

  function pasteLoginMfaCode(event: React.ClipboardEvent<HTMLInputElement>) {
    const digits = event.clipboardData.getData("text").replace(/\D/g, "").slice(0, EMAIL_VERIFICATION_CODE_LENGTH);
    if (!digits) return;
    event.preventDefault();
    if (busy || !loginMfaChallenge || Date.parse(loginMfaChallenge.expiresAt) <= clockNow) return;
    const nextDigits = Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, (_, index) => digits[index] ?? "");
    setLoginMfaDigits(nextDigits);
    setLoginMfaStatus("idle");
    setError("");
    if (nextDigits.every(Boolean)) {
      void verifyLoginEmailMfaCode(nextDigits.join(""));
    } else {
      loginMfaCodeInputs.current[digits.length - 1]?.focus();
    }
  }

  function handleLoginMfaCodeKeyDown(event: React.KeyboardEvent<HTMLInputElement>, index: number) {
    if (event.key === "ArrowLeft" && index > 0) {
      event.preventDefault();
      loginMfaCodeInputs.current[index - 1]?.focus();
    } else if (event.key === "ArrowRight" && index < EMAIL_VERIFICATION_CODE_LENGTH - 1) {
      event.preventDefault();
      loginMfaCodeInputs.current[index + 1]?.focus();
    } else if (event.key === "Backspace") {
      event.preventDefault();
      if (loginMfaDigits[index]) {
        setLoginMfaDigits((current) => current.map((digit, position) => position === index ? "" : digit));
      } else if (index > 0) {
        setLoginMfaDigits((current) => current.map((digit, position) => position === index - 1 ? "" : digit));
        loginMfaCodeInputs.current[index - 1]?.focus();
      }
    } else if (event.key === "Delete") {
      event.preventDefault();
      setLoginMfaDigits((current) => current.map((digit, position) => position === index ? "" : digit));
    }
  }

  async function resendVerificationCode() {
    if (resendSeconds > 0 || busy || !email.trim()) return;
    setBusy(true);
    setError("");
    setVerificationStatus("sending");
    try {
      await authApi.resendEmailVerification(email.trim());
      startVerificationCountdown();
      setVerificationNotice(`If this account is pending verification, a new code has been sent to ${redactEmailAddress(email)}.`);
      setVerificationStatus("sent");
      focusVerificationCode();
      window.setTimeout(() => setVerificationStatus((status) => status === "sent" ? "idle" : status), 1500);
    } catch (resendError) {
      setVerificationStatus("idle");
      setError(safeUserErrorMessage(resendError, "We could not request a new code."));
    } finally {
      setBusy(false);
    }
  }

  async function acceptInvitation() {
    if (!invitationToken) {
      setError("This invitation link is missing its token. Ask the organization administrator to send a new invitation.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const result = await authApi.acceptInvitation(invitationToken);
      if (!result.organizationId) throw new Error("The invitation was accepted, but no organization was returned.");
      setInvitationAccepted(true);
      setInvitationOrganizationId(result.organizationId);
      window.history.replaceState(null, "", "/");
      await switchWorkspace(result.organizationId);
    } catch (invitationError) {
      setError(invitationActionError(invitationError,
        "We could not accept this invitation. Check the link and sign in with the invited email address."));
    } finally {
      setBusy(false);
    }
  }

  async function declineInvitation() {
    if (!invitationToken) return;
    setBusy(true);
    setError("");
    try {
      await authApi.declineInvitation(invitationToken);
      setInvitationDeclined(true);
    } catch (declineError) {
      setError(invitationActionError(declineError, "We could not decline this invitation."));
    } finally {
      setBusy(false);
    }
  }

  async function createProject(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const result = await apiData<BootstrapResult>("/api/v1/onboarding/bootstrap", {
        method: "POST",
        body: JSON.stringify({ projectName: projectName.trim(), template }),
      });
      setBootstrap(result);
      await authApi.completeOnboardingStep("PROJECT");
      await authApi.startOnboardingStep("SANDBOX");
      await authApi.updateOnboardingProgress("environment", "project");
      setStep("environment");
    } catch (bootstrapError) {
      setError(safeUserErrorMessage(bootstrapError, "Project setup failed. You can safely retry."));
    } finally {
      setBusy(false);
    }
  }

  async function saveOrganization(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await authApi.updateOnboardingOrganization(newOrganizationName.trim(), newOrganizationDescription.trim());
      await authApi.completeOnboardingStep("ORGANIZATION");
      await authApi.startOnboardingStep("PROJECT");
      await authApi.updateOnboardingProgress("project", "welcome");
      setStep("project");
    } catch (organizationError) {
      setError(safeUserErrorMessage(organizationError, "Organization details could not be saved."));
    } finally {
      setBusy(false);
    }
  }

  async function createEnvironment(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!bootstrap?.project.id) {
      setError("Create a project before setting up its environment.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const environment = await authApi.createOnboardingEnvironment(bootstrap.project.id, environmentName.trim());
      setBootstrap((current) => current ? { ...current, environment } : current);
      await authApi.completeOnboardingStep("SANDBOX");
      await authApi.startOnboardingStep("API_KEY");
      await authApi.updateOnboardingProgress("api-key", "environment");
      setStep("api-key");
    } catch (environmentError) {
      setError(safeUserErrorMessage(environmentError, "Environment setup failed."));
    } finally {
      setBusy(false);
    }
  }

  async function createFirstApiKey() {
    if (!bootstrap?.project.id || !bootstrap.environment?.id) {
      setError("Create a project and sandbox environment before issuing an API key.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const apiKey = await authApi.createOnboardingApiKey(bootstrap.project.id, bootstrap.environment.id);
      setBootstrap((current) => current ? { ...current, apiKey } : current);
      await authApi.completeOnboardingStep("API_KEY");
      await authApi.startOnboardingStep("FIRST_API_REQUEST");
      await authApi.updateOnboardingProgress("first-request", "api-key");
    } catch (keyError) {
      setError(safeUserErrorMessage(keyError, "The first API key could not be created. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  async function sendFirstRequest() {
    if (!bootstrap?.apiKey?.key) {
      setError("Create an API key before sending the first request.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const response = await apiFetch<unknown>(bootstrap.firstEndpoint, {
        method: "GET",
        authorizationToken: bootstrap.apiKey.key,
        baseUrl: bootstrap.apiKey.baseUrl,
      });
      setFirstRequestResult(JSON.stringify(response, null, 2));
      await authApi.completeOnboardingStep("FIRST_API_REQUEST");
      await authApi.startOnboardingStep("API_EXPLORER");
      await authApi.updateOnboardingProgress("api-explorer", "first-request");
      setStep("api-explorer");
    } catch (requestError) {
      setError(safeUserErrorMessage(requestError, "The first sandbox request failed. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  async function advanceWizard(nextStep: DeveloperOnboardingStep, completedStep: Exclude<DeveloperOnboardingStep, "profile" | "complete">) {
    setBusy(true);
    setError("");
    try {
      const workflowStep: Partial<Record<typeof completedStep, string>> = {
        "api-explorer": "API_EXPLORER",
        webhook: "WEBHOOK",
        documentation: "DOCUMENTATION",
        "production-readiness": "PRODUCTION_READINESS",
      };
      const completedWorkflowStep = workflowStep[completedStep];
      if (completedWorkflowStep) {
        await authApi.completeOnboardingStep(completedWorkflowStep);
      }
      await authApi.updateOnboardingProgress(nextStep, completedStep);
      setStep(nextStep === "complete" ? "workspaceReady" : nextStep);
    } catch (progressError) {
      setError(safeUserErrorMessage(progressError, "Onboarding progress could not be saved."));
    } finally {
      setBusy(false);
    }
  }

  async function finishOnboarding() {
    setBusy(true);
    setError("");
    try {
      const completed = await authApi.completeOnboarding();
      setRecommendations(completed.recommendations);
      setStep("workspaceReady");
    } catch (completionError) {
      setError(safeUserErrorMessage(completionError, "Onboarding could not be completed."));
    } finally {
      setBusy(false);
    }
  }

  async function confirmSecuritySetup() {
    setBusy(true);
    setError("");
    try {
      const workflow = await authApi.completeOnboardingStep("SECURITY");
      const nextWorkflow = await authApi.startOnboardingStep("PROFILE");
      setOnboardingWorkflow(nextWorkflow ?? workflow);
      setMfaRecoveryCodes([]);
      setStep("profile");
    } catch (securityError) {
      setError(getUserMessage(securityError, "Security setup could not be saved."));
    } finally {
      setBusy(false);
    }
  }

  async function skipSetup() {
    const optionalStepByPage: Partial<Record<Step, string>> = {
      "api-explorer": "API_EXPLORER",
      webhook: "WEBHOOK",
      documentation: "DOCUMENTATION",
    };
    const optionalStep = optionalStepByPage[step];
    if (!optionalStep) {
      setError("This onboarding step is required and cannot be skipped.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const workflow = await authApi.skipOnboardingStep(optionalStep);
      setOnboardingWorkflow(workflow);
      setStep(step === "api-explorer" ? "webhook" : step === "webhook" ? "documentation" : "production-readiness");
    } catch (skipError) {
      setError(safeUserErrorMessage(skipError, "Onboarding could not be skipped."));
    } finally {
      setBusy(false);
    }
  }

  async function saveProfile(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true); setError("");
    try {
      await authApi.updateOnboardingProfile(displayName.trim());
      await reload();
      await authApi.completeOnboardingStep("PROFILE");
      await authApi.startOnboardingStep("ORGANIZATION");
      await authApi.updateOnboardingProgress("welcome");
      setStep("welcome");
    } catch (profileError) {
      setError(safeUserErrorMessage(profileError, "We could not save your profile."));
    } finally { setBusy(false); }
  }

  async function copyKey() {
    if (!bootstrap?.apiKey?.key) return;
    try {
      await copyTextToClipboard(bootstrap.apiKey.key);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      setError("Clipboard access was blocked. Select and copy the key manually.");
    }
  }

  async function copyBaseUrl() {
    if (!bootstrap?.apiKey?.baseUrl) return;
    try {
      await copyTextToClipboard(bootstrap.apiKey.baseUrl);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      setError("Clipboard access was blocked. Select and copy the Base URL manually.");
    }
  }

  const resendSeconds = resendAvailableAt === null
    ? 0
    : Math.max(0, Math.ceil((resendAvailableAt - clockNow) / 1000));
  const verificationExpired = verificationStatus === "expired"
    || (verificationExpiresAt !== null && verificationSeconds === 0 && verificationStatus !== "success" && verificationStatus !== "locked");
  const verificationCodeDisabled = busy || verificationStatus === "success" || verificationStatus === "locked"
    || verificationExpired || verificationExpiresAt === null;
  const showVerificationInputs = !verificationNotice.startsWith("Your email") || verificationStatus === "success";
  const loginMfaResendSeconds = loginMfaChallenge
    ? Math.max(0, Math.ceil((Date.parse(loginMfaChallenge.resendAvailableAt) - clockNow) / 1000))
    : 0;
  const loginMfaExpiresAt = loginMfaChallenge ? Date.parse(loginMfaChallenge.expiresAt) : null;
  const loginMfaSeconds = loginMfaExpiresAt === null ? 0 : Math.max(0, Math.ceil((loginMfaExpiresAt - clockNow) / 1000));
  const loginMfaExpired = loginMfaExpiresAt !== null && loginMfaSeconds === 0;
  const activeStage = step === "workspaceReady"
    ? stages.length - 1
    : Math.max(0, wizardStepOrder.indexOf(step as DeveloperOnboardingStep));
  const accountFlow = step === "auth" || step === "forgot" || step === "reset" || step === "verify" || step === "login-mfa" || step === "locked" || step === "invitation";
  if (status === "checking") {
    return <main className="onboarding-loading" aria-live="polite"><LoaderCircle size={20} aria-hidden="true" /> Checking your secure session…</main>;
  }

  const copyMfaRecoveryCodes = async () => {
    try {
      await copyTextToClipboard(mfaRecoveryCodes.join("\n"));
      setMfaRecoveryCodesCopied(true);
    } catch {
      setError("Clipboard access is unavailable. Select and copy the recovery codes manually.");
    }
  };

  return (
    <main className={`onboarding-page${accountFlow ? ` onboarding-page--account onboarding-page--${step}${step === "auth" ? ` onboarding-page--${authMode}` : ""}` : ""}`}>
      <header className="onboarding-topbar">
        <a className="onboarding-brand" href="/" aria-label="PesaGuard developer home">
          <img src="/pesaguard-brand-mark.svg" alt="" width="34" height="36" />
          <span>PesaGuard</span>
        </a>
        <nav className="onboarding-topbar-actions" aria-label="Onboarding help">
          <span className="onboarding-security"><LockKeyhole size={15} /> Secure developer workspace</span>
          <a className="onboarding-faq-button" href="/faqs"><CircleHelp size={16} /> FAQs</a>
        </nav>
      </header>

      <div className="onboarding-layout">
        <section className={`onboarding-card${accountFlow ? " onboarding-card--account" : ""}`} aria-labelledby="onboarding-title">
          {!accountFlow && <div className="onboarding-progress"
            aria-label={onboardingWorkflow
              ? `Onboarding progress ${onboardingWorkflow.progress.percentage} percent complete`
              : `Step ${activeStage + 1} of ${stages.length}`}>
            {stages.map((label, index) => (
              <div className={`onboarding-progress-step${index <= activeStage ? " is-active" : ""}`} key={label}>
                <span>{index < activeStage ? <Check size={13} /> : index + 1}</span>
                <small>{label}</small>
              </div>
            ))}
          </div>}
          {gateError && <p className="onboarding-error" role="alert">{gateError}</p>}
          {!accountFlow && isAuthenticated
            && ["api-explorer", "webhook", "documentation"].includes(step) && <button
            className="onboarding-secondary onboarding-skip"
            type="button"
            disabled={busy}
            onClick={() => void skipSetup()}
          >Skip this optional step</button>}
          {new URLSearchParams(window.location.search).get("logout") === "unconfirmed" && (
            <p className="onboarding-error" role="alert">
              You have been signed out on this device, but the server could not confirm session revocation. If this device is shared, sign in again when the service is available and revoke other sessions from Security Center.
            </p>
          )}

          {step === "invitation" && (
            <div className="onboarding-stage">
              <span className="onboarding-stage-icon"><Users size={24} /></span>
              <span className="onboarding-step-label">ORGANIZATION INVITATION</span>
              <h2 id="onboarding-title">Join your organization</h2>
              <p>Review the invitation, then sign in or create an account with the invited email address to continue.</p>
              {!invitationToken && <p className="onboarding-error" role="alert">This invitation link is missing its token. Ask the organization administrator to send a new invitation.</p>}
              {invitationPreview && <div className="onboarding-invitation-preview" aria-live="polite">
                <p><strong>{invitationPreview.organizationName}</strong> invited you as <strong>{invitationPreview.role}</strong>.</p>
                <p>Invited address: <strong>{invitationPreview.invitedEmailHint}</strong></p>
                <p>Invited by {invitationPreview.inviterName} · expires {new Date(invitationPreview.expiresAt).toLocaleString()}</p>
                {invitationPreview.status !== "PENDING" && <p className="onboarding-error" role="alert">This invitation is {invitationPreview.status.toLowerCase()}. Ask an organization administrator for a new invitation.</p>}
              </div>}
              {invitationAccepted ? <div className="onboarding-form">
                <p className="onboarding-success" role="status">Invitation accepted. Your organization membership is ready.</p>
                {invitationOrganizationId && <button className="onboarding-primary" type="button" disabled={busy} onClick={() => void switchWorkspace(invitationOrganizationId).catch((switchError: unknown) => setError(safeUserErrorMessage(switchError, "Could not open the invited workspace.")))}>
                  {busy ? "Opening workspace…" : "Open invited workspace"}<ArrowRight size={17} />
                </button>}
              </div> : invitationDeclined ? <p className="onboarding-success" role="status">You declined this invitation.</p>
                : isAuthenticated ? <div className="onboarding-form" aria-busy={busy}>
                  <p>Signed in as <strong>{user?.email}</strong>. The system will verify this account matches the invited address before joining.</p>
                  {error && <p className="onboarding-error" role="alert">{error}</p>}
                  <button className="onboarding-primary" type="button" disabled={busy || !invitationToken || invitationPreview?.status !== "PENDING"} onClick={() => void acceptInvitation()}>
                    {busy ? "Accepting invitation…" : "Accept invitation"}{!busy && <ArrowRight size={17} />}
                  </button>
                  <button className="onboarding-secondary" type="button" disabled={busy || !invitationToken || invitationPreview?.status !== "PENDING"} onClick={() => void declineInvitation()}>Decline invitation</button>
                </div> : <div className="onboarding-form">
                  {error && <p className="onboarding-error" role="alert">{error}</p>}
                  <button className="onboarding-primary" type="button" disabled={!invitationToken || invitationPreview?.status !== "PENDING"} onClick={() => { setError(""); setAuthMode("signup"); setStep("auth"); }}>
                    Create account to accept<ArrowRight size={17} />
                  </button>
                  <button className="onboarding-secondary" type="button" disabled={!invitationToken || invitationPreview?.status !== "PENDING"} onClick={() => { setError(""); setAuthMode("signin"); setStep("auth"); }}>Sign in to accept</button>
                </div>}
              <p className="onboarding-footnote">The invitation is single-use and may expire. If it is no longer valid, ask an organization administrator to create a new one.</p>
            </div>
          )}

          {step === "verify" && (
            <div className={`onboarding-stage onboarding-verification-stage${verificationStatus === "success" ? " is-verified" : ""}`}>
              <div
                className={`onboarding-verification-visual onboarding-verification-visual--${verificationExpired ? "expired" : verificationStatus}${verificationDigits.some(Boolean) ? " has-code" : ""}`}
                aria-hidden="true"
              >
                <svg className="onboarding-verification-progress" viewBox="0 0 56 56">
                  <circle className="onboarding-verification-progress-track" cx="28" cy="28" r="25" />
                  <circle
                    className="onboarding-verification-progress-value"
                    cx="28"
                    cy="28"
                    r="25"
                    pathLength="100"
                    strokeDashoffset={verificationStatus === "success"
                      ? 0
                      : 100 - (verificationSeconds / (10 * 60)) * 100}
                  />
                </svg>
                <span className="onboarding-verification-shield">
                  <ShieldCheck size={30} strokeWidth={1.7} />
                  <span className="onboarding-verification-lock"><LockKeyhole size={11} strokeWidth={2} /></span>
                  <span className="onboarding-verification-email"><Mail size={11} strokeWidth={2} /></span>
                </span>
                {verificationStatus === "success" && <span className="onboarding-verification-result"><Check size={12} strokeWidth={3} /></span>}
                {verificationStatus === "verifying" && <span className="onboarding-verification-scan" />}
              </div>
              <span className="onboarding-step-label">EMAIL VERIFICATION</span>
              <h2 id="onboarding-title">{verificationStatus === "success" || verificationNotice.startsWith("Your email") ? "Email verified" : "Verify your email"}</h2>
              <p role={verificationNotice ? "status" : undefined}>{verificationNotice || `Enter the six-digit code sent to ${redactEmailAddress(email)}. It expires 10 minutes after it was sent.`}</p>
              <ValidatedForm className="onboarding-form onboarding-email-form" onSubmit={submitVerificationCode} aria-busy={busy}>
                <div className="onboarding-email-redacted" aria-label={`Verification email ${redactEmailAddress(email)}`}>
                  <span>Email address</span>
                  <strong>{redactEmailAddress(email)}</strong>
                </div>
                {showVerificationInputs && <fieldset className="onboarding-code-fieldset" aria-describedby="verification-code-hint" disabled={verificationCodeDisabled}>
                  <legend>Verification code</legend>
                  <div
                    className="onboarding-code-inputs"
                    role="group"
                    aria-label="Six-digit verification code"
                    data-state={verificationStatus}
                    aria-busy={verificationStatus === "verifying"}
                    aria-invalid={verificationStatus === "error" || verificationExpired || verificationStatus === "locked"}
                  >
                    {verificationDigits.map((digit, index) => (
                      <input
                        key={index}
                        ref={(element) => { verificationCodeInputs.current[index] = element; }}
                        type="text"
                        inputMode="numeric"
                        pattern={index === 0 ? "[0-9]{1,6}" : "[0-9]"}
                        maxLength={index === 0 ? EMAIL_VERIFICATION_CODE_LENGTH : 1}
                        autoComplete={index === 0 ? "one-time-code" : "off"}
                        aria-label={`Digit ${index + 1} of ${EMAIL_VERIFICATION_CODE_LENGTH}`}
                        data-filled={digit !== ""}
                        required
                        value={digit}
                        onChange={(event) => updateVerificationDigit(index, event.target.value)}
                        onPaste={pasteVerificationCode}
                        onKeyDown={(event) => handleVerificationCodeKeyDown(event, index)}
                      />
                    ))}
                  </div>
                </fieldset>}
                {showVerificationInputs && <div className={`onboarding-code-status onboarding-code-status--${verificationStatus}`} aria-live="polite" role={error || verificationStatus === "error" || verificationStatus === "locked" ? "alert" : "status"}>
                  {verificationStatus === "verifying" && <><LoaderCircle size={15} aria-hidden="true" /> Verifying code…</>}
                  {verificationStatus === "success" && <><CheckCircle2 size={16} aria-hidden="true" /> Email verified</>}
                  {verificationStatus === "expired" && <>Code expired. Request a new code to continue.</>}
                  {verificationStatus === "locked" && <>{error || "Too many attempts. Request a new code to continue."}</>}
                  {verificationStatus === "error" && <>{error || "Invalid verification code. Check the code and try again."}</>}
                  {verificationStatus === "sending" && <><LoaderCircle size={15} aria-hidden="true" /> Sending a new code…</>}
                  {verificationStatus === "sent" && <><CheckCircle2 size={15} aria-hidden="true" /> Code sent. Check your inbox.</>}
                  {verificationStatus === "idle" && error && <>{error}</>}
                  {verificationStatus === "idle" && !error && <span className="onboarding-code-countdown">
                    {verificationExpiresAt === null
                      ? "Request a verification code to continue."
                      : verificationExpired
                        ? "Code expired. Request a new code to continue."
                        : `Code expires in ${String(Math.floor(verificationSeconds / 60)).padStart(2, "0")}:${String(verificationSeconds % 60).padStart(2, "0")}`}
                  </span>}
                </div>}
              </ValidatedForm>
              {!verificationNotice.startsWith("Your email") && verificationStatus !== "success" && <button className="onboarding-secondary onboarding-resend-code" type="button" aria-busy={verificationStatus === "sending"} onClick={() => void resendVerificationCode()} disabled={busy || resendSeconds > 0 || !email.trim()}>
                {verificationStatus === "sending"
                  ? <><LoaderCircle size={15} aria-hidden="true" /> Sending…</>
                  : verificationStatus === "sent"
                    ? <><CheckCircle2 size={15} aria-hidden="true" /> Code sent</>
                    : resendSeconds > 0
                      ? `Resend code in ${Math.floor(resendSeconds / 60)}:${String(resendSeconds % 60).padStart(2, "0")}`
                      : verificationStatus === "locked" || verificationExpired
                        ? "Request a new code"
                        : "Resend code"}
              </button>}
              {verificationNotice.startsWith("Your email") && verificationStatus !== "success" && <button className="onboarding-primary" type="button" onClick={() => { setAuthMode("signin"); setStep("auth"); }}>
                Continue to sign in <ArrowRight size={17} />
              </button>}
              {showVerificationInputs && <p className="onboarding-footnote">The code can be used once. Requesting another code invalidates the previous one.</p>}
              {!verificationNotice.startsWith("Your email") && verificationStatus !== "success" && <button className="onboarding-secondary" type="button" onClick={() => {
                setAuthMode("signin");
                setStep("auth");
                setError("");
              }}>Return to sign in</button>}
            </div>
          )}

          {step === "auth" && (
            <>
              <div className="onboarding-card-heading">
                <span className="onboarding-step-label">YOUR DEVELOPER ACCOUNT</span>
                <h2 id="onboarding-title">{authMode === "signup" ? "Create your account" : "Welcome back"}</h2>
                <p>{authMode === "signup" ? "Create your developer identity, then set up your organization in the next step." : "Sign in to continue to your developer workspace."}</p>
                {verificationNotice && <p className="onboarding-verification-notice" role="status">{verificationNotice}</p>}
              </div>
              <div className="onboarding-tabs" role="group" aria-label="Choose an account action">
                <button type="button" aria-pressed={authMode === "signup"} onClick={() => changeAuthMode("signup")}>Create account</button>
                <button type="button" aria-pressed={authMode === "signin"} onClick={() => changeAuthMode("signin")}>Sign in</button>
              </div>
              <ValidatedForm ref={authForm} className="onboarding-form" onSubmit={submitAuth} aria-busy={busy} key={`${step}-${authMode}`}>
                {authMode === "signup" && <>
                  <label>Your name<input autoComplete="name" required minLength={2} maxLength={120} value={displayName} onChange={(event) => setDisplayName(event.target.value)} /></label>
                </>}
                <label>Email address<input type="email" autoComplete="email" inputMode="email" required value={email} onChange={(event) => { setEmail(event.target.value); setError(""); }} /></label>
                <div className="onboarding-password-group">
                  <label htmlFor="account-password">Password</label>
                  <div className="onboarding-password-field">
                    <input id="account-password" type={showPassword ? "text" : "password"} autoComplete={authMode === "signup" ? "new-password" : "current-password"} required value={password} onChange={(event) => setPassword(event.target.value)} />
                    <button className="onboarding-password-toggle" type="button" aria-label={showPassword ? "Hide password" : "Show password"} aria-pressed={showPassword} onClick={() => setShowPassword((visible) => !visible)}>
                      {showPassword ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}
                    </button>
                  </div>
                </div>
                {authMode === "signup" && <>
                  <div className="onboarding-password-group">
                    <label htmlFor="account-password-confirm">Confirm password</label>
                    <div className="onboarding-password-field">
                      <input id="account-password-confirm" type={showConfirmPassword ? "text" : "password"} autoComplete="new-password" aria-invalid={passwordMismatch} aria-describedby="signup-password-match" required value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} />
                      <button className="onboarding-password-toggle" type="button" aria-label={showConfirmPassword ? "Hide confirmation password" : "Show confirmation password"} aria-pressed={showConfirmPassword} onClick={() => setShowConfirmPassword((visible) => !visible)}>
                        {showConfirmPassword ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}
                      </button>
                    </div>
                    <small className={`onboarding-password-match${passwordMismatch ? " is-invalid" : passwordMatch ? " is-valid" : ""}`} id="signup-password-match" aria-live="polite">
                      {passwordMismatch ? "Passwords do not match." : passwordMatch ? "Passwords match." : "Re-enter the same password."}
                    </small>
                  </div>
                  <label className="onboarding-checkbox">
                    <input type="checkbox" required checked={acceptedTerms} onChange={(event) => setAcceptedTerms(event.target.checked)} />
                    <span>I accept the <a href="/terms">developer terms</a> and <a href="/privacy">developer privacy policy</a>.</span>
                  </label>
                </>}
                {error && <p className="onboarding-error" role="alert">{error}</p>}
                <button className="onboarding-primary" type="submit" disabled={busy}>
                  {busy ? <><LoaderCircle size={17} aria-hidden="true" /> {authMode === "signup" ? "Continuing…" : "Signing in…"}</> : <>{authMode === "signup" ? "Continue to organization setup" : "Sign in"}<ArrowRight size={17} /></>}
                </button>
              </ValidatedForm>
              <p className="onboarding-footnote">Registration must be enabled by the platform administrator. Your password is sent only to the PesaGuard API over HTTPS.</p>
              {authMode === "signin" && (
                <div className="onboarding-auth-links">
                  <button className="onboarding-secondary" type="button" onClick={() => authForm.current?.requestSubmit()}>
                    Continue with email code
                  </button>
                  <button className="onboarding-secondary" type="button" onClick={() => { setError(""); setVerificationNotice(""); setStep("forgot"); }}>Forgot password?</button>
                </div>
              )}
            </>
          )}

          {step === "login-mfa" && <div className="onboarding-stage onboarding-verification-stage">
            <div
              className={`onboarding-verification-visual onboarding-verification-visual--${loginMfaExpired ? "expired" : loginMfaStatus}${loginMfaDigits.some(Boolean) ? " has-code" : ""}`}
              aria-hidden="true"
            >
              <svg className="onboarding-verification-progress" viewBox="0 0 56 56">
                <circle className="onboarding-verification-progress-track" cx="28" cy="28" r="25" />
                <circle
                  className="onboarding-verification-progress-value"
                  cx="28"
                  cy="28"
                  r="25"
                  pathLength="100"
                  strokeDashoffset={loginMfaStatus === "success"
                    ? 0
                    : 100 - (loginMfaSeconds / (10 * 60)) * 100}
                />
              </svg>
              <span className="onboarding-verification-shield">
                <ShieldCheck size={30} strokeWidth={1.7} />
                <span className="onboarding-verification-lock"><LockKeyhole size={11} strokeWidth={2} /></span>
                <span className="onboarding-verification-email"><Mail size={11} strokeWidth={2} /></span>
              </span>
              {loginMfaStatus === "success" && <span className="onboarding-verification-result"><Check size={12} strokeWidth={3} /></span>}
              {loginMfaStatus === "verifying" && <span className="onboarding-verification-scan" />}
            </div>
            <span className="onboarding-step-label">EMAIL SIGN-IN VERIFICATION</span>
            <h2 id="onboarding-title">Verify it’s you</h2>
            <p>{loginMfaChallenge
              ? `Enter the six-digit sign-in code sent to ${loginMfaChallenge.maskedEmail}.`
              : "Your sign-in verification has expired. Return to sign in to request a new code."}</p>
            {loginMfaChallenge ? <ValidatedForm className="onboarding-form onboarding-email-form onboarding-login-mfa-form" onSubmit={(event) => void submitLoginEmailMfa(event)} aria-busy={busy}>
              <fieldset className="onboarding-code-fieldset" aria-describedby="login-mfa-code-hint" disabled={busy || loginMfaExpired}>
                <legend>Sign-in code</legend>
                <div
                  className="onboarding-code-inputs"
                  role="group"
                  aria-label="Six-digit sign-in verification code"
                  data-state={loginMfaExpired ? "expired" : loginMfaStatus}
                  aria-busy={loginMfaStatus === "verifying"}
                  aria-invalid={loginMfaStatus === "error" || loginMfaExpired}
                >
                  {loginMfaDigits.map((digit, index) => (
                    <input
                      key={index}
                      ref={(element) => { loginMfaCodeInputs.current[index] = element; }}
                      type="text"
                      inputMode="numeric"
                      pattern={index === 0 ? "[0-9]{1,6}" : "[0-9]"}
                      maxLength={index === 0 ? EMAIL_VERIFICATION_CODE_LENGTH : 1}
                      autoComplete={index === 0 ? "one-time-code" : "off"}
                      aria-label={`Digit ${index + 1} of ${EMAIL_VERIFICATION_CODE_LENGTH}`}
                      data-filled={digit !== ""}
                      required
                      value={digit}
                      onChange={(event) => updateLoginMfaDigit(index, event.target.value)}
                      onPaste={pasteLoginMfaCode}
                      onKeyDown={(event) => handleLoginMfaCodeKeyDown(event, index)}
                    />
                  ))}
                </div>
              </fieldset>
              <div className={`onboarding-code-status onboarding-code-status--${loginMfaExpired ? "expired" : loginMfaStatus}`} aria-live="polite" role={error || loginMfaStatus === "error" || loginMfaExpired ? "alert" : "status"}>
                {loginMfaStatus === "verifying" && <><LoaderCircle size={15} aria-hidden="true" /> Verifying sign-in code…</>}
                {loginMfaExpired && <>Code expired. Request a new code to continue.</>}
                {loginMfaStatus === "error" && <>{error || "Invalid code. Check the code and try again."}</>}
                {loginMfaStatus === "idle" && error && <>{error}</>}
                {loginMfaStatus === "idle" && !error && !loginMfaExpired && <span id="login-mfa-code-hint" className="onboarding-code-countdown">
                  Code expires in {String(Math.floor(loginMfaSeconds / 60)).padStart(2, "0")}:{String(loginMfaSeconds % 60).padStart(2, "0")}
                </span>}
              </div>
              <button className="onboarding-primary" type="submit" disabled={busy || loginMfaExpired || loginMfaCode.length !== EMAIL_VERIFICATION_CODE_LENGTH}>
                {busy ? <><LoaderCircle size={17} aria-hidden="true" /> Verifying…</> : <>Verify and sign in<ArrowRight size={17} /></>}
              </button>
            </ValidatedForm> : <p className="onboarding-error" role="alert">This sign-in challenge is no longer available. Return to sign in to request a new code.</p>}
            <button className="onboarding-secondary onboarding-resend-code" type="button" disabled={busy || !loginMfaChallenge || loginMfaResendSeconds > 0}
              onClick={() => void resendLoginEmailCode()}>
              {loginMfaResendSeconds > 0 ? `Resend code in ${loginMfaResendSeconds}s` : "Resend sign-in code"}
            </button>
            <button className="onboarding-secondary" type="button" disabled={busy} onClick={() => {
              setLoginMfaChallenge(null);
              setLoginMfaDigits(Array.from({ length: EMAIL_VERIFICATION_CODE_LENGTH }, () => ""));
              setLoginMfaStatus("idle");
              setError("");
              setStep("auth");
            }}>Return to sign in</button>
          </div>}

          {step === "security" && <div className="onboarding-stage">
            <span className="onboarding-stage-icon"><ShieldCheck size={23} /></span>
            <span className="onboarding-step-label">SECURE YOUR ACCOUNT</span>
            <h2 id="onboarding-title">{mfaEnrollmentComplete ? "Authenticator confirmed" : "Set up an authenticator"}</h2>
            {mfaEnrollmentComplete ? <>
              <p>Store these recovery codes somewhere private. They are shown once and cannot be retrieved later.</p>
              <ul className="security-recovery-code-list">{mfaRecoveryCodes.map((code) => <li key={code}><code>{code}</code></li>)}</ul>
              <button className="onboarding-secondary" type="button" onClick={() => void copyMfaRecoveryCodes()}>
                {mfaRecoveryCodesCopied ? <><Check size={16} /> Recovery codes copied</> : <><Copy size={16} /> Copy recovery codes</>}
              </button>
              <label className="onboarding-checkbox">
                <input type="checkbox" checked={mfaRecoveryCodesSaved}
                  onChange={(event) => setMfaRecoveryCodesSaved(event.target.checked)} />
                <span>I have saved my recovery codes in a secure place.</span>
              </label>
              {error && <p className="onboarding-error" role="alert">{error}</p>}
              <button className="onboarding-primary" type="button" disabled={busy || !mfaRecoveryCodesSaved}
                onClick={() => void confirmSecuritySetup()}>
                {busy ? "Saving security setup…" : <>Continue to profile<ArrowRight size={17} /></>}
              </button>
            </> : <>
              <p>Add this account to a TOTP authenticator app, then verify a current code. The secret is only shown during setup.</p>
              {mfaEnrollment && <div className="onboarding-mfa-enrollment-details">
                <label>Authenticator setup URI<input readOnly value={mfaEnrollment.provisioningUri} /></label>
                <label>Manual setup key<input readOnly value={mfaEnrollment.secret} /></label>
              </div>}
              <ValidatedForm className="onboarding-form" onSubmit={(event) => void confirmRequiredMfaEnrollment(event)} aria-busy={busy}>
                <label>Authenticator code<input autoComplete="one-time-code" inputMode="numeric" minLength={6}
                  maxLength={6} required value={mfaEnrollmentCode}
                  onChange={(event) => setMfaEnrollmentCode(event.target.value.replace(/\D/g, "").slice(0, 6))} /></label>
                {error && <p className="onboarding-error" role="alert">{error}</p>}
                <button className="onboarding-primary" type="submit"
                  disabled={busy || !mfaEnrollment || mfaEnrollmentCode.length !== 6}>
                  {busy ? "Verifying authenticator…" : "Verify authenticator"} <ArrowRight size={17} />
                </button>
              </ValidatedForm>
            </>}
          </div>}

          {step === "mfa-enroll" && <div className="onboarding-stage">
            <span className="onboarding-stage-icon"><ShieldCheck size={23} /></span>
            <span className="onboarding-step-label">SECURE YOUR ACCOUNT</span>
            <h2 id="onboarding-title">{mfaEnrollmentComplete ? "MFA is enabled" : "Set up an authenticator"}</h2>
            {mfaEnrollmentComplete
              ? <>
                <p>Save these recovery codes somewhere private. They will not be shown again. Sign in with your password and emailed verification code to continue.</p>
                <ul className="security-recovery-code-list">{mfaRecoveryCodes.map((code) => <li key={code}><code>{code}</code></li>)}</ul>
                <button className="onboarding-secondary" type="button" onClick={() => void copyMfaRecoveryCodes()}>
                  {mfaRecoveryCodesCopied ? <><Check size={16} /> Recovery codes copied</> : <><Copy size={16} /> Copy recovery codes</>}
                </button>
                <button className="onboarding-primary" type="button" onClick={() => {
                  setMfaRecoveryCodes([]);
                  setAuthMode("signin");
                  setStep("auth");
                  setError("");
                }}>
                  Continue to email verification <ArrowRight size={17} />
                </button>
              </>
              : <>
                <p>Your workspace requires multi-factor authentication. Add this account to a TOTP authenticator app, then verify a current code to finish signing in.</p>
                {mfaEnrollment && <div className="onboarding-mfa-enrollment-details">
                  <label>Authenticator setup URI<input readOnly value={mfaEnrollment.provisioningUri} /></label>
                  <label>Manual setup key<input readOnly value={mfaEnrollment.secret} /></label>
                </div>}
                <ValidatedForm className="onboarding-form" onSubmit={(event) => void confirmRequiredMfaEnrollment(event)} aria-busy={busy}>
                  <label>Authenticator code<input autoComplete="one-time-code" inputMode="numeric" minLength={6} maxLength={6} required value={mfaEnrollmentCode} onChange={(event) => setMfaEnrollmentCode(event.target.value.replace(/\D/g, "").slice(0, 6))} /></label>
                  {error && <p className="onboarding-error" role="alert">{error}</p>}
                  <button className="onboarding-primary" type="submit" disabled={busy || !mfaEnrollment || mfaEnrollmentCode.length !== 6}>
                    {busy ? "Verifying…" : "Verify authenticator and save recovery codes"} <ArrowRight size={17} />
                  </button>
                </ValidatedForm>
                <button className="onboarding-secondary" type="button" onClick={() => {
                  setMfaEnrollmentToken("");
                  setMfaEnrollment(null);
                  setMfaEnrollmentCode("");
                  setStep("auth");
                }}>Cancel and return to sign in</button>
              </>}
          </div>}
          {step === "forgot" && <div className="onboarding-stage">
            <span className={`onboarding-stage-icon${verificationNotice ? " is-success" : ""}`}>{verificationNotice ? <CheckCircle2 size={23} /> : <LockKeyhole size={23} />}</span>
            <span className="onboarding-step-label">ACCOUNT RECOVERY</span>
            <h2 id="onboarding-title">{verificationNotice ? "Check your inbox" : "Reset your password"}</h2>
            <p role={verificationNotice ? "status" : undefined}>{verificationNotice || "Enter the email address linked to your account. If it matches, we’ll send a secure, time-limited reset link."}</p>
            <ValidatedForm className="onboarding-form onboarding-email-form" onSubmit={async (event) => {
              event.preventDefault(); setBusy(true); setError("");
              try {
                await authApi.forgotPassword({ email: email.trim() });
                setVerificationNotice("If an account exists for this address, a password reset link has been sent.");
              } catch (resetError) {
                setError(safeUserErrorMessage(resetError, "We could not request a reset link."));
              } finally { setBusy(false); }
            }} aria-busy={busy}>
              <label>Email address<input type="email" autoComplete="email" required value={email} onChange={(event) => { setEmail(event.target.value); if (verificationNotice) setVerificationNotice(""); }} /></label>
              {error && <p className="onboarding-error" role="alert">{error}</p>}
              <button className="onboarding-primary" type="submit" disabled={busy}>{busy ? <><LoaderCircle size={17} aria-hidden="true" /> Checking account…</> : <>Send reset link<ArrowRight size={17} /></>}</button>
            </ValidatedForm>
            <button className="onboarding-secondary" type="button" onClick={() => { setStep("auth"); setAuthMode("signin"); }}>Return to sign in</button>
          </div>}

          {step === "reset" && <div className="onboarding-stage">
            <span className={`onboarding-stage-icon${resetTokenExpired || !recoveryToken ? " is-error" : ""}`}>{resetTokenExpired || !recoveryToken ? <LockKeyhole size={23} /> : <KeyRound size={23} />}</span>
            <span className="onboarding-step-label">ACCOUNT RECOVERY</span>
            <h2 id="onboarding-title">{resetTokenExpired ? "Reset link expired" : recoveryToken ? "Choose a new password" : "Reset link unavailable"}</h2>
            {(!recoveryToken || resetTokenExpired) && <>
              <p className="onboarding-error" role="alert">{resetTokenExpired
                ? "This password reset link is invalid or has expired. Request a new link to continue."
                : "This password reset link is missing its token. Request a new link to continue."}</p>
              <button className="onboarding-secondary" type="button" onClick={() => { setResetTokenExpired(false); setError(""); setStep("forgot"); }}>Request a new reset link</button>
            </>}
            {recoveryToken && !resetTokenExpired && <ValidatedForm className="onboarding-form" onSubmit={async (event) => {
              event.preventDefault();
              if (!recoveryToken) { setError("This password reset link is invalid or has expired."); return; }
              if (password !== confirmPassword) { setError("Passwords do not match."); return; }
              setBusy(true); setError("");
              try {
                await authApi.resetPassword({ token: recoveryToken, newPassword: password });
                setPassword("");
                setConfirmPassword("");
                setShowPassword(false);
                setShowConfirmPassword(false);
                setVerificationNotice("Your password has changed. Sign in with your new password.");
                setAuthMode("signin"); setStep("auth");
              } catch (resetError) {
                if (resetError instanceof ApiError && resetError.code === "PASSWORD_RESET_INVALID") {
                  setResetTokenExpired(true);
                  setError("");
                } else {
                  setError(safeUserErrorMessage(resetError, "We could not reset your password."));
                }
              } finally { setBusy(false); }
            }} aria-busy={busy}>
              <div className="onboarding-password-group">
                <label htmlFor="reset-password">New password</label>
                <div className="onboarding-password-field">
                  <input id="reset-password" type={showPassword ? "text" : "password"} autoComplete="new-password" required value={password} onChange={(event) => setPassword(event.target.value)} />
                  <button className="onboarding-password-toggle" type="button" aria-label={showPassword ? "Hide password" : "Show password"} aria-pressed={showPassword} onClick={() => setShowPassword((visible) => !visible)}>
                    {showPassword ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}
                  </button>
                </div>
              </div>
              <div className="onboarding-password-group">
                <label htmlFor="reset-password-confirm">Confirm new password</label>
                <div className="onboarding-password-field">
                  <input id="reset-password-confirm" type={showConfirmPassword ? "text" : "password"} autoComplete="new-password" aria-invalid={passwordMismatch} aria-describedby="reset-password-match" required value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} />
                  <button className="onboarding-password-toggle" type="button" aria-label={showConfirmPassword ? "Hide confirmation password" : "Show confirmation password"} aria-pressed={showConfirmPassword} onClick={() => setShowConfirmPassword((visible) => !visible)}>
                    {showConfirmPassword ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}
                  </button>
                </div>
                <small className={`onboarding-password-match${passwordMismatch ? " is-invalid" : passwordMatch ? " is-valid" : ""}`} id="reset-password-match" aria-live="polite">
                  {passwordMismatch ? "Passwords do not match." : passwordMatch ? "Passwords match." : "Re-enter the same password."}
                </small>
              </div>
              {error && <p className="onboarding-error" role="alert">{error}</p>}
              <button className="onboarding-primary" type="submit" disabled={busy}>{busy ? <><LoaderCircle size={17} aria-hidden="true" /> Updating password…</> : <>Update password<ArrowRight size={17} /></>}</button>
            </ValidatedForm>}
          </div>}

          {step === "locked" && <div className="onboarding-stage">
            <span className="onboarding-stage-icon"><LockKeyhole size={24} /></span>
            <span className="onboarding-step-label">SIGN IN PAUSED</span>
            <h2 id="onboarding-title">Too many sign-in attempts</h2>
            <p>Sign-in attempts are temporarily paused for security. Wait before trying again, or reset your password if you think someone else tried to access the account.</p>
            <button className="onboarding-primary" type="button" onClick={() => { setStep("forgot"); setError(""); }}>Reset password <ArrowRight size={17} /></button>
            <button className="onboarding-secondary" type="button" onClick={() => { setStep("auth"); setAuthMode("signin"); }}>Return to sign in</button>
          </div>}

          {step === "welcome" && (
            <div className="onboarding-stage">
              <span className="onboarding-stage-icon"><ShieldCheck size={24} /></span>
              <span className="onboarding-step-label">WELCOME · PRODUCT INTRODUCTION</span>
              <h2 id="onboarding-title">Welcome to PesaGuard Developers</h2>
              <p>Build and test your integration safely in an isolated sandbox. You choose your organization details, project, environment, and credentials at each step; nothing is provisioned without your action.</p>
              <ul className="onboarding-checklist">
                <li><Check size={15} /> Test against sandbox data before production</li>
                <li><Check size={15} /> Create narrowly scoped API credentials</li>
                <li><Check size={15} /> Save progress and return whenever you are ready</li>
              </ul>
              <button className="onboarding-primary" type="button" disabled={busy} onClick={() => void advanceWizard("organization", "welcome")}>Continue to organization <ArrowRight size={17} /></button>
            </div>
          )}

          {step === "profile" && <>
            <div className="onboarding-card-heading">
              <span className="onboarding-step-label">DEVELOPER PROFILE</span>
              <h2 id="onboarding-title">Tell us your name</h2>
              <p>Your profile name appears in your developer workspace and activity history.</p>
            </div>
            <ValidatedForm className="onboarding-form" onSubmit={saveProfile}>
              <label>Full name<input autoFocus required minLength={2} maxLength={120} value={displayName} onChange={(event) => setDisplayName(event.target.value)} /></label>
              {error && <p className="onboarding-error" role="alert">{error}</p>}
              <button className="onboarding-primary" type="submit" disabled={busy}>{busy ? "Saving…" : "Save profile"}<ArrowRight size={17} /></button>
            </ValidatedForm>
          </>}

          {step === "organization" && (
            <>
              <div className="onboarding-card-heading">
                <span className="onboarding-step-label">STEP 1 · ORGANIZATION</span>
                <h2 id="onboarding-title">{status === "authenticated" && onboardingStatus?.organizationReady ? "Review your organization" : "Create your organization"}</h2>
                <p>Organizations group your projects and team access. This organization is created only after you submit these details.</p>
              </div>
              <ValidatedForm className="onboarding-form" onSubmit={submitOrganizationStep} aria-busy={busy}>
                <label>Organization name<input autoFocus required minLength={2} maxLength={120} value={newOrganizationName} onChange={(event) => setNewOrganizationName(event.target.value)} /></label>
                <label>Description<textarea required minLength={10} maxLength={500} rows={4} value={newOrganizationDescription} onChange={(event) => setNewOrganizationDescription(event.target.value)} /></label>
                {error && <p className="onboarding-error" role="alert">{error}</p>}
                <button className="onboarding-primary" type="submit" disabled={busy}>{busy
                  ? status === "authenticated" ? "Saving organization…" : "Creating account…"
                  : status === "authenticated" ? "Save and continue" : "Create account and organization"}<ArrowRight size={17} /></button>
              </ValidatedForm>
              {status !== "authenticated" && <button className="onboarding-secondary" type="button" disabled={busy} onClick={() => { setError(""); setStep("auth"); }}>Back to account details</button>}
            </>
          )}

          {step === "project" && (
            <>
              <div className="onboarding-card-heading">
                <span className="onboarding-step-label">STEP 2 · PROJECT</span>
                <h2 id="onboarding-title">{bootstrap?.project.id ? "Your project is ready" : "Create your first project"}</h2>
                <p>Choose a project name and starter. This creates only the project; you will select and create its environment next.</p>
              </div>
              {!bootstrap?.project.id ? <ValidatedForm className="onboarding-form" onSubmit={createProject}>
                <label>Project name<input autoFocus required minLength={2} maxLength={120} placeholder="Project name" value={projectName} onChange={(event) => setProjectName(event.target.value)} /></label>
                <fieldset className="onboarding-template-options">
                  <legend>Starter template</legend>
                  {([
                    ["payments", "Payments integration", "Start with sandbox transactions and payment flows."],
                    ["events", "Event-driven integration", "Read and process your project’s event stream."],
                    ["risk", "Usage analytics", "Explore read-only usage data for monitoring workflows."],
                  ] as const).map(([value, title, description]) => (
                    <label className={`onboarding-template-option${template === value ? " is-selected" : ""}`} key={value}>
                      <input type="radio" name="starter-template" value={value} checked={template === value}
                        onChange={() => setTemplate(value)} />
                      <span><strong>{title}</strong><small>{description}</small></span>
                    </label>
                  ))}
                </fieldset>
                {error && <p className="onboarding-error" role="alert">{error}</p>}
                <button className="onboarding-primary" type="submit" disabled={busy || !hasPermission("project:create")}>
                  {busy ? "Creating project…" : "Create project"}
                  {!busy && <ArrowRight size={17} />}
                </button>
              </ValidatedForm> : <div className="onboarding-stage">
                <p><strong>{bootstrap.project.name}</strong> is ready. No environment or key has been created yet.</p>
                <button className="onboarding-primary" type="button" disabled={busy} onClick={() => void advanceWizard("environment", "project")}>Continue to environment <ArrowRight size={17} /></button>
              </div>}
            </>
          )}

          {step === "environment" && (
            <div className="onboarding-stage">
              <span className="onboarding-stage-icon"><ShieldCheck size={24} /></span>
              <span className="onboarding-step-label">STEP 3 · ENVIRONMENT</span>
              <h2 id="onboarding-title">{bootstrap?.environment ? "Sandbox environment ready" : "Select an environment"}</h2>
              <p>A sandbox keeps test requests and test credentials separate from production.</p>
              {bootstrap?.environment ? <button className="onboarding-primary" type="button" disabled={busy} onClick={() => void advanceWizard("api-key", "environment")}>Continue to API key <ArrowRight size={17} /></button> : <ValidatedForm className="onboarding-form" onSubmit={createEnvironment}>
                <label>Environment name<input autoFocus required minLength={2} maxLength={80} value={environmentName} onChange={(event) => setEnvironmentName(event.target.value)} /></label>
                <p className="onboarding-footnote"><strong>Sandbox</strong> is recommended for first testing. It contains test data and is separate from production.</p>
                {error && <p className="onboarding-error" role="alert">{error}</p>}
                <button className="onboarding-primary" type="submit" disabled={busy || !bootstrap?.project.id}>{busy ? "Creating environment…" : "Create sandbox environment"}<ArrowRight size={17} /></button>
              </ValidatedForm>}
            </div>
          )}

          {step === "api-key" && !bootstrap?.apiKey && (
            <div className="onboarding-stage">
              <span className="onboarding-stage-icon"><KeyRound size={24} /></span>
              <span className="onboarding-step-label">STEP 4 · API KEY</span>
              <h2 id="onboarding-title">Create your first API key</h2>
              <p>Issue a scoped key for the selected sandbox. The full secret is shown only once.</p>
              {error && <p className="onboarding-error" role="alert">{error}</p>}
              <button className="onboarding-primary" type="button" disabled={busy || !bootstrap?.environment?.id} onClick={() => void createFirstApiKey()}>{busy ? "Creating scoped key…" : "Create sandbox API key"}<ArrowRight size={17} /></button>
            </div>
          )}

          {step === "api-key" && bootstrap?.apiKey && (
            <div className="onboarding-stage">
              <span className="onboarding-stage-icon"><KeyRound size={24} /></span>
              <span className="onboarding-step-label">SANDBOX PROVISIONED</span>
              <h2 id="onboarding-title">Your sandbox key</h2>
              <p><strong>{bootstrap.project.name}</strong> now has an active {bootstrap.environment?.name ?? "sandbox"} environment and a scoped starter key.</p>
              <div className="onboarding-key-box">
                <code>{bootstrap.apiKey.key}</code>
                <button type="button" onClick={copyKey} aria-label="Copy sandbox API key">{copied ? <Check size={17} /> : <Copy size={17} />}</button>
              </div>
              <div className="onboarding-integration-box">
                <div><span>Official SANDBOX Base URL</span><code>{bootstrap.apiKey.baseUrl}</code><button type="button" onClick={() => void copyBaseUrl()}>{copied ? "Copied" : "Copy Base URL"}</button></div>
                <div><span>Authentication</span><code>Authorization: Bearer &lt;API_KEY&gt;</code></div>
                <div><span>First endpoint</span><code>GET {bootstrap.firstEndpoint}</code></div>
                <small>Keep the key in a trusted server-side secret manager. Never embed it in browser or mobile application code.</small>
              </div>
              <p className="onboarding-warning">This key is shown once. Keep it private; it expires {new Date(bootstrap.apiKey.expiresAt).toLocaleDateString()}.</p>
              {error && <p className="onboarding-error" role="alert">{error}</p>}
              <button className="onboarding-primary" type="button" onClick={() => setStep("first-request")}>Continue to first request <ArrowRight size={17} /></button>
            </div>
          )}

          {step === "first-request" && (
            <div className="onboarding-stage">
              <span className="onboarding-stage-icon"><ArrowRight size={24} /></span>
              <span className="onboarding-step-label">STEP 5 · FIRST API REQUEST</span>
              <h2 id="onboarding-title">Make your first sandbox request</h2>
              <p>We will request <code>GET {bootstrap?.firstEndpoint}</code> using your sandbox key. This request is made to the sandbox API, not production.</p>
              {firstRequestResult && <pre className="onboarding-request-result"><code>{firstRequestResult}</code></pre>}
              {error && <p className="onboarding-error" role="alert">{error}</p>}
              <button className="onboarding-primary" type="button" disabled={busy || !bootstrap?.apiKey?.key} onClick={() => void sendFirstRequest()}>{busy ? "Sending request…" : "Send first request"}<ArrowRight size={17} /></button>
            </div>
          )}

          {step === "api-explorer" && (
            <div className="onboarding-stage">
              <span className="onboarding-stage-icon"><CircleHelp size={24} /></span>
              <span className="onboarding-step-label">STEP 6 · API EXPLORER</span>
              <h2 id="onboarding-title">Explore the API</h2>
              <p>Use the API Explorer to inspect endpoints and try requests with your current project and environment. Keep secret keys on your server and use sandbox credentials here.</p>
              <button className="onboarding-primary" type="button" disabled={busy} onClick={() => void advanceWizard("webhook", "api-explorer")}>Continue to webhook setup <ArrowRight size={17} /></button>
            </div>
          )}

          {step === "webhook" && (
            <div className="onboarding-stage">
              <span className="onboarding-stage-icon"><ArrowRight size={24} /></span>
              <span className="onboarding-step-label">STEP 7 · WEBHOOKS</span>
              <h2 id="onboarding-title">Set up event delivery</h2>
              <p>Webhooks notify your server when resources change. Configure an HTTPS endpoint, verify signatures, and handle retries idempotently.</p>
              <a className="onboarding-secondary" href="/webhooks">Open webhook settings</a>
              <button className="onboarding-primary" type="button" disabled={busy} onClick={() => void advanceWizard("documentation", "webhook")}>Continue to documentation <ArrowRight size={17} /></button>
            </div>
          )}

          {step === "documentation" && (
            <div className="onboarding-stage">
              <span className="onboarding-stage-icon"><CircleHelp size={24} /></span>
              <span className="onboarding-step-label">STEP 8 · DOCUMENTATION</span>
              <h2 id="onboarding-title">Keep the documentation close</h2>
              <p>Review authentication, API references, webhook signatures, and sandbox-to-production guidance in the developer docs.</p>
              <a className="onboarding-secondary" href="/docs">Browse documentation</a>
              <button className="onboarding-primary" type="button" disabled={busy} onClick={() => void advanceWizard("production-readiness", "documentation")}>Continue to readiness checklist <ArrowRight size={17} /></button>
            </div>
          )}

          {step === "production-readiness" && (
            <div className="onboarding-stage">
              <span className="onboarding-stage-icon is-success"><CheckCircle2 size={24} /></span>
              <span className="onboarding-step-label">STEP 9 · PRODUCTION READINESS</span>
              <h2 id="onboarding-title">Review before going live</h2>
              <p>Production is a separate environment. Do not reuse sandbox keys or treat a successful sandbox request as production approval.</p>
              <ul className="onboarding-checklist">
                <li><Check size={15} /> Store credentials in a server-side secret manager</li>
                <li><Check size={15} /> Verify webhook signatures and handle retries safely</li>
                <li><Check size={15} /> Configure monitoring, error handling, and idempotency</li>
                <li><Check size={15} /> Complete required account and compliance reviews</li>
              </ul>
              {error && <p className="onboarding-error" role="alert">{error}</p>}
              <button className="onboarding-primary" type="button" disabled={busy || !bootstrap?.environment} onClick={() => void finishOnboarding()}>{busy ? "Completing setup…" : "Complete onboarding"}<ArrowRight size={17} /></button>
            </div>
          )}

          {step === "workspaceReady" && <div className="onboarding-stage">
            <span className="onboarding-stage-icon is-success"><CheckCircle2 size={24} /></span>
            <span className="onboarding-step-label">ONBOARDING COMPLETE</span>
            <h2 id="onboarding-title">Your developer workspace is ready.</h2>
            <div className="onboarding-checklist">
              <span><Check size={15} /> Organization: {organization?.name || onboardingStatus?.organizationName || "Organization"}</span>
              <span><Check size={15} /> Project: {bootstrap?.project.name || onboardingStatus?.projectName}</span>
              <span><Check size={15} /> Environment: {bootstrap?.environment?.name || onboardingStatus?.environmentName}</span>
            </div>
            {recommendations.length ? <div className="onboarding-recommendations">
              <h3>Recommended next steps</h3>
              <ul>{recommendations.map((recommendation) => <li key={recommendation}>{recommendation}</li>)}</ul>
            </div> : null}
            {error && <p className="onboarding-error" role="alert">{error}</p>}
            <button className="onboarding-primary" onClick={() => {
              setBusy(true);
              setError("");
              void onComplete().then((opened) => {
                if (!opened) setError("Your workspace is not ready yet. Refresh this page and try again.");
              }).finally(() => setBusy(false));
            }} disabled={busy}>{busy ? "Opening dashboard…" : "Open developer dashboard"} <ArrowRight size={17} /></button>
          </div>}
        </section>
      </div>
      <footer className="onboarding-footer"><span className="onboarding-footer-brand">PesaGuard developer platform</span><span aria-hidden="true">•</span><span>Sandbox actions are isolated from production</span><nav aria-label="Legal information"><a href="/terms">Terms</a><a href="/privacy">Privacy</a></nav></footer>
    </main>
  );
}
