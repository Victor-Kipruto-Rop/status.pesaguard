import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { AppError, getUserMessage } from "../../lib/errors";
import { Activity, Check, CloudCog, Copy, KeyRound, Plus, RefreshCw, Save, ShieldCheck, Trash2 } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";
import { copyTextToClipboard } from "../../lib/clipboard";
import { readActiveEnvironmentContext } from "../../lib/activeEnvironment";

type EnvironmentRecord = {
  id: string;
  projectId: string;
  name: string;
  type: string;
  status: "ACTIVE" | "SUSPENDED" | "DEACTIVATED";
  baseUrl: string;
  configuration: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  statusChangedAt: string;
};

type EnvironmentHistory = {
  id: string;
  action: string;
  fromType: string | null;
  toType: string | null;
  fromStatus: string | null;
  toStatus: string | null;
  actorUserId: string | null;
  reason: string | null;
  createdAt: string;
};

type EnvironmentCredential = {
  id: string;
  name: string;
  type: string;
  status: "ACTIVE" | "REVOKED";
  version: number;
  rotatedAt: string | null;
  createdAt: string;
  updatedAt: string;
};

type EnvironmentLimits = {
  environmentId: string;
  requestsPerMinute: number;
  burstRequests: number;
  maxApiKeys: number;
  maxCredentials: number;
  credentialRotationIntervalMinutes: number;
  updatedBy: string | null;
  updatedAt: string;
};

type EnvironmentAccessPermission = "READ" | "WRITE" | "DEPLOY" | "ROTATE_CREDENTIALS" | "MANAGE_POLICIES";
type EnvironmentAccessPolicy = {
  id: string;
  environmentId: string;
  subjectType: "ORGANIZATION" | "PROJECT_MEMBER";
  subjectRole: string;
  permissions: EnvironmentAccessPermission[];
  ipAllowlist: string[];
  createdBy: string;
  createdAt: string;
  updatedAt: string;
};

const environmentAccessPermissions: EnvironmentAccessPermission[] = [
  "READ", "WRITE", "DEPLOY", "ROTATE_CREDENTIALS", "MANAGE_POLICIES",
];
const organizationRoles = ["OWNER", "ADMIN", "DEVELOPER", "SECURITY", "ANALYST", "VIEWER", "READ_ONLY", "FINANCE", "AUDITOR"];
const projectMemberRoles = ["MANAGER", "DEVELOPER", "VIEWER"];

function dateLabel(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unknown" : new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

export function EnvironmentPage() {
  const { isAuthenticated, hasPermission } = useAuth();
  const canUpdate = hasPermission("environment:update");
  const initialContext = readActiveEnvironmentContext();
  const [environmentId, setEnvironmentId] = useState(initialContext.environmentId);
  const [projectId, setProjectId] = useState(initialContext.projectId);
  const [environment, setEnvironment] = useState<EnvironmentRecord | null>(null);
  const [history, setHistory] = useState<EnvironmentHistory[]>([]);
  const [credentials, setCredentials] = useState<EnvironmentCredential[]>([]);
  const [limits, setLimits] = useState<EnvironmentLimits | null>(null);
  const [accessPolicies, setAccessPolicies] = useState<EnvironmentAccessPolicy[]>([]);
  const [policySubjectType, setPolicySubjectType] = useState<EnvironmentAccessPolicy["subjectType"]>("PROJECT_MEMBER");
  const [policySubjectRole, setPolicySubjectRole] = useState("DEVELOPER");
  const [policyPermissions, setPolicyPermissions] = useState<EnvironmentAccessPermission[]>(["READ"]);
  const [policyIpAllowlist, setPolicyIpAllowlist] = useState("");
  const [policyError, setPolicyError] = useState<string | null>(null);
  const [policyMessage, setPolicyMessage] = useState<string | null>(null);
  const [policySaving, setPolicySaving] = useState(false);
  const [configurationText, setConfigurationText] = useState("{}");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [credentialError, setCredentialError] = useState<string | null>(null);
  const [credentialMessage, setCredentialMessage] = useState<string | null>(null);
  const [credentialName, setCredentialName] = useState("");
  const [credentialType, setCredentialType] = useState("API_KEY");
  const [credentialSecret, setCredentialSecret] = useState("");
  const [credentialSaving, setCredentialSaving] = useState(false);
  const [limitsError, setLimitsError] = useState<string | null>(null);
  const [limitsMessage, setLimitsMessage] = useState<string | null>(null);
  const [limitsSaving, setLimitsSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [baseUrlCopied, setBaseUrlCopied] = useState(false);

  useEffect(() => {
    function syncActiveEnvironment() {
      const context = readActiveEnvironmentContext();
      setProjectId(context.projectId);
      setEnvironmentId(context.environmentId);
      setEnvironment(null);
      setHistory([]);
      setCredentials([]);
      setLimits(null);
      setAccessPolicies([]);
      setConfigurationText("{}");
      setMessage(null);
      setCredentialName("");
      setCredentialSecret("");
      setCredentialMessage(null);
      setCredentialError(null);
      setHistoryError(null);
      setLimitsError(null);
      setLimitsMessage(null);
      setPolicyError(null);
      setPolicyMessage(null);
      setError(null);
      setBaseUrlCopied(false);
      setReloadKey((current) => current + 1);
    }
    window.addEventListener("pesaguard:active-context-changed", syncActiveEnvironment);
    return () => window.removeEventListener("pesaguard:active-context-changed", syncActiveEnvironment);
  }, []);

  useEffect(() => {
    if (!isAuthenticated || !projectId || !environmentId) {
      setEnvironment(null);
      setHistory([]);
      setCredentials([]);
      setLimits(null);
      setLoading(false);
      setError(!isAuthenticated ? "Sign in to view environment details." : "Select an environment from the environment list first.");
      return;
    }

    const controller = new AbortController();
    const basePath = `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environmentId)}`;
    setLoading(true);
    setError(null);
    setHistoryError(null);
    setCredentialError(null);
    Promise.allSettled([
      apiData<EnvironmentRecord>(basePath, { signal: controller.signal }),
      apiData<EnvironmentHistory[]>(`${basePath}/history`, { signal: controller.signal }),
      apiData<EnvironmentCredential[]>(`${basePath}/credentials`, { signal: controller.signal }),
      apiData<EnvironmentLimits>(`${basePath}/limits`, { signal: controller.signal }),
      apiData<EnvironmentAccessPolicy[]>(`${basePath}/access-policies`, { signal: controller.signal }),
    ]).then(([environmentResult, historyResult, credentialResult, limitsResult, policyResult]) => {
      if (controller.signal.aborted) return;
      if (environmentResult.status === "fulfilled") {
        setEnvironment(environmentResult.value);
        setConfigurationText(JSON.stringify(environmentResult.value.configuration ?? {}, null, 2));
      } else {
        setEnvironment(null);
        setError(getUserMessage(environmentResult.reason, "Unable to load this environment."));
      }
      if (historyResult.status === "fulfilled") {
        setHistory(historyResult.value);
      } else {
        setHistory([]);
        setHistoryError(getUserMessage(historyResult.reason, "Environment history is unavailable."));
      }
      if (credentialResult.status === "fulfilled") {
        setCredentials(credentialResult.value);
      } else {
        setCredentials([]);
        setCredentialError(getUserMessage(credentialResult.reason, "Environment credentials are unavailable."));
      }
      if (limitsResult.status === "fulfilled") {
        setLimits(limitsResult.value);
        setLimitsError(null);
      } else {
        setLimits(null);
        setLimitsError(getUserMessage(limitsResult.reason, "Environment limits are unavailable."));
      }
      if (policyResult.status === "fulfilled") {
        setAccessPolicies(policyResult.value);
        setPolicyError(null);
      } else {
        setAccessPolicies([]);
        setPolicyError(getUserMessage(policyResult.reason, "Environment access policies are unavailable."));
      }
      setLoading(false);
    });
    return () => controller.abort();
  }, [environmentId, isAuthenticated, projectId, reloadKey]);

  async function saveAccessPolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!environment || !canUpdate) return;
    setPolicySaving(true);
    setPolicyError(null);
    setPolicyMessage(null);
    try {
      const ipAllowlist = policyIpAllowlist.split(/[\n,]/).map((value) => value.trim()).filter(Boolean);
      const saved = await apiData<EnvironmentAccessPolicy>(
        `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environment.id)}/access-policies`,
        {
          method: "PUT",
          body: JSON.stringify({
            subjectType: policySubjectType,
            subjectRole: policySubjectRole,
            permissions: policyPermissions,
            ipAllowlist,
          }),
        },
      );
      setAccessPolicies((current) => [
        ...current.filter((policy) => policy.id !== saved.id
          && !(policy.subjectType === saved.subjectType && policy.subjectRole === saved.subjectRole)),
        saved,
      ].sort((left, right) => left.subjectType.localeCompare(right.subjectType)
        || left.subjectRole.localeCompare(right.subjectRole)));
      setPolicyMessage(`Access policy saved for ${saved.subjectType === "ORGANIZATION" ? "organization" : "project"} role ${saved.subjectRole}.`);
    } catch (requestError) {
      setPolicyError(getUserMessage(requestError, "The environment access policy could not be saved."));
    } finally {
      setPolicySaving(false);
    }
  }

  async function deleteAccessPolicy(policy: EnvironmentAccessPolicy) {
    if (!environment) return;
    setPolicySaving(true);
    setPolicyError(null);
    setPolicyMessage(null);
    try {
      await apiData(
        `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environment.id)}/access-policies/${encodeURIComponent(policy.id)}`,
        { method: "DELETE" },
      );
      setAccessPolicies((current) => current.filter((item) => item.id !== policy.id));
      setPolicyMessage(`Policy removed for ${policy.subjectType === "ORGANIZATION" ? "organization" : "project"} role ${policy.subjectRole}.`);
    } catch (requestError) {
      setPolicyError(getUserMessage(requestError, "The environment access policy could not be removed."));
    } finally {
      setPolicySaving(false);
    }
  }

  async function saveConfiguration() {
    if (!environment || !isAuthenticated) return;
    let configuration: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(configurationText);
      if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
        throw new AppError("VALIDATION_ERROR", "Configuration must be a JSON object.");
      }
      configuration = Object.fromEntries(Object.entries(parsed));
    } catch (parseError) {
      setError(getUserMessage(parseError, "Enter valid JSON configuration."));
      return;
    }

    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      const updated = await apiData<EnvironmentRecord>(
        `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environment.id)}/configuration`,
        { method: "PUT", body: JSON.stringify({ configuration }) },
      );
      setEnvironment(updated);
      setConfigurationText(JSON.stringify(updated.configuration ?? {}, null, 2));
      setMessage("Environment configuration saved.");
      setReloadKey((current) => current + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Environment configuration could not be saved."));
    } finally {
      setSaving(false);
    }
  }

  async function createCredential(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!environment || !credentialSecret.trim()) return;
    setCredentialSaving(true);
    setCredentialError(null);
    setCredentialMessage(null);
    try {
      const created = await apiData<EnvironmentCredential>(
        `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environment.id)}/credentials`,
        { method: "POST", body: JSON.stringify({ name: credentialName.trim(), type: credentialType, secret: credentialSecret }) },
      );
      setCredentials((current) => [created, ...current]);
      setCredentialName("");
      setCredentialSecret("");
      setCredentialMessage(`Credential version ${created.version} was stored. The platform will not return the secret value.`);
    } catch (requestError) {
      setCredentialError(getUserMessage(requestError, "The environment credential could not be stored."));
    } finally {
      setCredentialSaving(false);
    }
  }

  async function revokeCredential(credential: EnvironmentCredential) {
    if (!environment || credentialSaving) return;
    if (!window.confirm(`Revoke ${credential.name} version ${credential.version}?`)) return;
    setCredentialSaving(true);
    setCredentialError(null);
    setCredentialMessage(null);
    try {
      await apiData<void>(
        `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environment.id)}/credentials/${encodeURIComponent(credential.id)}`,
        { method: "DELETE" },
      );
      setCredentials((current) => current.map((item) => item.id === credential.id ? { ...item, status: "REVOKED" } : item));
      setCredentialMessage(`${credential.name} version ${credential.version} was revoked.`);
    } catch (requestError) {
      setCredentialError(getUserMessage(requestError, "The environment credential could not be revoked."));
    } finally {
      setCredentialSaving(false);
    }
  }

  async function saveLimits(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!environment || !limits) return;
    setLimitsSaving(true);
    setLimitsError(null);
    setLimitsMessage(null);
    try {
      const updated = await apiData<EnvironmentLimits>(
        `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environment.id)}/limits`,
        { method: "PUT", body: JSON.stringify({
          requestsPerMinute: limits.requestsPerMinute,
          burstRequests: limits.burstRequests,
          maxApiKeys: limits.maxApiKeys,
          maxCredentials: limits.maxCredentials,
          credentialRotationIntervalMinutes: limits.credentialRotationIntervalMinutes,
        }) },
      );
      setLimits(updated);
      setLimitsMessage("Environment limits saved. Request and burst limits now apply to API key traffic.");
    } catch (requestError) {
      setLimitsError(getUserMessage(requestError, "Environment limits could not be saved."));
    } finally {
      setLimitsSaving(false);
    }
  }

  async function changeStatus() {
    if (!environment || !isAuthenticated) return;
    const action = environment.status === "ACTIVE" ? "suspend" : environment.status === "SUSPENDED" ? "resume" : null;
    if (!action) return;
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      const updated = await apiData<EnvironmentRecord>(
        `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environment.id)}/${action}`,
        { method: "POST" },
      );
      setEnvironment(updated);
      setMessage(`Environment ${action === "suspend" ? "suspended" : "resumed"}.`);
      setReloadKey((current) => current + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Environment status could not be changed."));
    } finally {
      setSaving(false);
    }
  }

  async function copyEnvironmentBaseUrl() {
    if (!environment?.baseUrl) return;
    try {
      await copyTextToClipboard(environment.baseUrl);
      setBaseUrlCopied(true);
      window.setTimeout(() => setBaseUrlCopied(false), 1800);
    } catch {
      setError("Clipboard access is unavailable. Select and copy the environment Base URL manually.");
    }
  }

  return (
    <div className="premium-page environment-detail-page">
      <PageHeader
        eyebrow="ENVIRONMENT"
        title={environment?.name ?? "Environment"}
        description={environment ? `${environment.type} · ${environment.status}` : "Environment details and configuration."}
        action={environment && canUpdate && environment.status !== "DEACTIVATED"
          ? <button className="button button--secondary" type="button" disabled={saving} onClick={() => void changeStatus()}><RefreshCw size={14} />{saving ? "Updating…" : environment.status === "ACTIVE" ? "Suspend" : "Resume"}</button>
          : undefined}
      />
      {error && <div className="workflow-error" role="alert"><p>{loading ? "Loading environment…" : error}</p><button className="text-button" type="button" onClick={() => setReloadKey((current) => current + 1)}>Retry</button></div>}
      {message && <p className="workflow-success" role="status">{message}</p>}
      {loading && <p className="workflow-hint" role="status">Loading environment details…</p>}

      {environment && (
        <>
          <section className={`environment-detail-hero${environment.type === "PRODUCTION" ? " environment-detail-hero--production" : " environment-detail-hero--nonproduction"}`}>
            <div className="environment-detail-hero__main">
              <span className="environment-detail-hero__icon"><CloudCog size={22} /></span>
              <div className="environment-detail-hero__identity">
                <span className="section-eyebrow">ENVIRONMENT CONTROL PLANE</span>
                <h2>{environment.name}</h2>
                <p>{environment.type} tier <span aria-hidden="true">·</span> scoped to selected project</p>
              </div>
              <span className={`premium-live-mark${environment.type === "PRODUCTION" ? " premium-live-mark--production" : " premium-live-mark--nonproduction"}`}><i />{environment.status}</span>
            </div>
            <div className="environment-detail-hero__endpoint">
              <div><span>Canonical API endpoint</span><code>{environment.baseUrl}</code><small>Use this host for API keys assigned to this environment. Never expose keys in browser code.</small></div>
              <button className="button button--secondary" type="button" onClick={() => void copyEnvironmentBaseUrl()}>
                {baseUrlCopied ? <Check size={14} /> : <Copy size={14} />}{baseUrlCopied ? "Copied" : "Copy URL"}
              </button>
            </div>
            <div className="environment-detail-hero__metrics" aria-label="Environment overview">
              <div><span>Lifecycle status</span><strong><i className={`environment-status-dot environment-status-dot--${environment.status.toLowerCase()}`} />{environment.status.toLowerCase()}</strong></div>
              <div><span>API keys allowed</span><strong>{limits?.maxApiKeys ?? "—"}</strong></div>
              <div><span>Credentials stored</span><strong>{credentials.filter((item) => item.status === "ACTIVE").length}</strong></div>
              <div><span>Access policies</span><strong>{accessPolicies.length ? `${accessPolicies.length} custom` : "Inherited"}</strong></div>
            </div>
          </section>
          <nav className="environment-detail-nav" aria-label="Environment sections">
            <a href="#environment-overview"><Activity size={14} />Overview</a>
            <a href="#environment-access"><ShieldCheck size={14} />Access</a>
            <a href="#environment-limits"><RefreshCw size={14} />Limits</a>
            <a href="#environment-configuration"><Save size={14} />Configuration</a>
            <a href="#environment-credentials"><KeyRound size={14} />Credentials</a>
            <a href="#environment-history"><RefreshCw size={14} />History</a>
          </nav>
          <section id="environment-overview" className="panel settings-group environment-detail-overview">
            <div className="settings-group-header">
              <div className="settings-group-title-wrap"><span className="settings-group-icon"><CloudCog size={17} /></span><div><h2>Environment details</h2><p>Identity and lifecycle data returned by the environment service.</p></div></div>
              <span className="table-tag">{environment.status}</span>
            </div>
            <dl className="environment-details-grid">
              <div><dt>Environment ID</dt><dd><code>{environment.id}</code></dd></div>
              <div><dt>Project ID</dt><dd><code>{environment.projectId}</code></dd></div>
              <div><dt>Type</dt><dd>{environment.type}</dd></div>
              <div><dt>Created</dt><dd>{dateLabel(environment.createdAt)}</dd></div>
              <div><dt>Last updated</dt><dd>{dateLabel(environment.updatedAt)}</dd></div>
              <div><dt>Status changed</dt><dd>{dateLabel(environment.statusChangedAt)}</dd></div>
            </dl>
          </section>

          <section id="environment-access" className="panel settings-group environment-access-panel">
            <div className="settings-group-header">
              <div className="settings-group-title-wrap"><span className="settings-group-icon"><ShieldCheck size={17} /></span><div><h2>Environment access policies</h2><p>Grant environment-specific capabilities to organization roles or project-member roles.</p></div></div>
              <span className="table-tag">{accessPolicies.length > 0 ? "CUSTOM RULES" : "INHERITED ACCESS"}</span>
            </div>
            <p className="workflow-hint">Without policies, existing project access applies. Once any policy is saved, roles must have a matching policy that grants the requested permission. Organization owners and admins retain administrative access. Optional IP ranges are enforced on every request.</p>
            {policyError && <p className="workflow-error" role="alert">{policyError}</p>}
            {policyMessage && <p className="workflow-success" role="status">{policyMessage}</p>}
            {accessPolicies.length > 0 && <div className="table-scroll"><table className="data-table"><thead><tr><th>SUBJECT</th><th>ROLE</th><th>PERMISSIONS</th><th>IP ALLOWLIST</th>{canUpdate && <th>ACTIONS</th>}</tr></thead><tbody>
              {accessPolicies.map((policy) => <tr key={policy.id}>
                <td>{policy.subjectType === "ORGANIZATION" ? "Organization role" : "Project member"}</td>
                <td>{policy.subjectRole}</td>
                <td>{policy.permissions.join(", ")}</td>
                <td>{policy.ipAllowlist.length ? policy.ipAllowlist.join(", ") : "Any IP"}</td>
                {canUpdate && <td><button className="text-button text-button--danger" type="button" disabled={policySaving} onClick={() => void deleteAccessPolicy(policy)}><Trash2 size={13} />Remove</button></td>}
              </tr>)}
            </tbody></table></div>}
            {canUpdate && <ValidatedForm className="workflow-form" onSubmit={(event) => void saveAccessPolicy(event)}>
              <label>Subject type<select value={policySubjectType} disabled={policySaving} onChange={(event) => {
                const nextType = event.target.value as EnvironmentAccessPolicy["subjectType"];
                setPolicySubjectType(nextType);
                setPolicySubjectRole(nextType === "ORGANIZATION" ? "DEVELOPER" : "DEVELOPER");
              }}><option value="PROJECT_MEMBER">Project member role</option><option value="ORGANIZATION">Organization role</option></select></label>
              <label>Role<select value={policySubjectRole} disabled={policySaving} onChange={(event) => setPolicySubjectRole(event.target.value)}>
                {(policySubjectType === "ORGANIZATION" ? organizationRoles : projectMemberRoles).map((role) => <option key={role} value={role}>{role.replaceAll("_", " ")}</option>)}
              </select></label>
              <fieldset className="workflow-fieldset"><legend>Permissions</legend>
                {environmentAccessPermissions.map((permission) => <label className="workflow-checkbox" key={permission}><input type="checkbox" checked={policyPermissions.includes(permission)} disabled={policySaving} onChange={(event) => setPolicyPermissions((current) => event.target.checked
                  ? [...current, permission]
                  : current.filter((item) => item !== permission))} />{permission.replaceAll("_", " ").toLowerCase()}</label>)}
              </fieldset>
              <label>Allowed IP addresses or CIDR ranges (optional)<textarea rows={3} spellCheck={false} value={policyIpAllowlist} disabled={policySaving} onChange={(event) => setPolicyIpAllowlist(event.target.value)} placeholder={"203.0.113.10\n198.51.100.0/24"} /></label>
              <div className="workflow-form-actions"><button className="button button--primary" type="submit" disabled={policySaving || policyPermissions.length === 0}>{policySaving ? "Saving…" : "Save role policy"}</button></div>
            </ValidatedForm>}
          </section>

          <section id="environment-limits" className="panel settings-group environment-limits-panel">
            <div className="settings-group-header"><div className="settings-group-title-wrap"><span className="settings-group-icon"><RefreshCw size={17} /></span><div><h2>Rate limits and quotas</h2><p>System-enforced key, credential, and environment request budgets.</p></div></div><span className="table-tag">ENFORCED</span></div>
            {limitsError && <p className="workflow-error" role="alert">{limitsError}</p>}
            {limitsMessage && <p className="workflow-success" role="status">{limitsMessage}</p>}
            {limits && <ValidatedForm className="workflow-form" onSubmit={(event) => void saveLimits(event)}>
              <label>Requests per minute<input type="number" min={1} max={100000} required value={limits.requestsPerMinute} onChange={(event) => setLimits((current) => current ? { ...current, requestsPerMinute: Number(event.target.value) } : current)} disabled={!canUpdate || limitsSaving} /></label>
              <label>Burst requests per second<input type="number" min={1} max={10000} required value={limits.burstRequests} onChange={(event) => setLimits((current) => current ? { ...current, burstRequests: Number(event.target.value) } : current)} disabled={!canUpdate || limitsSaving} /></label>
              <label>Maximum active API keys<input type="number" min={1} max={100} required value={limits.maxApiKeys} onChange={(event) => setLimits((current) => current ? { ...current, maxApiKeys: Number(event.target.value) } : current)} disabled={!canUpdate || limitsSaving} /></label>
              <label>Maximum active credentials<input type="number" min={1} max={500} required value={limits.maxCredentials} onChange={(event) => setLimits((current) => current ? { ...current, maxCredentials: Number(event.target.value) } : current)} disabled={!canUpdate || limitsSaving} /></label>
              <label>Credential rotation reminder (minutes)<input type="number" min={1} max={525600} required value={limits.credentialRotationIntervalMinutes} onChange={(event) => setLimits((current) => current ? { ...current, credentialRotationIntervalMinutes: Number(event.target.value) } : current)} disabled={!canUpdate || limitsSaving} /></label>
              {canUpdate && <div className="workflow-form-actions"><button className="button button--primary" type="submit" disabled={limitsSaving || environment.status === "DEACTIVATED"}>{limitsSaving ? "Saving…" : "Save limits"}</button></div>}
            </ValidatedForm>}
          </section>

          <section id="environment-configuration" className="panel settings-group environment-configuration-panel">
            <div className="settings-group-header">
              <div className="settings-group-title-wrap"><span className="settings-group-icon"><Save size={17} /></span><div><h2>Configuration</h2><p>Update the persisted JSON configuration for this environment.</p></div></div>
              <span className="table-tag">JSON OBJECT</span>
            </div>
            <p className="workflow-hint">Use <code>allowedOrigins</code> as an array of unique origins, for example <code>["https://app.example.com"]</code>. HTTPS is required except for local development and sandbox. Put credentials in the separate credentials manager, never in this JSON.</p>
            <label className="settings-field environment-configuration-field"><span>Environment configuration (JSON object)</span><textarea className="field-control" rows={12} spellCheck={false} value={configurationText} onChange={(event) => setConfigurationText(event.target.value)} disabled={saving || environment.status !== "ACTIVE"} /></label>
            {environment.status !== "ACTIVE" && <p className="workflow-hint">Configuration can only be changed while the environment is active.</p>}
            {canUpdate && <div className="workflow-form-actions"><button className="button button--primary" type="button" disabled={saving || environment.status !== "ACTIVE"} onClick={() => void saveConfiguration()}><Save size={14} />{saving ? "Saving…" : "Save configuration"}</button></div>}
          </section>

          <section id="environment-credentials" className="panel settings-group environment-credentials-panel">
            <div className="settings-group-header"><div className="settings-group-title-wrap"><span className="settings-group-icon"><KeyRound size={17} /></span><div><h2>Environment credentials</h2><p>Encrypted inventory for environment secrets. Values cannot be viewed or consumed by runtime integrations yet; keep the source secret in your own secret manager. Reuse a name to create a new version, then revoke the old one.</p></div></div><span className="table-tag">ENCRYPTED</span></div>
            {credentialError && <p className="workflow-error" role="alert">{credentialError}</p>}
            {credentialMessage && <p className="workflow-success" role="status">{credentialMessage}</p>}
            {canUpdate && environment.status === "ACTIVE" && <ValidatedForm className="workflow-form" onSubmit={(event) => void createCredential(event)}>
              <label>Credential name<input required maxLength={120} value={credentialName} onChange={(event) => setCredentialName(event.target.value)} disabled={credentialSaving} placeholder="payments-api" /></label>
              <label>Credential type<select value={credentialType} onChange={(event) => setCredentialType(event.target.value)} disabled={credentialSaving}>{["API_KEY", "WEBHOOK_SECRET", "HMAC_SECRET", "BASIC", "BEARER", "TLS_CERTIFICATE"].map((type) => <option key={type} value={type}>{type.replaceAll("_", " ")}</option>)}</select></label>
              <label>Secret value<input type="password" autoComplete="off" required maxLength={16384} value={credentialSecret} onChange={(event) => setCredentialSecret(event.target.value)} disabled={credentialSaving} /></label>
              <div className="workflow-form-actions"><button className="button button--primary" type="submit" disabled={credentialSaving || !credentialName.trim() || !credentialSecret.trim()}><Plus size={14} />{credentialSaving ? "Storing…" : "Store credential"}</button></div>
            </ValidatedForm>}
            {environment.status !== "ACTIVE" && <p className="workflow-hint">Credentials can only be changed while the environment is active.</p>}
            <div className="table-scroll"><table className="data-table"><thead><tr><th>NAME</th><th>TYPE</th><th>VERSION</th><th>STATUS</th><th>CREATED</th><th>ROTATION REMINDER</th>{canUpdate && <th>ACTIONS</th>}</tr></thead><tbody>{credentials.map((credential) => { const dueAt = limits ? new Date(new Date(credential.createdAt).getTime() + limits.credentialRotationIntervalMinutes * 60_000) : null; const overdue = credential.status === "ACTIVE" && dueAt !== null && dueAt.getTime() < Date.now(); return <tr key={credential.id}><td>{credential.name}</td><td>{credential.type}</td><td>{credential.version}</td><td>{credential.status}</td><td>{dateLabel(credential.createdAt)}</td><td>{dueAt ? <span className={overdue ? "workflow-error" : ""}>{overdue ? "Rotation due · " : "Due "}{dateLabel(dueAt.toISOString())}</span> : "Unavailable"}</td>{canUpdate && <td>{credential.status === "ACTIVE" && <button className="text-button text-button--danger" type="button" disabled={credentialSaving} onClick={() => void revokeCredential(credential)}><Trash2 size={13} />Revoke</button>}</td>}</tr>; })}{credentials.length === 0 && !credentialError && <tr><td colSpan={canUpdate ? 7 : 6}>No environment credentials have been registered.</td></tr>}</tbody></table></div>
          </section>

          <section id="environment-history" className="panel table-panel environment-history-panel">
            <div className="panel-heading"><div><h2>Environment history</h2><p>Lifecycle and type changes returned by the environment service.</p></div></div>
            {historyError && <p className="workflow-error" role="alert">Unable to load environment history: {historyError}</p>}
            <div className="table-scroll"><table className="data-table"><thead><tr><th>DATE</th><th>ACTION</th><th>CHANGE</th><th>ACTOR</th><th>REASON</th></tr></thead><tbody>{history.map((entry) => <tr key={entry.id}><td>{dateLabel(entry.createdAt)}</td><td>{entry.action}</td><td>{entry.fromStatus ?? entry.fromType ?? "—"} → {entry.toStatus ?? entry.toType ?? "—"}</td><td>{entry.actorUserId ?? "System"}</td><td>{entry.reason ?? "—"}</td></tr>)}{!historyError && history.length === 0 && <tr><td colSpan={5}>No lifecycle history was returned.</td></tr>}</tbody></table></div>
          </section>
        </>
      )}
    </div>
  );
}
