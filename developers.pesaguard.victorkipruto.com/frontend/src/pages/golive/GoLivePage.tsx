import { getUserMessage } from "../../lib/errors";
import { useCallback, useEffect, useState, type KeyboardEvent } from "react";
import { Activity, AlertTriangle, Check, CheckCircle2, Clipboard, Code2, ExternalLink, KeyRound, LoaderCircle, Rocket, Settings2, ShieldCheck, Webhook, XCircle } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";
import { copyTextToClipboard } from "../../lib/clipboard";
import { generateIdempotencyKey } from "../../lib/idempotency";
import { readActiveEnvironmentContext, setActiveEnvironment } from "../../lib/activeEnvironment";
import type { PageId } from "../../app/routes";

type GoLiveCheck = {
  checkId: string;
  name: string;
  category: string;
  severity: "BLOCKER" | "CRITICAL" | "WARNING" | "INFO";
  status: "PENDING" | "RUNNING" | "PASSED" | "FAILED" | "WARNING" | "SKIPPED" | "NOT_APPLICABLE";
  blocking: boolean;
  description: string;
  result: string;
  remediation: string | null;
  remediationRoute: string | null;
  checkedAt: string;
};

type Readiness = {
  verificationId: string | null;
  projectId: string;
  projectName: string;
  projectStatus: string;
  environmentId: string;
  environmentName: string;
  environmentStatus: string;
  baseUrl: string;
  state: "NOT_STARTED" | "IN_PROGRESS" | "NOT_READY" | "BLOCKED" | "READY" | "LAUNCHED" | "LIVE" | "SUSPENDED" | "REVOKED";
  readinessPercent: number;
  passedChecks: number;
  failedChecks: number;
  blockingChecks: number;
  warningChecks: number;
  canLaunch: boolean;
  verifiedAt: string | null;
  checks: GoLiveCheck[];
};

type Launch = {
  id: string;
  projectId: string;
  environmentId: string;
  verificationId: string;
  status: string;
  failureReason: string | null;
  startedAt: string;
  completedAt: string;
  requestId: string | null;
};

type VerificationJob = {
  id: string;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
  verificationId: string | null;
  failureReason: string | null;
  queuedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  result: Readiness | null;
};

type UsageSummary = {
  totalRequests: number;
  successfulRequests: number;
  failedRequests: number;
  errorRate: number;
  averageLatencyMs: number;
  p95LatencyMs: number;
};

type UsageSeries = { summary: UsageSummary };
type RequestLog = {
  requestId: string;
  endpoint: string;
  method: string;
  statusCode: number;
  latencyMs: number;
  occurredAt: string;
};
type RequestLogPage = { content: RequestLog[] };
type Delivery = {
  id: string;
  eventType: string;
  status: string;
  responseCode: number | null;
  latencyMs: number | null;
  createdAt: string;
};
type DeliveryPage = { items: Delivery[]; totalElements: number };
type GoLiveEnvironment = {
  id: string;
  projectId: string;
  name: string;
  type: string;
  status: string;
  baseUrl?: string;
};

type GoLivePageProps = {
  onNavigate: (page: PageId) => void;
  activeSection: GoLiveSectionId;
  onNavigateSection: (section: GoLiveSectionId) => void;
};

export const goLiveSections = [
  ["setup", "Production setup", "Environment, project, and live endpoint."],
  ["integration", "Integration", "Connect your application to Production."],
  ["credentials", "Credentials", "Issue and protect Production API keys."],
  ["webhooks", "Webhooks", "Secure event delivery endpoints."],
  ["verification", "Verification", "Run the complete readiness check."],
  ["launch", "Launch", "Activate live traffic when ready."],
  ["post-launch", "Post-Launch", "Monitor live requests and delivery health."],
] as const;
export type GoLiveSectionId = "readiness" | (typeof goLiveSections)[number][0];

const goLiveSectionIcons = {
  setup: Settings2,
  integration: Code2,
  credentials: KeyRound,
  webhooks: Webhook,
  verification: ShieldCheck,
  launch: Rocket,
  "post-launch": Activity,
} satisfies Record<(typeof goLiveSections)[number][0], typeof CheckCircle2>;

const goLiveSectionDescriptions = {
  setup: "Confirm your live project, Production environment, and canonical API endpoint.",
  integration: "Use the Production API host and developer tools to prepare your application.",
  credentials: "Create a least-privilege key bound only to this Production environment.",
  webhooks: "Review HTTPS transport, signing, and any remaining delivery requirements.",
  verification: "Run a fresh system verification; its READY result is valid for 15 minutes.",
  launch: "Review authorization and readiness before enabling live traffic.",
  "post-launch": "Inspect environment-scoped usage, API requests, and webhook deliveries.",
} satisfies Record<(typeof goLiveSections)[number][0], string>;

function handleGoLiveTabKeyDown(
  event: KeyboardEvent<HTMLButtonElement>,
  section: (typeof goLiveSections)[number][0],
  onNavigateSection: (section: GoLiveSectionId) => void,
) {
  const currentIndex = goLiveSections.findIndex(([id]) => id === section);
  const nextIndex = event.key === "ArrowRight"
    ? (currentIndex + 1) % goLiveSections.length
    : event.key === "ArrowLeft"
      ? (currentIndex - 1 + goLiveSections.length) % goLiveSections.length
      : event.key === "Home"
        ? 0
        : event.key === "End"
          ? goLiveSections.length - 1
          : -1;
  if (nextIndex < 0) return;
  event.preventDefault();
  const nextSection = goLiveSections[nextIndex][0];
  onNavigateSection(nextSection);
  window.requestAnimationFrame(() => {
    document.getElementById(`golive-tab-${nextSection}`)?.focus();
  });
}

function errorMessage(error: unknown) {
  return getUserMessage(error, "The request failed. Try again.");
}

function dateLabel(value: string | null | undefined) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function checkIcon(status: GoLiveCheck["status"]) {
  if (status === "PASSED") return <CheckCircle2 size={17} aria-hidden="true" />;
  if (status === "FAILED") return <XCircle size={17} aria-hidden="true" />;
  if (status === "WARNING") return <AlertTriangle size={17} aria-hidden="true" />;
  return <ShieldCheck size={17} aria-hidden="true" />;
}

function remediationPage(route: string | null): PageId | null {
  switch (route) {
    case "/?page=account-settings": return "account-settings";
    case "/?page=projects": return "projects";
    case "/?page=environments": return "environments";
    case "/?page=api-keys": return "api-keys";
    case "/?page=webhooks": return "webhooks";
    case "/?page=security-center": return "security-center";
    case "/?page=support-hub": return "support-hub";
    default: return null;
  }
}

export function GoLivePage({ onNavigate, activeSection, onNavigateSection }: GoLivePageProps) {
  const { isAuthenticated, hasPermission, permissionsLoaded, organization } = useAuth();
  const [context, setContext] = useState(readActiveEnvironmentContext);
  const [availableEnvironments, setAvailableEnvironments] = useState<GoLiveEnvironment[]>([]);
  const [environmentsLoading, setEnvironmentsLoading] = useState(false);
  const [environmentsError, setEnvironmentsError] = useState<string | null>(null);
  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [verifications, setVerifications] = useState<Readiness[]>([]);
  const [launches, setLaunches] = useState<Launch[]>([]);
  const [postLaunchUsage, setPostLaunchUsage] = useState<UsageSummary | null>(null);
  const [postLaunchRequests, setPostLaunchRequests] = useState<RequestLog[]>([]);
  const [postLaunchDeliveries, setPostLaunchDeliveries] = useState<Delivery[]>([]);
  const [postLaunchErrors, setPostLaunchErrors] = useState({
    usage: null as string | null,
    requests: null as string | null,
    deliveries: null as string | null,
  });
  const [postLaunchLoading, setPostLaunchLoading] = useState(false);
  const [postLaunchReload, setPostLaunchReload] = useState(0);
  const [postLaunchRefreshedAt, setPostLaunchRefreshedAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [verificationEstimatedFinish, setVerificationEstimatedFinish] = useState<number | null>(null);
  const [verificationClock, setVerificationClock] = useState(() => Date.now());
  const [launching, setLaunching] = useState(false);
  const [changingState, setChangingState] = useState(false);
  const [confirmLaunch, setConfirmLaunch] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => setVerificationClock(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [running]);

  useEffect(() => {
    const updateContext = () => {
      setContext(readActiveEnvironmentContext());
      setReadiness(null);
      setVerifications([]);
      setLaunches([]);
      setPostLaunchUsage(null);
      setPostLaunchRequests([]);
      setPostLaunchDeliveries([]);
      setPostLaunchErrors({ usage: null, requests: null, deliveries: null });
      setPostLaunchRefreshedAt(null);
      setError(null);
      setNotice(null);
    };
    window.addEventListener("pesaguard:active-context-changed", updateContext);
    return () => window.removeEventListener("pesaguard:active-context-changed", updateContext);
  }, []);

  const basePath = context.projectId && context.environmentId
    ? `/api/v1/projects/${encodeURIComponent(context.projectId)}/environments/${encodeURIComponent(context.environmentId)}/go-live`
    : "";
  const isProduction = context.environmentType.toUpperCase() === "PRODUCTION";
  const canViewGoLive = hasPermission("production:view");
  const canVerifyProduction = hasPermission("production:verify");
  const canReadUsage = hasPermission("usage:read");
  const canReadWebhooks = hasPermission("webhook:read");

  useEffect(() => {
    if (!isAuthenticated || !context.projectId) {
      setAvailableEnvironments([]);
      setEnvironmentsError(null);
      return;
    }
    const controller = new AbortController();
    setEnvironmentsLoading(true);
    setEnvironmentsError(null);
    void apiData<GoLiveEnvironment[]>(
      `/api/v1/projects/${encodeURIComponent(context.projectId)}/environments`,
      { signal: controller.signal },
    ).then((environments) => {
      if (!Array.isArray(environments)) throw new Error("The environments API returned an invalid list.");
      if (!controller.signal.aborted) setAvailableEnvironments(environments);
    }).catch((cause: unknown) => {
      if (!controller.signal.aborted) {
        setEnvironmentsError(errorMessage(cause));
        setAvailableEnvironments([]);
      }
    }).finally(() => {
      if (!controller.signal.aborted) setEnvironmentsLoading(false);
    });
    return () => controller.abort();
  }, [context.projectId, isAuthenticated]);

  const load = useCallback(async () => {
    if (!isAuthenticated || !basePath || !isProduction) {
      setReadiness(null);
      setVerifications([]);
      setLaunches([]);
      return;
    }
    if (!permissionsLoaded) return;
    if (!canViewGoLive) {
      setReadiness(null);
      setVerifications([]);
      setLaunches([]);
      return;
    }
    setLoading(true);
    setError(null);
    const [readinessResult, verificationResult, launchResult] = await Promise.allSettled([
      apiData<Readiness>(`${basePath}/readiness`),
      apiData<Readiness[]>(`${basePath}/verifications`),
      apiData<Launch[]>(`${basePath}/launches`),
    ]);
    if (readinessResult.status === "fulfilled") setReadiness(readinessResult.value);
    else setError(errorMessage(readinessResult.reason));
    setVerifications(verificationResult.status === "fulfilled" ? verificationResult.value : []);
    setLaunches(launchResult.status === "fulfilled" ? launchResult.value : []);
    setLoading(false);
  }, [basePath, canViewGoLive, isAuthenticated, isProduction, permissionsLoaded]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (activeSection !== "post-launch" || !isAuthenticated || !basePath || !isProduction
        || !permissionsLoaded) {
      return;
    }
    let active = true;
    const to = new Date();
    const from = new Date(to.getTime() - 7 * 24 * 60 * 60 * 1000);
    const query = new URLSearchParams({
      projectId: context.projectId,
      environmentId: context.environmentId,
      from: from.toISOString(),
      to: to.toISOString(),
      granularity: "DAY",
    });
    setPostLaunchLoading(true);
    setPostLaunchErrors({ usage: null, requests: null, deliveries: null });
    void Promise.allSettled([
      canReadUsage
        ? apiData<UsageSeries>(`/api/v1/usage?${query.toString()}`)
        : Promise.resolve(null),
      canReadUsage
        ? apiData<RequestLogPage>(`/api/v1/usage/requests?projectId=${encodeURIComponent(context.projectId)}&environmentId=${encodeURIComponent(context.environmentId)}&page=0&size=10`)
        : Promise.resolve(null),
      canReadWebhooks
        ? apiData<DeliveryPage>(`/api/v1/events/deliveries?projectId=${encodeURIComponent(context.projectId)}&environmentId=${encodeURIComponent(context.environmentId)}&page=0&size=10`)
        : Promise.resolve(null),
    ]).then(([usageResult, requestResult, deliveryResult]) => {
      if (!active) return;
      setPostLaunchUsage(usageResult.status === "fulfilled" ? usageResult.value?.summary ?? null : null);
      setPostLaunchRequests(requestResult.status === "fulfilled" ? requestResult.value?.content ?? [] : []);
      setPostLaunchDeliveries(deliveryResult.status === "fulfilled" ? deliveryResult.value?.items ?? [] : []);
      setPostLaunchErrors({
        usage: canReadUsage && usageResult.status === "rejected" ? errorMessage(usageResult.reason) : null,
        requests: canReadUsage && requestResult.status === "rejected" ? errorMessage(requestResult.reason) : null,
        deliveries: canReadWebhooks && deliveryResult.status === "rejected" ? errorMessage(deliveryResult.reason) : null,
      });
      setPostLaunchRefreshedAt(new Date().toISOString());
    }).finally(() => {
      if (active) setPostLaunchLoading(false);
    });
    return () => { active = false; };
  }, [activeSection, basePath, canReadUsage, canReadWebhooks, context.environmentId,
    context.projectId, isAuthenticated, isProduction, permissionsLoaded, postLaunchReload]);

  async function runVerification() {
    if (!basePath) return;
    const estimateFinish = Date.now() + 30_000;
    setVerificationEstimatedFinish(estimateFinish);
    setVerificationClock(Date.now());
    setRunning(true);
    setError(null);
    setNotice(null);
    try {
      let job = await apiData<VerificationJob>(`${basePath}/verifications`, {
        method: "POST",
        headers: { "Idempotency-Key": generateIdempotencyKey() },
      });
      const verificationDeadline = Date.now() + 30_000;
      while ((job.status === "QUEUED" || job.status === "RUNNING")
        && Date.now() < verificationDeadline) {
        job = await apiData<VerificationJob>(`${basePath}/verifications/${encodeURIComponent(job.id)}`);
        if (job.status === "QUEUED" || job.status === "RUNNING") {
          await new Promise<void>((resolve) => window.setTimeout(resolve, 500));
        }
      }
      if (job.status === "FAILED") {
        setError("Readiness verification could not be completed. Review the checks and try again.");
        return;
      }
      if (job.status !== "COMPLETED" || !job.result) {
        setNotice("Verification is still processing. Check the readiness page again shortly.");
        return;
      }
      const result = job.result;
      setReadiness(result);
      setVerifications((current) => [result, ...current.filter((item) => item.verificationId !== result.verificationId)]);
      setNotice("Verification completed. Readiness and launch eligibility were computed by the system.");
    } catch (verificationError) {
      setError(errorMessage(verificationError));
    } finally {
      setRunning(false);
      setVerificationEstimatedFinish(null);
    }
  }

  const verificationEstimateSeconds = verificationEstimatedFinish === null
    ? null
    : Math.max(0, Math.ceil((verificationEstimatedFinish - verificationClock) / 1000));
  const verificationEstimateLabel = verificationEstimatedFinish === null
    ? null
    : new Intl.DateTimeFormat(undefined, { timeStyle: "short" })
        .format(new Date(verificationEstimatedFinish));

  async function launchProduction() {
    if (!basePath) return;
    setLaunching(true);
    setError(null);
    setNotice(null);
    try {
      const result = await apiData<Launch>(`${basePath}/launches`, {
        method: "POST",
        headers: { "Idempotency-Key": generateIdempotencyKey() },
      });
      setConfirmLaunch(false);
      if (result.status === "LIVE") {
        setNotice(`Production launch completed. Launch reference: ${result.id}`);
      } else {
        const failure = `Production launch couldn't be completed. Review the failed readiness checks and try again. Reference: ${result.id}`;
        await load();
        setError(failure);
        return;
      }
      await load();
    } catch (launchError) {
      setError(errorMessage(launchError));
    } finally {
      setLaunching(false);
    }
  }

  async function revokeProduction() {
    if (!basePath) return;
    if (!window.confirm("Revoke Go-Live for this Production environment? Live access will stop, and a new launch will be required.")) return;
    setChangingState(true);
    setError(null);
    setNotice(null);
    try {
      await apiData<Launch>(`${basePath}/revoke`, { method: "POST" });
      await load();
      setNotice("Go-Live has been revoked. A new readiness verification and launch are required to enable Production again.");
    } catch (stateError) {
      setError(errorMessage(stateError));
    } finally {
      setChangingState(false);
    }
  }

  async function copyBaseUrl() {
    if (!readiness?.baseUrl) return;
    try {
      await copyTextToClipboard(readiness.baseUrl);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      setError("Could not copy the Production API URL. Copy it manually from the page.");
    }
  }

  function checksView(checks: GoLiveCheck[]) {
    if (loading && !readiness) return <p className="security-empty-state">Checking Production readiness…</p>;
    if (checks.length === 0) return <p className="security-empty-state">No check results are available yet. Run verification to load current results.</p>;
    return <div className="golive-check-list">{checks.map((check) => (
      <article className={`golive-check golive-check--${check.status.toLowerCase()}`} key={check.checkId}>
        <span className="golive-check__icon">{checkIcon(check.status)}</span>
        <div className="golive-check__content">
          <div className="golive-check__title"><strong>{check.name}</strong><span>{check.category}</span></div>
          <p>{check.description}</p>
          <small>{check.result}</small>
          {check.remediation && <div className="golive-check__remediation">
            <p>Next step: {check.remediation}</p>
            {remediationPage(check.remediationRoute) && <button type="button" onClick={() => {
              const page = remediationPage(check.remediationRoute);
              if (page) onNavigate(page);
            }}>Fix issue</button>}
          </div>}
        </div>
        <span className="golive-check__status">{check.status}</span>
      </article>
    ))}</div>;
  }

  function selectProductionEnvironment(environmentId: string) {
    const environment = availableEnvironments.find((item) => item.id === environmentId);
    if (!environment) return;
    setActiveEnvironment(
      { id: context.projectId, name: context.projectName || readiness?.projectName || "Selected project" },
      environment,
    );
  }

  return (
    <>
      <PageHeader
        eyebrow="BUILD · GO-LIVE"
        title="Go Live"
        description="Follow the readiness steps, verify your Production setup, and activate live traffic. Billing is currently disabled and is not required."
        action={<button className="button button--secondary" type="button" onClick={() => {
          void load();
          if (activeSection === "post-launch") setPostLaunchReload((current) => current + 1);
        }} disabled={loading || running || postLaunchLoading}><LoaderCircle size={15} className={loading || postLaunchLoading ? "usage-refresh-icon" : ""} />{loading || postLaunchLoading ? "Refreshing…" : "Refresh"}</button>}
      />

      {error && <p className="notification-alert golive-feedback golive-feedback--error" role="alert">{error}</p>}
      {notice && <p className="notification-success golive-feedback golive-feedback--success" role="status">{notice}</p>}
      {isAuthenticated && <section className="golive-context" aria-label="Go-Live context">
        <div><span>Organization</span><strong>{organization?.name ?? "Unavailable"}</strong></div>
        <div><span>Project</span><strong>{context.projectName || "Select a project"}</strong></div>
        <div><span>Environment</span><strong>{context.environmentName || "Select an environment"}</strong></div>
        <div><span>Production state</span><strong>{readiness?.state ?? (loading ? "CHECKING" : "NOT_STARTED")}</strong></div>
      </section>}
      {isAuthenticated && permissionsLoaded && <p className="golive-permission-note">
        {isProduction
          ? <>Go-Live access: {canViewGoLive ? "view" : "no view"} · {canVerifyProduction ? "verify" : "no verify"} ·
            {" "}{hasPermission("production:launch") ? "launch" : "no launch"} ·
            {" "}{hasPermission("production:suspend") ? "suspend" : "no suspend"} ·
            {" "}{hasPermission("production:resume") ? "resume" : "no resume"}</>
          : "Select a Production environment to load its Go-Live access and readiness checks."}
      </p>}

      {!isAuthenticated ? (
        <section className="panel golive-empty"><ShieldCheck size={24} /><h2>Sign in to continue</h2><p>Go-Live checks are available to authorized project members.</p></section>
      ) : !context.projectId || !context.environmentId ? (
        <section className="panel golive-empty"><ShieldCheck size={24} /><h2>Select a project and environment</h2><p>Go-Live operates within the project and environment selected in the workspace context bar.</p><button className="button button--secondary" type="button" onClick={() => onNavigate("environments")}>Open environments</button></section>
      ) : !isProduction ? (
        <section className="panel golive-environment-prompt">
          <div className="golive-environment-prompt__icon"><AlertTriangle size={20} /></div>
          <div className="golive-environment-prompt__copy">
            <span className="golive-journey__eyebrow">STEP 1 · SELECT YOUR TARGET</span>
            <h2>You’re currently working in {context.environmentName || "Sandbox"}</h2>
            <p>Sandbox is for testing. Select an existing Production environment to see its readiness checks and continue the launch steps.</p>
            {environmentsError && <p className="form-error" role="alert">Could not load environments: {environmentsError}</p>}
            <div className="golive-environment-prompt__actions">
              <label htmlFor="golive-production-environment">Production environment</label>
              <select id="golive-production-environment"
                value={availableEnvironments.some((environment) => environment.id === context.environmentId
                  && environment.type.toUpperCase() === "PRODUCTION") ? context.environmentId : ""}
                disabled={environmentsLoading || !availableEnvironments.some((environment) => environment.type.toUpperCase() === "PRODUCTION")}
                onChange={(event) => selectProductionEnvironment(event.target.value)}>
                <option value="">{environmentsLoading ? "Loading environments…" : "Select Production"}</option>
                {availableEnvironments.filter((environment) => environment.type.toUpperCase() === "PRODUCTION").map((environment) =>
                  <option key={environment.id} value={environment.id}>{environment.name} · {environment.status}</option>)}
              </select>
              {!environmentsLoading && !environmentsError
                && !availableEnvironments.some((environment) => environment.type.toUpperCase() === "PRODUCTION")
                && <span>No Production environment exists for this project yet.</span>}
              <button className="button button--secondary" type="button" onClick={() => onNavigate("environments")}>
                {availableEnvironments.some((environment) => environment.type.toUpperCase() === "PRODUCTION")
                  ? "Manage environments" : "Create Production environment"}
                <ExternalLink size={14} />
              </button>
            </div>
          </div>
        </section>
      ) : !permissionsLoaded ? (
        <section className="panel golive-empty"><LoaderCircle size={22} /><h2>Checking Go-Live access</h2><p>Loading your account permissions.</p></section>
      ) : !canViewGoLive ? (
        <section className="panel golive-empty">
          <ShieldCheck size={24} /><h2>Go-Live access is required</h2>
          <p>Your account does not include <code>production:view</code>. Contact PesaGuard support if you believe this access is missing.</p>
        </section>
      ) : (
        <>
          <section className={`golive-summary golive-summary--${(readiness?.state ?? "IN_PROGRESS").toLowerCase()}`}>
            <div className="golive-summary__main">
              <div className="golive-summary__eyebrow"><span className={`golive-state golive-state--${(readiness?.state ?? "IN_PROGRESS").toLowerCase()}`} />LIVE ENVIRONMENT · {readiness?.state ?? "CHECKING"}</div>
              <h2>{readiness?.projectName ?? (context.projectName || "Selected project")}<span className="golive-summary__environment">{readiness?.environmentName ?? (context.environmentName || "Production")}</span></h2>
              <p>{readiness?.baseUrl ?? "Production API URL is loading"}</p>
              <div className="golive-progress" aria-label={`Readiness ${readiness?.readinessPercent ?? 0}%`}>
                <span key={readiness?.readinessPercent ?? 0} style={{ width: `${readiness?.readinessPercent ?? 0}%` }} />
              </div>
              <div className="golive-summary__metrics">
                <span><strong>{readiness?.readinessPercent ?? 0}%</strong> ready</span>
                <span><strong>{readiness?.passedChecks ?? 0}</strong> checks passed</span>
                <span><strong>{readiness?.blockingChecks ?? 0}</strong> blockers</span>
                <span className="golive-summary__verified">Verified {dateLabel(readiness?.verifiedAt)}</span>
              </div>
            </div>
            <div className="golive-summary__actions">
              {running && verificationEstimateSeconds !== null && verificationEstimateLabel && <p className="golive-note" role="status" aria-live="polite">
                {verificationEstimateSeconds > 0
                  ? `Estimated completion: around ${verificationEstimateLabel} (about ${verificationEstimateSeconds}s). This is an approximation; the result updates as soon as checks finish.`
                  : "The approximate 30-second window has passed. Verification is still running; keep this page open while it completes."}
              </p>}
              {!permissionsLoaded
                ? <p className="golive-note">Checking the active role's verification permission…</p>
                : canVerifyProduction
                  ? <button className="button button--secondary" type="button" onClick={() => void runVerification()} disabled={running || loading}>
                    <ShieldCheck size={15} />{running ? "Verifying…" : "Run verification"}
                  </button>
                  : <p className="golive-note">Your role does not have `production:verify`; ask an organization owner or admin to grant access.</p>}
              {readiness?.state === "LIVE" && permissionsLoaded && hasPermission("production:suspend") && <button className="button button--secondary" type="button" onClick={() => void revokeProduction()} disabled={changingState || launching}>
                {changingState ? "Revoking…" : "Revoke Go-Live"}
              </button>}
              {permissionsLoaded && hasPermission("production:launch") && <button className="button button--primary" type="button" onClick={() => setConfirmLaunch(true)} disabled={!readiness?.canLaunch || launching || readiness.state === "LIVE"}>
                <Rocket size={15} />{readiness?.state === "LIVE" ? "Live" : "Launch Production"}
              </button>}
            </div>
          </section>

          <section className="golive-workspace">
            <div className="golive-workspace__heading">
              <div><span>GO-LIVE WORKSPACE</span><h2>Production launch path</h2></div>
              <button
                id="golive-tab-readiness"
                className={`golive-overview-link${activeSection === "readiness" ? " golive-overview-link--active" : ""}`}
                type="button"
                aria-current={activeSection === "readiness" ? "page" : undefined}
                onClick={() => onNavigateSection("readiness")}>
                <CheckCircle2 size={15} />Readiness overview
              </button>
            </div>
            <div className="golive-tabs" role="tablist" aria-label="Production launch stages">
              {goLiveSections.map(([id, label], index) => {
                const Icon = goLiveSectionIcons[id];
                const active = activeSection === id;
                return <button key={id} type="button"
                  id={`golive-tab-${id}`}
                  role="tab"
                  aria-selected={active}
                  aria-controls="golive-tab-panel"
                  tabIndex={active ? 0 : -1}
                  className={`golive-tab${active ? " golive-tab--active" : ""}`}
                  onClick={() => onNavigateSection(id)}
                  onKeyDown={(event) => handleGoLiveTabKeyDown(event, id, onNavigateSection)}>
                  <span className="golive-tab__index">{String(index + 1).padStart(2, "0")}</span>
                  <Icon className="golive-tab__icon" size={16} aria-hidden="true" />
                  <span className="golive-tab__label">{label}</span>
                </button>;
              })}
            </div>
          </section>

          <div
            key={activeSection}
            id="golive-tab-panel"
            className={`golive-tab-panel golive-tab-panel--${activeSection}${running ? " golive-tab-panel--verifying" : ""}${launching ? " golive-tab-panel--launching" : ""}${readiness?.state === "LIVE" ? " golive-tab-panel--live" : ""}`}
            role="tabpanel"
            aria-labelledby={activeSection === "readiness" ? "golive-tab-readiness" : `golive-tab-${activeSection}`}
            tabIndex={0}>
            <header className={`golive-stage-heading golive-stage-heading--${activeSection}`}>
              <span className="golive-stage-heading__icon">
                {activeSection === "readiness"
                  ? <CheckCircle2 size={21} aria-hidden="true" />
                  : (() => {
                    const Icon = goLiveSectionIcons[activeSection];
                    return <Icon size={21} aria-hidden="true" />;
                  })()}
              </span>
              <div>
                <span className="golive-stage-heading__eyebrow">{activeSection === "readiness" ? "OVERVIEW" : `STAGE ${String(goLiveSections.findIndex(([id]) => id === activeSection) + 1).padStart(2, "0")} / 07`}</span>
                <h2>{activeSection === "readiness" ? "Readiness overview" : goLiveSections.find(([id]) => id === activeSection)?.[1]}</h2>
                <p>{activeSection === "readiness"
                  ? "See what is ready, what needs attention, and what stands between this environment and live traffic."
                  : goLiveSectionDescriptions[activeSection]}</p>
              </div>
              <span className={`golive-stage-heading__state golive-stage-heading__state--${(readiness?.state ?? "checking").toLowerCase()}`}>
                <i />{readiness?.state ?? "CHECKING"}
              </span>
            </header>
          {activeSection === "readiness" && <section className="golive-readiness" aria-label="Production readiness overview">
            <header className="golive-readiness__top">
              <div className="golive-readiness__summary">
                <span className={`golive-readiness__indicator golive-readiness__indicator--${(readiness?.state ?? "checking").toLowerCase()}`} />
                <div>
                  <span className="golive-readiness__eyebrow">PRODUCTION READINESS</span>
                  <h3>{readiness?.state === "LIVE" ? "Production is live"
                    : readiness?.state === "REVOKED" ? "Go-Live has been revoked"
                    : readiness?.state === "SUSPENDED" ? "Production is suspended"
                      : readiness?.blockingChecks
                        ? `${readiness.blockingChecks} item${readiness.blockingChecks === 1 ? "" : "s"} need attention`
                        : readiness?.canLaunch ? "Ready for launch review" : "Verification required"}</h3>
                  <p>{readiness?.state === "LIVE"
                    ? "Live status is persisted for this Production environment."
                    : readiness?.state === "REVOKED"
                      ? "The previous live launch was revoked. A fresh verification and launch are required."
                    : readiness?.state === "SUSPENDED"
                      ? "The existing launch is retained; resume Production when you are ready."
                      : "Current environment checks and the latest verification determine launch eligibility."}</p>
                </div>
              </div>
              <div className="golive-readiness__score">
                <strong>{readiness?.readinessPercent ?? 0}<span>%</span></strong>
                <small>ready</small>
              </div>
            </header>
            <div className="golive-readiness__progress" role="progressbar"
              aria-label="Production readiness" aria-valuemin={0} aria-valuemax={100}
              aria-valuenow={readiness?.readinessPercent ?? 0}>
              <span style={{ width: `${readiness?.readinessPercent ?? 0}%` }} />
            </div>
            <div className="golive-readiness__metrics">
              <span><strong>{readiness?.passedChecks ?? 0}</strong> passed</span>
              <span><strong>{readiness?.blockingChecks ?? 0}</strong> blockers</span>
              <span><strong>{readiness?.warningChecks ?? 0}</strong> warnings</span>
              <span className="golive-readiness__verified">{readiness?.verifiedAt
                ? `Last verified ${dateLabel(readiness.verifiedAt)}`
                : "No verification recorded"}</span>
            </div>
            <div className="golive-readiness__checks" aria-label="Readiness checks">
              {loading && !readiness
                ? <p className="security-empty-state">Checking Production readiness…</p>
                : !readiness?.checks.length
                  ? <p className="security-empty-state">No checks are available yet. Run a verification to load the current report.</p>
                  : readiness.checks.map((check) => {
                    const fixPage = remediationPage(check.remediationRoute);
                    return <article className={`golive-readiness-check golive-readiness-check--${check.status.toLowerCase()}`} key={check.checkId}>
                      <span className="golive-readiness-check__icon">{checkIcon(check.status)}</span>
                      <div className="golive-readiness-check__detail">
                        <div className="golive-readiness-check__title"><strong>{check.name}</strong><span>{check.category}</span></div>
                        <p>{check.result || check.description}</p>
                        {check.status === "FAILED" && check.remediation && <small>{check.remediation}</small>}
                      </div>
                      <span className="golive-readiness-check__status">{check.status.replaceAll("_", " ")}</span>
                      {check.status === "FAILED" && fixPage && <button type="button" onClick={() => onNavigate(fixPage)}>Fix</button>}
                    </article>;
                  })}
            </div>
            <footer className="golive-readiness__actions">
              <span>{readiness?.verifiedAt && readiness.canLaunch
                ? `Verification expires ${dateLabel(new Date(new Date(readiness.verifiedAt).getTime() + 15 * 60_000).toISOString())}`
                : readiness?.verifiedAt && readiness.state !== "LIVE" && readiness.state !== "SUSPENDED"
                  ? "Run a fresh verification before launching."
                  : readiness?.state === "LIVE" ? "Live state remains active until Go-Live is explicitly revoked."
                    : readiness?.state === "SUSPENDED" ? "The existing launch is retained. Use Resume Production when its readiness checks pass."
                    : "A fresh successful verification is required to launch."}</span>
              {permissionsLoaded && canVerifyProduction && readiness?.state !== "LIVE" && readiness?.state !== "SUSPENDED"
                && <button className="button button--secondary" type="button" onClick={() => void runVerification()} disabled={running || loading}>
                  <ShieldCheck size={15} />{running ? "Verifying…" : "Run verification"}
                </button>}
              {permissionsLoaded && hasPermission("production:launch") && readiness?.state !== "LIVE" && readiness?.state !== "SUSPENDED"
                && <button className="button button--primary" type="button" onClick={() => setConfirmLaunch(true)} disabled={!readiness?.canLaunch || launching}>
                  <Rocket size={15} />{readiness?.canLaunch ? "Review and launch" : "Launch locked"}
                </button>}
              {permissionsLoaded && !canVerifyProduction && readiness?.state !== "LIVE" && readiness?.state !== "SUSPENDED"
                && <small>Ask an organization admin for `production:verify` to run these checks.</small>}
            </footer>
          </section>}

          {activeSection === "setup" && <section className="golive-grid" aria-label="Production setup">
            <article className="panel golive-card golive-setup">
              <div className="golive-setup-banner">
                <span className="golive-setup-banner__mark"><Rocket size={20} /></span>
                <div><span>PRODUCTION CONTROL PLANE</span><strong>{readiness?.environmentStatus ?? "Checking environment…"}</strong></div>
                <span className="golive-setup-banner__live"><i />Live target</span>
              </div>
              <div className="panel-heading"><div><h2>Production setup</h2><p>Live environment details</p></div><Rocket size={17} className="heading-icon" /></div>
              <dl className="golive-details">
                <div><dt>Project</dt><dd>{readiness?.projectName ?? (context.projectName || "—")}</dd></div>
                <div><dt>Project status</dt><dd>{readiness?.projectStatus ?? "Checking…"}</dd></div>
                <div><dt>Environment</dt><dd>{readiness?.environmentName ?? (context.environmentName || "Production")}</dd></div>
                <div><dt>Environment status</dt><dd>{readiness?.environmentStatus ?? "Checking…"}</dd></div>
                <div className="golive-details__url"><dt>Base URL</dt><dd><code>{readiness?.baseUrl ?? "—"}</code><button className="icon-button" aria-label={copied ? "Copied Production API URL" : "Copy Production API URL"} onClick={() => void copyBaseUrl()} disabled={!readiness?.baseUrl}>{copied ? <Check size={15} /> : <Clipboard size={15} />}</button></dd></div>
              </dl>
              <div className="golive-links"><button type="button" onClick={() => onNavigate("environments")}>Manage environments <ExternalLink size={14} /></button></div>
            </article>
          </section>}

          {activeSection === "integration" && <section className="golive-grid">
            <article className="panel golive-card">
              <div className="golive-integration-terminal">
                <div className="golive-integration-terminal__bar"><span /><span /><span /><small>PRODUCTION ENDPOINT</small></div>
                <span className="golive-integration-terminal__label">BASE URL</span>
                <code>{readiness?.baseUrl ?? "Loading canonical Production URL…"}</code>
                <div className="golive-integration-terminal__state"><i />Assigned by PesaGuard · HTTPS</div>
              </div>
              <div className="panel-heading"><div><h2>Integration tools</h2><p>Use existing project-scoped tools before moving live traffic.</p></div><ExternalLink size={17} className="heading-icon" /></div>
              <div className="golive-links">
                <button type="button" onClick={() => onNavigate("api-explorer")}>Open API Explorer <ExternalLink size={14} /></button>
                <button type="button" onClick={() => onNavigate("api-catalog")}>Review API reference <ExternalLink size={14} /></button>
              </div>
            </article>
            <article className="panel golive-card">
              <div className="panel-heading"><div><h2>Production API</h2><p>Canonical destination returned by the system</p></div></div>
              <code className="golive-integration-url">{readiness?.baseUrl ?? "Load readiness to retrieve the Production API URL."}</code>
              <p className="golive-note">The platform does not run a live connectivity or transaction probe from this page.</p>
            </article>
          </section>}

          {activeSection === "credentials" && <section className="golive-grid">
            <article className="panel golive-card"><div className="panel-heading"><div><h2>Production credentials</h2><p>Secret values remain available only through the existing credential flow.</p></div></div>
              <div className="golive-credential-banner"><span><KeyRound size={21} /></span><div><strong>Environment-bound access</strong><p>Keys created here stay scoped to this Production environment. The full secret is shown once.</p></div></div>
              <div className="golive-links"><button type="button" onClick={() => onNavigate("api-keys")}>Manage Production API keys <ExternalLink size={14} /></button></div>
            </article>
            <article className="panel table-panel golive-checks"><div className="panel-heading"><div><h2>Credential readiness</h2><p>Active key presence is checked by the system.</p></div><ShieldCheck size={17} className="heading-icon" /></div>
              {checksView((readiness?.checks ?? []).filter((check) => check.category === "CREDENTIALS"))}
            </article>
          </section>}

          {activeSection === "webhooks" && <section className="golive-grid">
            <article className="panel golive-card"><div className="panel-heading"><div><h2>Production webhooks</h2><p>Endpoint configuration stays in the existing webhook manager.</p></div></div>
              <div className="golive-webhook-flow" aria-label="Webhook security requirements">
                <span><i>01</i><strong>HTTPS endpoint</strong><small>Encrypted transport</small></span>
                <b aria-hidden="true" />
                <span><i>02</i><strong>Signing secret</strong><small>Authentic events</small></span>
                <b aria-hidden="true" />
                <span><i>03</i><strong>Delivery</strong><small>Retry visibility</small></span>
              </div>
              <div className="golive-links"><button type="button" onClick={() => onNavigate("webhooks")}>Configure webhooks <ExternalLink size={14} /></button></div>
              <p className="golive-note">Delivery history and endpoint challenge verification are not currently used as Go-Live checks.</p>
            </article>
            <article className="panel table-panel golive-checks"><div className="panel-heading"><div><h2>Webhook readiness</h2><p>The system checks transport and signing configuration.</p></div></div>
              {checksView((readiness?.checks ?? []).filter((check) => check.category === "WEBHOOKS"))}
            </article>
          </section>}

          {activeSection === "verification" && <section className="golive-grid golive-history-grid">
            <article className="panel golive-card"><div className="panel-heading"><div><h2>Run verification</h2><p>The system checks Production readiness and saves the result before responding.</p></div></div>
              <div className={`golive-verification-orb${running ? " golive-verification-orb--running" : readiness?.canLaunch ? " golive-verification-orb--passed" : ""}`}>
                <span><ShieldCheck size={27} /></span>
                <strong>{running ? "Checking" : readiness?.canLaunch ? "Ready" : "Awaiting run"}</strong>
                <small>{running ? "Evaluating Production checks" : readiness?.verifiedAt ? `Last run ${dateLabel(readiness.verifiedAt)}` : "Your first readiness run"}</small>
              </div>
              {running && verificationEstimateSeconds !== null && verificationEstimateLabel && <p className="golive-note" role="status" aria-live="polite">
                 {verificationEstimateSeconds > 0
                   ? `Estimated completion: around ${verificationEstimateLabel} (about ${verificationEstimateSeconds}s).`
                   : "The approximate 30-second window has passed; verification is still running."}
              </p>}
              <p className="golive-note">A launch requires a READY verification no older than 15 minutes.</p>
              {!permissionsLoaded
                ? <p className="golive-note">Checking the active role's verification permission…</p>
                : canVerifyProduction
                  ? <button className="button button--secondary" type="button" onClick={() => void runVerification()} disabled={running || loading}>
                    <ShieldCheck size={15} />{running ? "Verifying…" : "Run verification"}
                  </button>
                  : <p className="golive-note">Your role does not have `production:verify`; ask an organization owner or admin to grant access.</p>}
            </article>
            <article className="panel golive-card"><div className="panel-heading"><div><h2>Verification history</h2><p>Recent system readiness runs</p></div></div>
              {verifications.length === 0 ? <p className="security-empty-state">No verifications recorded yet.</p> : <ul className="golive-history">{verifications.map((item) => <li key={item.verificationId ?? item.verifiedAt}><div><strong>{item.state}</strong><span>{item.passedChecks} passed · {item.failedChecks} blockers</span></div><time>{dateLabel(item.verifiedAt)}</time></li>)}</ul>}
            </article>
          </section>}

          {activeSection === "launch" && <section className="golive-grid">
            <article className="panel golive-card"><div className="panel-heading"><div><h2>Launch status</h2><p>System state for this Production environment</p></div><Rocket size={17} className="heading-icon" /></div>
              <div className={`golive-launch-console${readiness?.state === "LIVE" ? " golive-launch-console--live" : readiness?.state === "SUSPENDED" ? " golive-launch-console--suspended" : readiness?.canLaunch ? " golive-launch-console--ready" : ""}`}>
                <span className="golive-launch-console__light"><i /></span>
                <div><span>TRAFFIC GATE</span><strong>{readiness?.state === "LIVE" ? "Live traffic enabled" : readiness?.state === "SUSPENDED" ? "Production suspended" : readiness?.canLaunch ? "Ready for launch review" : "Launch gate closed"}</strong></div>
                <small>{readiness?.blockingChecks ?? 0} blockers</small>
              </div>
              <p className="golive-note">{readiness?.canLaunch
                ? "Current readiness permits a launch, subject to a fresh persisted verification and production:launch permission."
                : readiness?.blockingChecks === 0
                  ? "Run a verification and wait for its persisted result before launching. Verification results expire after 15 minutes."
                  : "Resolve blocking readiness checks and run a fresh verification before launch."}</p>
              {!permissionsLoaded
                ? <p className="golive-note">Checking the active role's Go-Live permissions…</p>
                : hasPermission("production:launch")
                ? <button className="button button--primary" type="button" onClick={() => setConfirmLaunch(true)} disabled={!readiness?.canLaunch || launching || readiness.state === "LIVE"}>
                  <Rocket size={15} />{readiness?.state === "LIVE" ? "Live" : "Launch Production"}
                </button>
                : <p className="golive-note">Your role does not have the `production:launch` permission. Ask an organization owner or admin to grant access.</p>}
            </article>
            <article className="panel golive-card"><div className="panel-heading"><div><h2>Launch history</h2><p>Production state transitions</p></div></div>
              {launches.length === 0 ? <p className="security-empty-state">Production has not been launched through Go-Live.</p> : <ul className="golive-history">{launches.map((launch) => <li key={launch.id}><div><strong>{launch.status}</strong><span>Launch {launch.id.slice(0, 8)}</span></div><time>{dateLabel(launch.completedAt)}</time></li>)}</ul>}
            </article>
          </section>}

          {activeSection === "post-launch" && <section className="golive-post-launch">
            <div className="golive-post-launch__heading">
              <div><h2>Production activity · last 7 days</h2><p>
                {readiness?.state === "LIVE"
                  ? "Live request and webhook telemetry for the selected Production environment."
                  : "Showing actual telemetry for the selected Production environment; zero traffic is not treated as a health signal."}
              </p></div>
              <span>{postLaunchRefreshedAt ? `Updated ${dateLabel(postLaunchRefreshedAt)}` : "Awaiting telemetry"}</span>
            </div>
            <div className="golive-metric-grid">
              {[
                ["Requests", postLaunchUsage?.totalRequests],
                ["Successful", postLaunchUsage?.successfulRequests],
                ["Failed", postLaunchUsage?.failedRequests],
                ["Error rate", postLaunchUsage ? `${(postLaunchUsage.errorRate * 100).toFixed(2)}%` : undefined],
                ["Average latency", postLaunchUsage ? `${Math.round(postLaunchUsage.averageLatencyMs)} ms` : undefined],
                ["P95 latency", postLaunchUsage ? `${Math.round(postLaunchUsage.p95LatencyMs)} ms` : undefined],
              ].map(([label, value]) => <article className="panel golive-metric" key={label}>
                <span>{label}</span><strong>{postLaunchLoading && value === undefined ? "…" : value ?? "—"}</strong>
              </article>)}
            </div>
            <section className="panel golive-activity-panel">
              <div className="panel-heading"><div><h2>Recent API requests</h2><p>Latest requests scoped to this project and Production environment</p></div></div>
              {!canReadUsage && permissionsLoaded
                ? <p className="golive-source-note">Your role does not have <code>usage:read</code>; request telemetry is unavailable.</p>
                : postLaunchErrors.requests
                  ? <p className="notification-alert" role="alert">Request telemetry unavailable: {postLaunchErrors.requests}</p>
                  : postLaunchRequests.length === 0
                    ? <p className="golive-source-note">{postLaunchLoading ? "Loading request telemetry…" : "No request records were returned for this environment."}</p>
                    : <div className="golive-table-wrap"><table className="golive-activity-table"><thead><tr><th>Time</th><th>Endpoint</th><th>Status</th><th>Latency</th></tr></thead><tbody>
                      {postLaunchRequests.slice(0, 10).map((request) => <tr key={request.requestId}>
                        <td>{dateLabel(request.occurredAt)}</td><td><code>{request.method} {request.endpoint}</code></td>
                        <td><span className={`golive-http-status${request.statusCode >= 400 ? " golive-http-status--error" : ""}`}>{request.statusCode}</span></td>
                        <td>{request.latencyMs} ms</td>
                      </tr>)}
                    </tbody></table></div>}
              <div className="golive-links">
                <button type="button" onClick={() => onNavigate("usage")}>Open usage <ExternalLink size={14} /></button>
                <button type="button" onClick={() => onNavigate("logs")}>Open logs <ExternalLink size={14} /></button>
              </div>
            </section>
            <section className="panel golive-activity-panel">
              <div className="panel-heading"><div><h2>Recent webhook deliveries</h2><p>Latest delivery attempts for this project and Production environment</p></div></div>
              {!canReadWebhooks && permissionsLoaded
                ? <p className="golive-source-note">Your role does not have <code>webhook:read</code>; delivery telemetry is unavailable.</p>
                : postLaunchErrors.deliveries
                  ? <p className="notification-alert" role="alert">Webhook telemetry unavailable: {postLaunchErrors.deliveries}</p>
                  : postLaunchDeliveries.length === 0
                    ? <p className="golive-source-note">{postLaunchLoading ? "Loading webhook telemetry…" : "No webhook delivery attempts were returned for this environment."}</p>
                    : <div className="golive-table-wrap"><table className="golive-activity-table"><thead><tr><th>Time</th><th>Event</th><th>Status</th><th>Response</th><th>Latency</th></tr></thead><tbody>
                      {postLaunchDeliveries.slice(0, 10).map((delivery) => <tr key={delivery.id}>
                        <td>{dateLabel(delivery.createdAt)}</td><td><code>{delivery.eventType}</code></td>
                        <td>{delivery.status}</td><td>{delivery.responseCode ?? "—"}</td><td>{delivery.latencyMs == null ? "—" : `${delivery.latencyMs} ms`}</td>
                      </tr>)}
                    </tbody></table></div>}
              <div className="golive-links"><button type="button" onClick={() => onNavigate("webhooks")}>Open webhooks <ExternalLink size={14} /></button></div>
            </section>
            <p className="golive-source-note">
              {postLaunchErrors.usage
                ? `Usage metrics unavailable: ${postLaunchErrors.usage}`
                : !canReadUsage && permissionsLoaded
                  ? "Usage totals are hidden because this role lacks usage:read."
                  : "Telemetry is scoped to the selected environment. Metrics reflect recorded data and do not replace external uptime monitoring or alerting."}
            </p>
          </section>}
          </div>

          {confirmLaunch && <div className="golive-modal-backdrop" role="presentation">
            <section className="golive-confirm panel" role="dialog" aria-modal="true" aria-labelledby="golive-confirm-title">
              <span className="golive-confirm__icon"><AlertTriangle size={21} /></span>
              <h2 id="golive-confirm-title">Launch Production?</h2>
              <p>You are activating this integration for live traffic. Production requests may affect real customers and systems.</p>
              <dl><div><dt>Project</dt><dd>{readiness?.projectName}</dd></div><div><dt>Environment</dt><dd>{readiness?.environmentName}</dd></div><div><dt>Readiness</dt><dd>{readiness?.readinessPercent}% · {readiness?.passedChecks} checks passed</dd></div></dl>
              <footer><button className="button button--secondary" type="button" onClick={() => setConfirmLaunch(false)} disabled={launching}>Cancel</button><button className="button button--primary" type="button" onClick={() => void launchProduction()} disabled={launching || !readiness?.canLaunch}><Rocket size={15} />{launching ? "Launching…" : "Launch Production"}</button></footer>
            </section>
          </div>}
        </>
      )}
    </>
  );
}
