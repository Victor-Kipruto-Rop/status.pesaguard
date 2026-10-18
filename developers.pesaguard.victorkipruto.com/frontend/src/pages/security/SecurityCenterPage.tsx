import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useEffect, useMemo, useState, type FormEvent, type KeyboardEvent } from "react";
import { Activity, AlertTriangle, BellRing, Check, ChevronLeft, ChevronRight, Clock3, Copy, Fingerprint, KeyRound, MailCheck, Monitor, RotateCw, Search, Settings2, Shield, ShieldAlert, ShieldCheck } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";
import { copyTextToClipboard } from "../../lib/clipboard";
import { useAuth } from "../../context/AuthContext";
import {
  beginMfaEnrolment,
  confirmMfaEnrolment,
  disableMfa,
  beginPasskeyRegistration,
  completePasskeyRegistration,
  listPasskeys,
  removePasskey,
  type PasskeyCredentialSummary,
  listSessions,
  mfaStatus,
  onboardingStatus,
  regenerateMfaRecoveryCodes,
  resendEmailVerification,
  revokeOtherSessions as revokeOtherSessionsApi,
  revokeSession,
} from "../../lib/authApi";
import { createPasskey, passkeysAvailable } from "../../lib/passkeys";
import type { SessionSummary } from "../../types/auth";
import { PasswordSettingsStep } from "../settings/PasswordSettingsStep";

type AuditEvent = {
  id: string;
  sequenceNumber: number;
  actorUserId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  requestId: string;
  createdAt: string;
};

type AuditPage = { items: AuditEvent[]; totalElements: number; totalPages: number };
type MfaEnrolment = { secret: string; provisioningUri: string };
type LoginProtectionStatus = { locked: boolean; lockedUntil: string | null };
type SecuritySignal = { id: string; type: string; subjectId: string; subjectKind: string; detail: string; detectedAt: string };
type SecuritySignalPage = { items: SecuritySignal[]; totalElements: number };
type OrganizationSecuritySettings = {
  organizationId: string;
  allowedAuthMethods: string[];
  sessionTtlMinutes: number;
  idleTimeoutMinutes: number;
  maxSessions: number;
  credentialMinLength: number;
  credentialMaxLength: number;
  mfaRequired: boolean;
  mfaRequiredForAdmins: boolean;
  ipAllowlist: string[];
  securityEventTypes: string[];
  updatedAt: string;
};
const securityCenterTabs = [
  { id: "overview", label: "Overview", description: "Security posture and account signals", icon: ShieldCheck },
  { id: "sign-in", label: "Sign-in & recovery", description: "MFA, passkeys, and password", icon: Fingerprint },
  { id: "sessions", label: "Sessions", description: "Devices and active access", icon: Monitor },
  { id: "organization", label: "Organization", description: "Enforcement and security policy", icon: Settings2 },
  { id: "audit", label: "Audit activity", description: "Security events and history", icon: Activity },
] as const;
type SecurityCenterTab = (typeof securityCenterTabs)[number]["id"];

function handleSecurityTabKeyDown(
  event: KeyboardEvent<HTMLButtonElement>,
  currentTab: SecurityCenterTab,
  onSelectTab: (tab: SecurityCenterTab) => void,
) {
  const currentIndex = securityCenterTabs.findIndex((tab) => tab.id === currentTab);
  const nextIndex = event.key === "ArrowRight"
    ? (currentIndex + 1) % securityCenterTabs.length
    : event.key === "ArrowLeft"
      ? (currentIndex - 1 + securityCenterTabs.length) % securityCenterTabs.length
      : event.key === "Home"
        ? 0
        : event.key === "End"
          ? securityCenterTabs.length - 1
          : -1;
  if (nextIndex < 0) return;
  event.preventDefault();
  const nextTab = securityCenterTabs[nextIndex].id;
  onSelectTab(nextTab);
  event.currentTarget.parentElement
    ?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[nextIndex]?.focus();
}

function formatTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

function errorMessage(error: unknown) {
  return getUserMessage(error, "The system request failed.");
}

export function SecurityCenterPage({ title = "Security center" }: { title?: string } = {}) {
  const { user, logout } = useAuth();
  const [activeTab, setActiveTab] = useState<SecurityCenterTab>("overview");
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [totalEvents, setTotalEvents] = useState(0);
  const [auditPage, setAuditPage] = useState(0);
  const [auditPages, setAuditPages] = useState(0);
  const [auditLoading, setAuditLoading] = useState(true);
  const [mfaEnabled, setMfaEnabled] = useState<boolean | null>(null);
  const [organizationMfaRequired, setOrganizationMfaRequired] = useState<boolean | null>(null);
  const [organizationSettings, setOrganizationSettings] = useState<OrganizationSecuritySettings | null>(null);
  const [organizationPolicyBusy, setOrganizationPolicyBusy] = useState(false);
  const [organizationPolicyError, setOrganizationPolicyError] = useState("");
  const [organizationPolicyNotice, setOrganizationPolicyNotice] = useState("");
  const [mfaEnrolment, setMfaEnrolment] = useState<MfaEnrolment | null>(null);
  const [mfaCode, setMfaCode] = useState("");
  const [recoveryCode, setRecoveryCode] = useState("");
  const [recoveryBusy, setRecoveryBusy] = useState(false);
  const [emailVerified, setEmailVerified] = useState<boolean | null>(null);
  const [emailBusy, setEmailBusy] = useState(false);
  const [emailNotice, setEmailNotice] = useState("");
  const [emailError, setEmailError] = useState("");
  const [loginProtection, setLoginProtection] = useState<LoginProtectionStatus | null>(null);
  const [loginProtectionError, setLoginProtectionError] = useState("");
  const [accountSignals, setAccountSignals] = useState<SecuritySignal[]>([]);
  const [mfaCopied, setMfaCopied] = useState("");
  const [backupCodes, setBackupCodes] = useState<string[]>([]);
  const [recoveryCodesCopied, setRecoveryCodesCopied] = useState(false);
  const [mfaError, setMfaError] = useState("");
  const [mfaBusy, setMfaBusy] = useState(false);
  const [passkeys, setPasskeys] = useState<PasskeyCredentialSummary[]>([]);
  const [passkeyName, setPasskeyName] = useState("");
  const [passkeyError, setPasskeyError] = useState("");
  const [passkeyBusy, setPasskeyBusy] = useState(false);
  const [removingPasskeyId, setRemovingPasskeyId] = useState("");
  const [passkeyCurrentPassword, setPasskeyCurrentPassword] = useState("");
  const [mfaCurrentPassword, setMfaCurrentPassword] = useState("");
  const [mfaDisableCode, setMfaDisableCode] = useState("");
  const [sessionError, setSessionError] = useState("");
  const [auditError, setAuditError] = useState("");
  const [loading, setLoading] = useState(true);
  const [revokingId, setRevokingId] = useState("");
  const [revokingOthers, setRevokingOthers] = useState(false);
  const [query, setQuery] = useState("");
  const [notice, setNotice] = useState("");
  async function loadAuditEvents() {
    setAuditLoading(true);
    setAuditError("");
    try {
      const result = await apiData<AuditPage>(`/api/v1/audit-events?page=${auditPage}&size=15`);
      if (result.totalPages > 0 && auditPage >= result.totalPages) {
        setAuditPage(result.totalPages - 1);
        return;
      }
      if (result.totalPages === 0 && auditPage !== 0) {
        setAuditPage(0);
        return;
      }
      setEvents(result.items);
      setTotalEvents(result.totalElements);
      setAuditPages(result.totalPages);
    } catch (error) {
      setAuditError(errorMessage(error));
    } finally {
      setAuditLoading(false);
    }
  }

  async function loadSecurityData() {
    setLoading(true);
    setNotice("");
    const [sessionResult, mfaResult, passkeyResult, emailResult, protectionResult, signalResult, settingsResult] = await Promise.allSettled([
      listSessions(),
      mfaStatus(),
      listPasskeys(),
      onboardingStatus(),
      apiData<LoginProtectionStatus>("/api/v1/auth/login-protection"),
      apiData<SecuritySignalPage>("/api/v1/security/events/open?page=0&size=100"),
      apiData<OrganizationSecuritySettings>("/api/v1/organization/security-settings"),
    ]);

    if (sessionResult.status === "fulfilled") {
      setSessions(sessionResult.value);
      setSessionError("");
    } else {
      setSessionError(errorMessage(sessionResult.reason));
    }
    if (mfaResult.status === "fulfilled") {
      setMfaEnabled(mfaResult.value.enabled);
      setOrganizationMfaRequired(mfaResult.value.organizationRequired);
      setMfaError("");
    } else {
      setOrganizationMfaRequired(null);
      setMfaError(errorMessage(mfaResult.reason));
    }
    if (passkeyResult.status === "fulfilled") {
      setPasskeys(passkeyResult.value);
      setPasskeyError("");
    } else {
      setPasskeyError(errorMessage(passkeyResult.reason));
    }
    if (emailResult.status === "fulfilled") {
      setEmailVerified(emailResult.value.emailVerified);
      setEmailError("");
    } else {
      setEmailVerified(null);
      setEmailError(errorMessage(emailResult.reason));
    }
    if (protectionResult.status === "fulfilled") {
      setLoginProtection(protectionResult.value);
      setLoginProtectionError("");
    } else {
      setLoginProtection(null);
      setLoginProtectionError(errorMessage(protectionResult.reason));
    }
    if (signalResult.status === "fulfilled" && Array.isArray(signalResult.value.items)) {
      setAccountSignals(signalResult.value.items.filter((signal) =>
        signal.type === "REPEATED_FAILURES" && signal.subjectId === user?.id));
    } else {
      setAccountSignals([]);
    }
    if (settingsResult.status === "fulfilled") {
      setOrganizationSettings(settingsResult.value);
      setOrganizationPolicyError("");
    } else {
      setOrganizationSettings(null);
      setOrganizationPolicyError(errorMessage(settingsResult.reason));
    }
    setLoading(false);
  }

  useEffect(() => {
    void loadSecurityData();
  }, []);

  useEffect(() => {
    void loadAuditEvents();
  }, [auditPage]);

  const visibleEvents = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return events;
    return events.filter((event) => [
      event.action,
      event.resourceType,
      event.resourceId,
      event.actorUserId,
      event.requestId,
    ].some((value) => value?.toLowerCase().includes(needle)));
  }, [events, query]);

  async function endSession(session: SessionSummary) {
    setRevokingId(session.id);
    setSessionError("");
    setNotice("");
    try {
      await revokeSession(session.id);
      setSessions((current) => current.filter((item) => item.id !== session.id));
      setNotice(`Session ${session.id} was revoked.`);
    } catch (error) {
      setSessionError(errorMessage(error));
    } finally {
      setRevokingId("");
    }
  }

  async function signOutEverywhere() {
    if (!window.confirm("Sign out this account from all active sessions, including this one?")) return;
    setRevokingOthers(true);
    setSessionError("");
    try {
      await revokeOtherSessionsApi();
      await logout();
    } catch (error) {
      setSessionError(errorMessage(error));
      setRevokingOthers(false);
    }
  }

  async function startMfaEnrolment() {
    setMfaBusy(true);
    setMfaError("");
    setBackupCodes([]);
    setMfaCopied("");
    try {
      setMfaEnrolment(await beginMfaEnrolment());
      setMfaCode("");
    } catch (error) {
      setMfaError(errorMessage(error));
    }
    finally {
      setMfaBusy(false);
    }
  }

  async function confirmMfa() {
    setMfaBusy(true);
    setMfaError("");
    try {
      const result = await confirmMfaEnrolment(mfaCode.trim());
      setBackupCodes(result.codes);
      setRecoveryCodesCopied(false);
      setMfaEnabled(true);
      setMfaEnrolment(null);
      setMfaCode("");
      setNotice("Multi-factor authentication is enabled. Save your one-time recovery codes now.");
    } catch (error) {
      setMfaError(errorMessage(error));
    } finally {
      setMfaBusy(false);
    }
  }

  async function regenerateRecoveryCodes() {
    setRecoveryBusy(true);
    setMfaError("");
    setBackupCodes([]);
    try {
      const result = await regenerateMfaRecoveryCodes(recoveryCode.trim());
      setBackupCodes(result.codes);
      setRecoveryCodesCopied(false);
      setRecoveryCode("");
      setNotice("Recovery codes regenerated. The previous codes can no longer be used.");
    } catch (error) {
      setMfaError(errorMessage(error));
    } finally {
      setRecoveryBusy(false);
    }
  }

  async function sendVerificationEmail() {
    if (!user?.email) {
      setEmailError("Your account email could not be loaded. Refresh the page and try again.");
      return;
    }
    setEmailBusy(true);
    setEmailError("");
    setEmailNotice("");
    try {
      await resendEmailVerification(user.email);
      setEmailNotice("If your address needs verification, a verification message has been sent.");
    } catch (error) {
      setEmailError(errorMessage(error));
    } finally {
      setEmailBusy(false);
    }
  }

  async function copyMfaSetup(value: string, label: string) {
    try {
      await copyTextToClipboard(value);
      setMfaCopied(label);
      window.setTimeout(() => setMfaCopied(""), 1800);
    } catch {
      setMfaError("Clipboard access is unavailable. Copy the setup value directly.");
    }
  }

  async function copyRecoveryCodes() {
    try {
      await copyTextToClipboard(backupCodes.join("\n"));
      setRecoveryCodesCopied(true);
    } catch {
      setMfaError("Clipboard access is unavailable. Select and copy the recovery codes manually.");
    }
  }

  async function turnOffMfa() {
    if (organizationMfaRequired) {
      setMfaError("Your organization requires MFA. Ask an organization administrator to change that policy before disabling this factor.");
      return;
    }

    if (organizationMfaRequired === null) {
      setMfaError("Organization MFA policy could not be verified. Refresh the security data before disabling MFA.");
      return;
    }
    if (!mfaCurrentPassword.trim()) {
      setMfaError("Enter your current password to disable MFA.");
      return;
    }
    if (!mfaDisableCode.trim()) {
      setMfaError("Enter an authenticator or recovery code to disable MFA.");
      return;
    }
    if (!window.confirm("Disable multi-factor authentication for this account?")) return;
    setMfaBusy(true);
    setMfaError("");
    try {
      await disableMfa({
        currentPassword: mfaCurrentPassword,
        code: mfaDisableCode.trim(),
      });
      setMfaEnabled(false);
      setBackupCodes([]);
      setMfaEnrolment(null);
      setMfaCurrentPassword("");
      setMfaDisableCode("");
      setNotice("Multi-factor authentication was disabled.");
    } catch (error) {
      setMfaError(errorMessage(error));
    } finally {
      setMfaBusy(false);
    }
  }

  async function saveOrganizationMfaPolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!organizationSettings) return;
    setOrganizationPolicyBusy(true);
    setOrganizationPolicyError("");
    setOrganizationPolicyNotice("");
    try {
      const updated = await apiData<OrganizationSecuritySettings>("/api/v1/organization/security-settings", {
        method: "PUT",
        body: JSON.stringify({
          allowedAuthMethods: organizationSettings.allowedAuthMethods,
          sessionTtlMinutes: organizationSettings.sessionTtlMinutes,
          idleTimeoutMinutes: organizationSettings.idleTimeoutMinutes,
          maxSessions: organizationSettings.maxSessions,
          credentialMinLength: organizationSettings.credentialMinLength,
          credentialMaxLength: organizationSettings.credentialMaxLength,
          mfaRequired: organizationSettings.mfaRequired,
          mfaRequiredForAdmins: organizationSettings.mfaRequiredForAdmins,
          ipAllowlist: organizationSettings.ipAllowlist,
          securityEventTypes: organizationSettings.securityEventTypes,
        }),
      });
      setOrganizationSettings(updated);
      setOrganizationMfaRequired(updated.mfaRequired || updated.mfaRequiredForAdmins);
      setOrganizationPolicyNotice("MFA enforcement settings were saved. The policy takes effect at the next sign-in.");
    } catch (error) {
      setOrganizationPolicyError(errorMessage(error));
    } finally {
      setOrganizationPolicyBusy(false);
    }
  }

  async function addPasskey() {
    if (!passkeysAvailable()) {
      setPasskeyError("Passkeys need a supported browser and a secure connection (HTTPS or localhost).");
      return;
    }
    const displayName = passkeyName.trim();
    if (!displayName) {
      setPasskeyError("Name this passkey so you can recognize it later.");
      return;
    }
    setPasskeyBusy(true);
    setPasskeyError("");
    try {
      const options = await beginPasskeyRegistration();
      const credential = await createPasskey(options);
      const saved = await completePasskeyRegistration(options.challengeId, displayName, credential);
      setPasskeys((current) => [...current, saved]);
      setPasskeyName("");
      setNotice("Passkey added. It can now sign in without a password or complete a password sign-in.");
    } catch (error) {
      setPasskeyError(errorMessage(error));
    } finally {
      setPasskeyBusy(false);
    }
  }

  async function confirmRemovePasskey() {
    if (!removingPasskeyId) return;
    if (!passkeyCurrentPassword) {
      setPasskeyError("Enter your current password to remove a passkey.");
      return;
    }
    setPasskeyBusy(true);
    setPasskeyError("");
    try {
      await removePasskey(removingPasskeyId, passkeyCurrentPassword);
      setPasskeys((current) => current.filter((passkey) => passkey.id !== removingPasskeyId));
      setRemovingPasskeyId("");
      setPasskeyCurrentPassword("");
      setNotice("Passkey removed.");
    } catch (error) {
      setPasskeyError(errorMessage(error));
    } finally {
      setPasskeyBusy(false);
    }
  }

  const activeSessions = sessions.length;

  return (
    <>
      <PageHeader
        eyebrow="SECURITY"
        title={title}
        description="A focused workspace for account safeguards, organization policy, and access history."
        action={<div className="overview-header-actions">
          <button className="button button--secondary" type="button" disabled={loading || auditLoading} onClick={() => { void loadSecurityData(); void loadAuditEvents(); }}><RotateCw size={14} />{loading || auditLoading ? "Refreshing…" : "Refresh"}</button>
          <button className="button button--secondary" type="button" disabled={loading || revokingOthers || Boolean(revokingId)} onClick={() => void signOutEverywhere()}>{revokingOthers ? "Signing out…" : "Sign out all sessions"}</button>
        </div>}
      />
      {notice && <p className="security-feedback" role="status">{notice}</p>}

      <div className="security-center">
      <section className="security-center-hero" aria-label="Security overview">
        <span className="security-center-hero-icon" aria-hidden="true"><Shield size={22} /></span>
        <div className="security-center-hero-copy">
          <span className="security-center-kicker">PESAGUARD SECURITY</span>
          <h2>Your security, clearly in view.</h2>
          <p>Manage account protection, review organization controls, and investigate access activity from one place.</p>
        </div>
        <div className={`security-center-hero-meta${loginProtection?.locked || accountSignals.length > 0 ? " security-center-hero-meta--attention" : ""}`}>
          {loginProtection?.locked || accountSignals.length > 0 ? <ShieldAlert size={15} /> : <ShieldCheck size={15} />}
          {loginProtection?.locked || accountSignals.length > 0
            ? "Review account alerts"
            : mfaEnabled === null || emailVerified === null
              ? "Security status loading"
              : mfaEnabled && emailVerified
                ? "MFA & email verified"
                : "Security review recommended"}
        </div>
      </section>

      <div className="security-center-tabs" role="tablist" aria-label="Security center sections">
        {securityCenterTabs.map(({ id, label, description, icon: TabIcon }) => (
          <button
            className={`security-center-tab${activeTab === id ? " security-center-tab--active" : ""}`}
            id={`security-tab-${id}`}
            key={id}
            type="button"
            role="tab"
            aria-selected={activeTab === id}
            aria-controls={`security-panel-${id}`}
            tabIndex={activeTab === id ? 0 : -1}
            onClick={() => setActiveTab(id)}
            onKeyDown={(event) => handleSecurityTabKeyDown(event, id, setActiveTab)}
          >
            <span className="security-center-tab-icon"><TabIcon size={16} /></span>
            <span className="security-center-tab-copy"><strong>{label}</strong><small>{description}</small></span>
            <ChevronRight size={15} className="security-center-tab-arrow" />
          </button>
        ))}
      </div>

      <section
        className="security-center-panel"
        id={`security-panel-${activeTab}`}
        role="tabpanel"
        aria-labelledby={`security-tab-${activeTab}`}
        tabIndex={0}
      >
      {activeTab === "overview" && <>
      <section className="stats-grid stats-grid--three security-summary" aria-label="Security summary">
        <article className="stat-card"><div className="stat-card-top"><span>Organization MFA</span><ShieldCheck size={17} /></div><div className="stat-value">{organizationMfaRequired === null ? "—" : organizationMfaRequired ? "Required" : "Optional"}</div><div className="stat-foot">{organizationMfaRequired === null ? organizationPolicyError || "Checking organization policy…" : "Policy provided by the system"}</div></article>
        <article className="stat-card"><div className="stat-card-top"><span>Active sessions</span><Monitor size={17} /></div><div className="stat-value">{sessionError ? "—" : activeSessions}</div><div className="stat-foot">{sessionError || "Account sessions returned by system"}</div></article>
        <article className="stat-card"><div className="stat-card-top"><span>Unresolved sign-in alerts</span><ShieldAlert size={17} /></div><div className="stat-value">{accountSignals.length}</div><div className="stat-foot">{loginProtection?.locked ? "An active account lockout is also in effect" : loginProtectionError || (accountSignals.length === 0 ? "No open sign-in alerts reported" : "Review the flagged account activity")}</div></article>
      </section>
      <section className="panel security-account-panel" aria-labelledby="security-account-heading">
        <div className="panel-heading"><div><span className="security-center-section-kicker">ACCOUNT SNAPSHOT</span><h2 id="security-account-heading">Account security</h2><p>Verification, sign-in protection, and recovery options for this account.</p></div><ShieldCheck size={17} className="heading-icon" /></div>
        <div className="security-account-grid">
          <article className="security-account-card">
            <span className="security-account-icon"><MailCheck size={16} /></span>
            <div><strong>Email verification</strong><p>{emailVerified === null ? emailError || "Checking verification status…" : emailVerified ? `Verified${user?.email ? ` · ${user.email}` : ""}` : `Not verified${user?.email ? ` · ${user.email}` : ""}`}</p></div>
            {!emailVerified && <button className="text-button" type="button" disabled={emailBusy || emailVerified === null} onClick={() => void sendVerificationEmail()}>{emailBusy ? "Sending…" : "Send verification email"}</button>}
            {emailError && <span className="security-account-error" role="alert">{emailError}</span>}
            {emailNotice && <span className="security-account-success" role="status">{emailNotice}</span>}
          </article>
          <article className="security-account-card">
            <span className="security-account-icon"><ShieldAlert size={16} /></span>
            <div><strong>Login protection</strong><p>{loginProtectionError || (loginProtection === null ? "Checking sign-in status…" : loginProtection.locked ? `Temporarily locked until ${formatTime(loginProtection.lockedUntil ?? "")}` : "No active account sign-in lockout.")}</p>{accountSignals.length > 0 && <p className="security-account-warning">{accountSignals.length} unresolved repeated sign-in failure alert{accountSignals.length === 1 ? "" : "s"}.</p>}</div>
          </article>
          <article className="security-account-card">
            <span className="security-account-icon"><BellRing size={16} /></span>
            <div><strong>Security notifications</strong><p>Choose in-app and email delivery for security alerts.</p></div>
            <a className="text-button" href="/?page=notifications">Manage notifications</a>
          </article>
          <article className="security-account-card">
            <span className="security-account-icon"><KeyRound size={16} /></span>
            <div><strong>Account recovery</strong><p>Reset your password if you can’t sign in.</p></div>
            <a className="text-button" href="/forgot-password">Open recovery</a>
          </article>
        </div>
      </section>
      <div className="security-center-overview-footer">
        <div><span className="security-center-section-kicker">NEXT STEP</span><strong>{mfaEnabled === false || mfaEnabled === null ? "Strengthen your sign-in" : "Review trusted devices"}</strong><p>{mfaEnabled === false || mfaEnabled === null ? "Enable an authenticator or add a passkey to improve account protection." : "Confirm active sessions and revoke any device you no longer recognize."}</p></div>
        <button className="button button--secondary" type="button" onClick={() => setActiveTab(mfaEnabled === false || mfaEnabled === null ? "sign-in" : "sessions")}>Continue review<ChevronRight size={14} /></button>
      </div>
      </>}

      {activeTab === "organization" && <div className="security-center-policy-layout">
      <section className="panel security-controls-panel" aria-labelledby="organization-mfa-policy-heading">
        <div className="panel-heading"><div><h2 id="organization-mfa-policy-heading">MFA enforcement</h2><p>Choose whether members or administrators must enroll an authenticator. Enforcement starts at their next sign-in.</p></div><ShieldCheck size={17} className="heading-icon" /></div>
        {organizationPolicyError && <p className="notification-alert" role="alert">{organizationPolicyError}</p>}
        {organizationPolicyNotice && <p className="security-feedback" role="status">{organizationPolicyNotice}</p>}
        {organizationSettings
          ? <ValidatedForm className="security-account-grid" onSubmit={(event) => void saveOrganizationMfaPolicy(event)}>
          <label className="security-account-card">
            <input
              type="checkbox"
              checked={organizationSettings.mfaRequired}
              onChange={(event) => setOrganizationSettings((current) =>
                current ? { ...current, mfaRequired: event.target.checked } : current)}
            />
            <div><strong>Require MFA for everyone</strong><p>Members, administrators, and owners must verify with TOTP or an enrolled passkey.</p></div>
          </label>
          <label className="security-account-card">
            <input
              type="checkbox"
              checked={organizationSettings.mfaRequiredForAdmins}
              onChange={(event) => setOrganizationSettings((current) =>
                current ? { ...current, mfaRequiredForAdmins: event.target.checked } : current)}
            />
            <div><strong>Require MFA for administrators</strong><p>Require MFA for administrators and owners without changing the member sign-in policy.</p></div>
          </label>
          <div>
            <button className="button button--primary" type="submit" disabled={organizationPolicyBusy}>
              {organizationPolicyBusy ? "Saving…" : "Save enforcement"}
            </button>
          </div>
          </ValidatedForm>
          : <p className="security-empty-state">{loading ? "Loading organization policy…" : "Organization security policy is unavailable. Refresh to try again."}</p>}
      </section>
      {organizationSettings && <section className="panel security-controls-panel" aria-labelledby="organization-policy-heading">
        <div className="panel-heading"><div><span className="security-center-section-kicker">CURRENT CONTROLS</span><h2 id="organization-policy-heading">Organization policy</h2><p>Current policy values are reported by the organization security service.</p></div><Settings2 size={17} className="heading-icon" /></div>
        <div className="security-policy-grid security-center-policy-grid">
          <div className="security-policy-value"><small>Session lifetime</small><strong>{organizationSettings.sessionTtlMinutes} minutes</strong></div>
          <div className="security-policy-value"><small>Idle timeout</small><strong>{organizationSettings.idleTimeoutMinutes} minutes</strong></div>
          <div className="security-policy-value"><small>Maximum sessions</small><strong>{organizationSettings.maxSessions}</strong></div>
          <div className="security-policy-value"><small>Password length</small><strong>{organizationSettings.credentialMinLength}–{organizationSettings.credentialMaxLength} characters</strong></div>
          <div className="security-policy-value"><small>Allowed sign-in methods</small><strong>{organizationSettings.allowedAuthMethods.join(", ") || "None reported"}</strong></div>
          <div className="security-policy-value"><small>IP allowlist</small><strong>{organizationSettings.ipAllowlist.length ? `${organizationSettings.ipAllowlist.length} network rule(s)` : "No IP restrictions reported"}</strong></div>
        </div>
        <p className="security-center-updated-at">Last updated {formatTime(organizationSettings.updatedAt)}</p>
      </section>}
      </div>}

      {activeTab === "sign-in" && <div className="security-center-signin-layout">
      <section className="panel security-controls-panel" id="security-mfa" aria-label="Account multi-factor authentication">
        <div className="panel-heading"><div><h2>Multi-factor authentication</h2><p>Protect sign-in with an authenticator app. Recovery codes are displayed once after enrollment.</p></div><span className={`security-mfa-status${mfaEnabled ? " security-mfa-status--enabled" : ""}`} role="status">{mfaEnabled === null ? "Checking" : mfaEnabled ? "Enabled" : "Not enabled"}</span></div>
        {mfaError && <p className="notification-alert" role="alert">{mfaError}</p>}
        {mfaEnabled === null
          ? <p className="security-empty-state">{mfaError ? "Account MFA status is unavailable." : "Loading account MFA status…"}</p>
          : mfaEnabled
            ? <div className="security-mfa-enabled">
              <div className="security-mfa-enabled-summary"><ShieldCheck size={17} /><div><strong>Authenticator active</strong><span>{organizationMfaRequired ? "MFA is required by your organization." : "A one-time code is required for account sign-in."}</span></div></div>
              <ValidatedForm className="security-mfa-recovery-form" onSubmit={(event) => { event.preventDefault(); void regenerateRecoveryCodes(); }}>
                <label className="settings-field"><span>Regenerate recovery codes</span><input className="field-control" autoComplete="one-time-code" maxLength={32} value={recoveryCode} onChange={(event) => setRecoveryCode(event.target.value)} placeholder="Authenticator or unused recovery code" /></label>
                <button className="button button--secondary" type="submit" disabled={recoveryBusy || !recoveryCode.trim()}>{recoveryBusy ? "Regenerating…" : "Regenerate codes"}</button>
              </ValidatedForm>
              {!organizationMfaRequired && organizationMfaRequired !== null && <>
                <label className="settings-field"><span>Current password</span><input className="field-control" type="password" autoComplete="current-password" value={mfaCurrentPassword} onChange={(event) => setMfaCurrentPassword(event.target.value)} /></label>
                <label className="settings-field"><span>Authenticator or recovery code</span><input className="field-control" autoComplete="one-time-code" inputMode="text" maxLength={32} value={mfaDisableCode} onChange={(event) => setMfaDisableCode(event.target.value)} /></label>
              </>}
              {organizationMfaRequired
                ? <p className="security-mfa-policy-note">Your organization requires MFA. Ask an owner or administrator before removing this authenticator.</p>
                : organizationMfaRequired === null
                  ? <p className="security-mfa-policy-note">Organization policy could not be checked. Refresh before removing this authenticator.</p>
                  : <button className="button button--secondary" type="button" disabled={mfaBusy || !mfaCurrentPassword.trim() || !mfaDisableCode.trim()} onClick={() => void turnOffMfa()}>{mfaBusy ? "Updating…" : "Remove authenticator"}</button>}
            </div>
            : mfaEnrolment
              ? <div className="settings-groups">
                <p className="security-mfa-instructions"><strong>1. Add this account to your authenticator</strong><span>Copy the setup URI or enter the manual secret in your authenticator app.</span></p>
                <label className="settings-field"><span>Authenticator setup URI</span><div className="security-mfa-copy-field"><textarea className="field-control" readOnly rows={2} value={mfaEnrolment.provisioningUri} /><button className="button button--secondary" type="button" onClick={() => void copyMfaSetup(mfaEnrolment.provisioningUri, "uri")}>{mfaCopied === "uri" ? <Check size={14} /> : <Copy size={14} />}{mfaCopied === "uri" ? "Copied" : "Copy URI"}</button></div></label>
                <label className="settings-field"><span>Manual setup secret</span><div className="security-mfa-copy-field"><input className="field-control" readOnly value={mfaEnrolment.secret} /><button className="button button--secondary" type="button" onClick={() => void copyMfaSetup(mfaEnrolment.secret, "secret")}>{mfaCopied === "secret" ? <Check size={14} /> : <Copy size={14} />}{mfaCopied === "secret" ? "Copied" : "Copy secret"}</button></div></label>
                <ValidatedForm className="security-mfa-confirm" onSubmit={(event) => { event.preventDefault(); void confirmMfa(); }}>
                  <label className="settings-field"><span>2. Enter the 6-digit authenticator code</span><input className="field-control security-mfa-code" type="text" autoComplete="one-time-code" inputMode="numeric" pattern="[0-9]{6}" maxLength={6} required value={mfaCode} onChange={(event) => setMfaCode(event.target.value.replace(/\D/g, "").slice(0, 6))} /></label>
                  <button className="button button--primary" type="submit" disabled={mfaBusy || mfaCode.length !== 6}>{mfaBusy ? "Verifying code…" : "Verify and enable MFA"}</button>
                </ValidatedForm>
              </div>
              : <div>
                <p>{organizationMfaRequired ? "Organization policy requires MFA. Enroll an authenticator to secure sign-in." : "Add an authenticator app to protect sign-in."}</p>
                <button className="button button--primary" type="button" disabled={mfaBusy} onClick={() => void startMfaEnrolment()}>{mfaBusy ? "Starting enrollment…" : "Set up MFA"}</button>
              </div>}
        {backupCodes.length > 0 && <div className="notification-success" role="status">
          <strong>Save these recovery codes now. Each code can be used once when you cannot access your authenticator.</strong>
          <ul className="security-recovery-code-list">{backupCodes.map((code) => <li key={code}><code>{code}</code></li>)}</ul>
          <button className="button button--secondary" type="button" onClick={() => void copyRecoveryCodes()}>
            {recoveryCodesCopied ? <><Check size={14} /> Recovery codes copied</> : <><Copy size={14} /> Copy all recovery codes</>}
          </button>
        </div>}
      </section>

      <section className="panel security-controls-panel" id="security-passkeys" aria-label="Account passkeys">
        <div className="panel-heading"><div><h2>Passkeys</h2><p>Use a passkey for passwordless sign-in or as a second factor after your password. Registration and sign-in require device verification.</p></div><Fingerprint size={17} className="heading-icon" /></div>
        {!passkeysAvailable() && <p className="security-empty-state">This browser needs WebAuthn support and a secure connection (HTTPS or localhost) to use passkeys.</p>}
        {passkeyError && <p className="notification-alert" role="alert">{passkeyError}</p>}
        <div className="settings-groups">
          <label className="settings-field"><span>Passkey name</span><input className="field-control" autoComplete="off" maxLength={80} value={passkeyName} onChange={(event) => setPasskeyName(event.target.value)} placeholder="e.g. Work laptop" /></label>
          <button className="button button--primary" type="button" disabled={passkeyBusy || !passkeyName.trim() || !passkeysAvailable()} onClick={() => void addPasskey()}>
            {passkeyBusy && !removingPasskeyId ? "Waiting for device…" : "Add passkey"}
          </button>
        </div>
        {passkeys.length === 0
          ? <p className="security-empty-state">{passkeyError ? "Passkey inventory is unavailable." : loading ? "Loading passkeys…" : "No passkeys are registered yet."}</p>
          : <div className="security-policy-grid">
            {passkeys.map((passkey) => <article className="security-policy-value" key={passkey.id}>
              <strong>{passkey.displayName}</strong>
              <span>Added {formatTime(passkey.createdAt)} · {passkey.backedUp ? "synced backup" : passkey.backupEligible ? "backup eligible" : "device-bound"}</span>
              <span>{passkey.lastUsedAt ? `Last used ${formatTime(passkey.lastUsedAt)}` : "Not used yet"}</span>
              {removingPasskeyId === passkey.id
                ? <div className="settings-groups">
                  <label className="settings-field"><span>Current password</span><input className="field-control" type="password" autoComplete="current-password" value={passkeyCurrentPassword} onChange={(event) => setPasskeyCurrentPassword(event.target.value)} /></label>
                  <div className="overview-header-actions">
                    <button className="button button--secondary" type="button" disabled={passkeyBusy} onClick={() => { setRemovingPasskeyId(""); setPasskeyCurrentPassword(""); setPasskeyError(""); }}>Cancel</button>
                    <button className="button button--secondary" type="button" disabled={passkeyBusy || !passkeyCurrentPassword} onClick={() => void confirmRemovePasskey()}>{passkeyBusy ? "Removing…" : "Confirm removal"}</button>
                  </div>
                </div>
                : <button className="button button--secondary" type="button" disabled={passkeyBusy} onClick={() => { setRemovingPasskeyId(passkey.id); setPasskeyCurrentPassword(""); setPasskeyError(""); }}>Remove passkey</button>}
            </article>)}
          </div>}
      </section>

      <PasswordSettingsStep />
      </div>}

      {activeTab === "sessions" && <>
      <section className="panel security-session-panel" id="security-sessions">
        <div className="panel-heading"><div><span className="security-center-section-kicker">ACCOUNT ACCESS</span><h2>Active sessions</h2><p>Review signed-in devices and revoke access you no longer recognize. Your current session is protected from per-device revocation.</p></div><span className="table-tag">{sessionError ? "UNAVAILABLE" : `${sessions.length} ACTIVE`}</span></div>
        {sessionError && <p className="notification-alert" role="alert">{sessionError}</p>}
        {loading && sessions.length === 0
          ? <p className="security-empty-state">Loading account sessions…</p>
          : sessions.length === 0
            ? <p className="security-empty-state">No active sessions returned by the system.</p>
            : <div className="table-scroll"><table className="data-table security-table"><thead><tr><th>CLIENT</th><th>IP ADDRESS</th><th>LAST ACTIVE</th><th>EXPIRES</th><th></th></tr></thead><tbody>{sessions.map((session) => <tr key={session.id}><td><strong>{session.deviceLabel}</strong>{session.current && <span className="security-current-label">Current</span>}<code>{session.id}</code></td><td>{session.lastIp ?? "Not reported"}</td><td>{formatTime(session.lastSeenAt)}</td><td>{formatTime(session.expiresAt)}</td><td>{!session.current && <button className="text-button" type="button" disabled={Boolean(revokingId)} onClick={() => void endSession(session)}>{revokingId === session.id ? "Revoking…" : "Revoke session"}</button>}</td></tr>)}</tbody></table></div>}
      </section>
      </>}

      {activeTab === "audit" && <>
      <section className="panel security-audit-panel" id="security-audit-log">
        <div className="panel-heading"><div><span className="security-center-section-kicker">ORGANIZATION TRAIL</span><h2>Audit activity</h2><p>{auditLoading ? "Loading organization events…" : totalEvents === 0 ? "No records" : `Showing ${auditPage * 15 + 1}–${auditPage * 15 + events.length} of ${totalEvents} organization events`}</p></div><Clock3 size={17} className="heading-icon" /></div>
        {auditError && <p className="notification-alert" role="alert">{auditError}</p>}
        <label className="security-audit-search"><Search size={14} /><span className="visually-hidden">Search audit events on this page</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search this page by action, actor, resource, or request ID" /></label>
        <div className="security-audit-pagination" aria-label="Audit event pages">
          <button className="button button--secondary" type="button" disabled={auditLoading || auditPage === 0} onClick={() => setAuditPage((current) => Math.max(0, current - 1))}><ChevronLeft size={14} />Previous</button>
          <span aria-live="polite">Page {auditPage + 1} of {Math.max(1, auditPages)}</span>
          <button className="button button--secondary" type="button" disabled={auditLoading || auditPage + 1 >= auditPages} onClick={() => setAuditPage((current) => current + 1)}>Next<ChevronRight size={14} /></button>
        </div>
        {auditLoading
          ? <p className="security-empty-state">Loading audit events…</p>
          : visibleEvents.length === 0
            ? <p className="security-empty-state"><AlertTriangle size={16} />{events.length ? "No loaded events match this search." : "No audit events returned by the system."}</p>
            : <div className="table-scroll"><table className="data-table security-table"><thead><tr><th>SEQUENCE</th><th>ACTION</th><th>RESOURCE</th><th>ACTOR</th><th>REQUEST</th><th>TIME</th></tr></thead><tbody>{visibleEvents.map((event) => <tr key={event.id}><td>{event.sequenceNumber}</td><td><strong>{event.action}</strong></td><td>{event.resourceType}{event.resourceId ? <code>{event.resourceId}</code> : null}</td><td><code>{event.actorUserId}</code></td><td><code>{event.requestId}</code></td><td>{formatTime(event.createdAt)}</td></tr>)}</tbody></table></div>}
      </section>
      </>}
      </section>
      </div>
    </>
  );
}
