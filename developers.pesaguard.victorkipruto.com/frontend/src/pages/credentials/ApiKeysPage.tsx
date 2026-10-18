import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { createPortal } from "react-dom";
import {
  Activity,
  AlertTriangle,
  ArrowUpRight,
  Check,
  Clock3,
  Copy,
  Gauge,
  KeyRound,
  MoreVertical,
  Pencil,
  Plus,
  RotateCw,
  ScrollText,
  ShieldCheck,
  Trash2,
  X,
} from "lucide-react";
import { externalLinks } from "../../app/routes";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { ApiError, apiData } from "../../lib/api";
import { copyTextToClipboard } from "../../lib/clipboard";
import { generateIdempotencyKey } from "../../lib/idempotency";
import {
  readActiveEnvironmentId,
  readActiveProjectId,
  setActiveEnvironment,
  setActiveProject,
} from "../../lib/activeEnvironment";

interface Project {
  id: string;
  name: string;
  status: string;
}

interface Environment {
  id: string;
  projectId: string;
  name: string;
  type: string;
  status: string;
  baseUrl: string;
}

interface ApiKey {
  id: string;
  projectId: string;
  environmentId: string;
  name: string;
  prefix: string;
  scopes: string[];
  status: string;
  expiresAt: string | null;
  lastUsedAt: string | null;
  lastUsedIp: string | null;
  lastUsedCountry: string | null;
  lastUsedDevice: string | null;
  requestCount: number;
  rotatedFromId: string | null;
  ipAllowlist: string[];
  createdAt: string;
}

interface ApiScope {
  name: string;
  description: string;
  category: string;
  apiKeyAssignable: boolean;
  deprecated: boolean;
  restricted: boolean;
}

type KeyAction = "rotate" | "revoke";
const expirationChoices = [
  { label: "30 days", value: "PT720H" },
  { label: "90 days", value: "PT2160H" },
  { label: "365 days", value: "PT8760H" },
];

function formatDate(value: string | null) {
  if (!value) return "Never";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unknown" : date.toLocaleString();
}

function statusLabel(status: string) {
  if (status === "EXPIRING") return "Expiring";
  return status.charAt(0) + status.slice(1).toLowerCase();
}

function statusClass(status: string) {
  if (status === "ACTIVE") return "credential-status--active";
  if (status === "EXPIRING") return "credential-status--expiring";
  if (status === "COMPROMISED" || status === "REVOKED" || status === "EXPIRED") {
    return "credential-status--terminal";
  }
  return "credential-status--other";
}

export function ApiKeysPage({ onNavigate }: { onNavigate: (page: PageId, apiKeyId?: string) => void }) {
  const { isAuthenticated, hasPermission } = useAuth();
  const canRead = hasPermission("credential:read");
  const canCreate = hasPermission("credential:create");
  const canRotate = hasPermission("credential:rotate");
  const canRevoke = hasPermission("credential:revoke");
  const canUpdate = hasPermission("credential:update");
  const canReadUsage = hasPermission("usage:read");
  const [projects, setProjects] = useState<Project[]>([]);
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [scopes, setScopes] = useState<ApiScope[]>([]);
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [projectId, setProjectId] = useState("");
  const [environmentId, setEnvironmentId] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [name, setName] = useState("");
  const [expiresIn, setExpiresIn] = useState("PT2160H");
  const [selectedScopes, setSelectedScopes] = useState<string[]>([]);
  const [selectedKey, setSelectedKey] = useState<ApiKey | null>(null);
  const [createDialogOpen, setCreateDialogOpen] = useState(false);
  const [createIdempotencyKey, setCreateIdempotencyKey] = useState("");
  const [createRequestFingerprint, setCreateRequestFingerprint] = useState("");
  const [revealedKey, setRevealedKey] = useState("");
  const [revealedName, setRevealedName] = useState("");
  const [revealedBaseUrl, setRevealedBaseUrl] = useState("");
  const [pendingAction, setPendingAction] = useState<KeyAction | null>(null);
  const [workingKeyId, setWorkingKeyId] = useState("");
  const [copied, setCopied] = useState(false);
  const [openMenuKeyId, setOpenMenuKeyId] = useState("");
  const [copiedReferenceId, setCopiedReferenceId] = useState("");
  const [menuPosition, setMenuPosition] = useState({ top: 0, right: 0 });
  const [renameTarget, setRenameTarget] = useState<ApiKey | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [renaming, setRenaming] = useState(false);

  const activeEnvironmentIds = useMemo(
    () => new Set(environments.filter((environment) => environment.status === "ACTIVE").map((environment) => environment.id)),
    [environments],
  );
  const projectEnvironments = useMemo(
    () => environments.filter((environment) => environment.projectId === projectId),
    [environments, projectId],
  );
  const selectedEnvironment = projectEnvironments.find((environment) => environment.id === environmentId);
  const activeKeys = keys.filter((key) => key.status === "ACTIVE" || key.status === "EXPIRING").length;
  const expiringKeys = keys.filter((key) => key.status === "EXPIRING").length;
  const totalRequests = keys.reduce((sum, key) => sum + key.requestCount, 0);

  useEffect(() => {
    let current = true;
    if (!isAuthenticated || (!canRead && !canCreate)) {
      setProjects([]);
      setEnvironments([]);
      setScopes([]);
      setKeys([]);
      setProjectId("");
      setEnvironmentId("");
      return () => { current = false; };
    }

    setLoading(true);
    setError("");
    Promise.all([
      apiData<{ items: Project[] }>("/api/v1/projects?page=0&size=100"),
      apiData<ApiScope[]>("/api/v1/scopes"),
    ])
      .then(async ([projectPayload, scopePayload]) => {
        const nextProjects = projectPayload.items;
        const nextScopes = scopePayload;
        const environmentPayloads = await Promise.all(nextProjects.map((project) =>
          apiData<Environment[]>(
            `/api/v1/projects/${encodeURIComponent(project.id)}/environments`,
          )));
        const nextEnvironments = environmentPayloads.flat();
        if (!current) return;
        setProjects(nextProjects);
        setEnvironments(nextEnvironments);
        setScopes(nextScopes);
        const activeProjectId = readActiveProjectId();
        setProjectId((selected) => nextProjects.some((project) => project.id === activeProjectId)
          ? activeProjectId
          : nextProjects.some((project) => project.id === selected) ? selected : nextProjects[0]?.id ?? "");
      })
      .catch((requestError: unknown) => {
        if (current) setError(getUserMessage(requestError, "Could not load projects and credential scopes."));
      })
      .finally(() => {
        if (current) setLoading(false);
      });

    return () => { current = false; };
  }, [isAuthenticated, canRead, canCreate]);

  useEffect(() => {
    if (!projectEnvironments.some((environment) => environment.id === environmentId)) {
      const savedEnvironmentId = readActiveEnvironmentId(projectId);
      const selected = projectEnvironments.find((environment) => environment.id === savedEnvironmentId)
        ?? projectEnvironments.find((environment) => environment.type === "SANDBOX" && environment.status === "ACTIVE")
        ?? projectEnvironments.find((environment) => environment.status === "ACTIVE")
        ?? projectEnvironments[0];
      setEnvironmentId(selected?.id ?? "");
      const project = projects.find((item) => item.id === projectId);
      if (project && selected) setActiveEnvironment(project, selected);
    }
  }, [projectId, environments, environmentId, projects]);

  useEffect(() => {
    function syncEnvironmentSelection() {
      const nextProjectId = readActiveProjectId();
      if (projects.some((project) => project.id === nextProjectId)) {
        setProjectId(nextProjectId);
        const nextEnvironmentId = readActiveEnvironmentId(nextProjectId);
        if (projectEnvironments.some((environment) => environment.id === nextEnvironmentId)) {
          setEnvironmentId(nextEnvironmentId);
        }
      }
    }
    window.addEventListener("pesaguard:active-context-changed", syncEnvironmentSelection);
    return () => window.removeEventListener("pesaguard:active-context-changed", syncEnvironmentSelection);
  }, [projects, projectEnvironments]);

  function selectProject(projectId: string) {
    const project = projects.find((item) => item.id === projectId);
    if (!project) return;
    setProjectId(project.id);
    setEnvironmentId("");
    setActiveProject(project);
  }

  function selectEnvironment(environmentId: string) {
    const project = projects.find((item) => item.id === projectId);
    const environment = projectEnvironments.find((item) => item.id === environmentId);
    if (!project || !environment) return;
    setEnvironmentId(environment.id);
    setActiveEnvironment(project, environment);
  }

  useEffect(() => {
    let current = true;
    if (!isAuthenticated || !canRead || !projectId || !environmentId) {
      setKeys([]);
      return () => { current = false; };
    }
    setLoading(true);
    apiData<ApiKey[]>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environmentId)}/api-keys`,
    )
      .then((payload) => {
        if (current) setKeys(payload);
      })
      .catch((requestError: unknown) => {
        if (current) setError(getUserMessage(requestError, "Could not load API keys."));
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => { current = false; };
  }, [canRead, environmentId, isAuthenticated, projectId]);

  useEffect(() => {
    if (selectedScopes.length > 0 || scopes.length === 0) return;
    const firstUsableScope = scopes.find((scope) =>
      scope.apiKeyAssignable && !scope.deprecated && !scope.restricted);
    if (firstUsableScope) setSelectedScopes([firstUsableScope.name]);
  }, [scopes, selectedScopes.length]);

  useEffect(() => {
    if (!createDialogOpen) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !saving) {
        setCreateDialogOpen(false);
        setError("");
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [createDialogOpen, saving]);

  useEffect(() => {
    if (!openMenuKeyId) return;
    const closeMenu = (event: PointerEvent) => {
      if (!(event.target instanceof Element) || !event.target.closest("[data-key-action-menu]")) {
        setOpenMenuKeyId("");
      }
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpenMenuKeyId("");
    };
    const closeOnScroll = () => setOpenMenuKeyId("");
    document.addEventListener("pointerdown", closeMenu);
    document.addEventListener("keydown", closeOnEscape);
    window.addEventListener("scroll", closeOnScroll, true);
    return () => {
      document.removeEventListener("pointerdown", closeMenu);
      document.removeEventListener("keydown", closeOnEscape);
      window.removeEventListener("scroll", closeOnScroll, true);
    };
  }, [openMenuKeyId]);

  function keyBasePath(key: ApiKey) {
    return `/api/v1/projects/${encodeURIComponent(key.projectId)}/environments/${encodeURIComponent(key.environmentId)}/api-keys`;
  }

  async function createKey(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!projectId || !environmentId || !name.trim()) {
      setError("Choose a project and environment, and enter a key name.");
      return;
    }
    if (!activeEnvironmentIds.has(environmentId)) {
      setError("API keys can only be issued for an active environment.");
      return;
    }
    if (selectedScopes.length === 0) {
      setError("Select at least one API scope for this key.");
      return;
    }
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const body = {
        name: name.trim(),
        scopes: [...selectedScopes].sort(),
        expiresIn,
      };
      const fingerprint = JSON.stringify([projectId, environmentId, body]);
      const idempotencyKey = createRequestFingerprint === fingerprint && createIdempotencyKey
        ? createIdempotencyKey
        : generateIdempotencyKey();
      setCreateRequestFingerprint(fingerprint);
      setCreateIdempotencyKey(idempotencyKey);
      const created = await apiData<{
        id: string; name: string; key: string; prefix: string; scopes: string[]; expiresAt: string; baseUrl: string;
      }>(
        `${keyListPath}`,
        {
          method: "POST",
          headers: { "Idempotency-Key": idempotencyKey },
          body: JSON.stringify(body),
          requestPolicy: { timeoutMs: 30000, retryCount: 0 },
        },
      );
      const baseUrl = created.baseUrl?.trim() || selectedEnvironment?.baseUrl?.trim();
      if (!baseUrl) {
        throw new Error("The environment API Base URL was not returned. Retry key creation to retrieve the integration details.");
      }
      setRevealedKey(created.key);
      setRevealedName(created.name);
      setRevealedBaseUrl(baseUrl);
      setName("");
      setCreateIdempotencyKey("");
      setCreateRequestFingerprint("");
      setCreateDialogOpen(false);
      setNotice("API key created. Copy the secret now; it will not be shown again.");
      try {
        await loadKeys();
      } catch (refreshError) {
        const refreshMessage = getUserMessage(refreshError, "The key list could not be refreshed.");
        setError(`The API key was created, but its list could not be refreshed. ${refreshMessage}`);
      }
    } catch (requestError) {
      const message = getUserMessage(requestError, "Could not create the API key.");
      setError(requestError instanceof ApiError && requestError.supportReference
        ? `${message} (Reference: ${requestError.supportReference})`
        : message);
    } finally {
      setSaving(false);
    }
  }

  const keyListPath = projectId && environmentId
    ? `/api/v1/projects/${encodeURIComponent(projectId)}/environments/${encodeURIComponent(environmentId)}/api-keys`
    : "";

  async function loadKeys() {
    if (!keyListPath) return;
    const nextKeys = await apiData<ApiKey[]>(keyListPath);
    setKeys(nextKeys);
  }

  function beginAction(key: ApiKey, action: KeyAction) {
    setSelectedKey(key);
    setPendingAction(action);
    setError("");
  }

  async function confirmAction() {
    if (!selectedKey || !pendingAction) return;
    const key = selectedKey;
    const action = pendingAction;
    setWorkingKeyId(key.id);
    setError("");
    setNotice("");
    try {
      if (action === "rotate") {
        const created = await apiData<{
          id: string; name: string; key: string; prefix: string; scopes: string[]; expiresAt: string; baseUrl: string;
        }>(`${keyBasePath(key)}/${encodeURIComponent(key.id)}/rotate`, { method: "POST" });
        const environment = environments.find((item) => item.id === key.environmentId);
        const baseUrl = created.baseUrl?.trim() || environment?.baseUrl?.trim();
        if (!baseUrl) {
          throw new Error("The environment API Base URL was not returned. Retry rotation to retrieve the integration details.");
        }
        setRevealedKey(created.key);
        setRevealedName(created.name);
        setRevealedBaseUrl(baseUrl);
        setNotice("Key rotated. The old secret has been revoked; copy the replacement now.");
      } else {
        await apiData<void>(`${keyBasePath(key)}/${encodeURIComponent(key.id)}`, { method: "DELETE" });
        setNotice(`${key.name} was deleted from active use. Its audit history is retained.`);
      }
      setPendingAction(null);
      setSelectedKey(null);
      await loadKeys();
    } catch (requestError) {
      setError(getUserMessage(requestError, `Could not ${action} the API key.`));
    } finally {
      setWorkingKeyId("");
    }
  }

  async function copySecret() {
    try {
      await copyTextToClipboard(revealedKey);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      setError("Clipboard access is unavailable. Select and copy the displayed key manually.");
    }
  }

  async function copyBaseUrl(baseUrl: string) {
    try {
      await copyTextToClipboard(baseUrl);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      setError("Clipboard access is unavailable. Select and copy the Base URL manually.");
    }
  }

  async function copyKeyReference(key: ApiKey) {
    try {
      await copyTextToClipboard(key.prefix);
      setCopiedReferenceId(key.id);
      window.setTimeout(() => setCopiedReferenceId((current) => current === key.id ? "" : current), 1800);
    } catch {
      setError("Clipboard access is unavailable. Select and copy the visible key reference manually.");
    }
  }

  async function renameKey(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!renameTarget || !renameValue.trim()) return;
    setRenaming(true);
    setError("");
    try {
      const renamed = await apiData<ApiKey>(
        `${keyBasePath(renameTarget)}/${encodeURIComponent(renameTarget.id)}/name`,
        { method: "PUT", body: JSON.stringify({ name: renameValue.trim() }) },
      );
      setKeys((current) => current.map((key) => key.id === renamed.id ? renamed : key));
      setNotice(`API key renamed to ${renamed.name}.`);
      setRenameTarget(null);
      setRenameValue("");
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not rename the API key."));
    } finally {
      setRenaming(false);
    }
  }

  function closeReveal() {
    setRevealedKey("");
    setRevealedName("");
    setRevealedBaseUrl("");
    setCopied(false);
  }

  function renderKeyActions(key: ApiKey) {
    const terminal = key.status === "REVOKED"
      || key.status === "EXPIRED"
      || key.status === "COMPROMISED";
    const disabled = terminal || workingKeyId === key.id;
    return (
      <div className="credential-row-actions" aria-label={`Actions for ${key.name}`}>
        <span className="credential-copy-action">
          <button className="credential-icon-action" type="button" onClick={() => void copyKeyReference(key)} aria-label={`Copy visible key reference for ${key.name}`} title="Copy key reference"><Copy size={15} /></button>
          {copiedReferenceId === key.id && <span className="credential-copy-feedback" role="status">Copied!</span>}
        </span>
        {canReadUsage && canRead && <>
          <button className="credential-icon-action" type="button" onClick={() => onNavigate("usage", key.id)} aria-label={`View usage for ${key.name}`} title="Usage"><Activity size={15} /></button>
          <button className="credential-icon-action" type="button" onClick={() => onNavigate("logs", key.id)} aria-label={`View logs for ${key.name}`} title="Logs"><ScrollText size={15} /></button>
        </>}
        <div className="credential-key-menu-wrap" data-key-action-menu>
          <button className="credential-icon-action" type="button" aria-label={`More actions for ${key.name}`}
            aria-haspopup="menu" aria-expanded={openMenuKeyId === key.id}
            onClick={(event) => {
              if (openMenuKeyId === key.id) {
                setOpenMenuKeyId("");
                return;
              }
              const rect = event.currentTarget.getBoundingClientRect();
              const estimatedMenuHeight = 125;
              const top = rect.bottom + estimatedMenuHeight > window.innerHeight
                ? Math.max(8, rect.top - estimatedMenuHeight - 5)
                : rect.bottom + 5;
              setMenuPosition({ top, right: Math.max(8, window.innerWidth - rect.right) });
              setOpenMenuKeyId(key.id);
            }}>
            <MoreVertical size={17} />
          </button>
          {openMenuKeyId === key.id && createPortal(<div className="credential-key-menu" data-key-action-menu role="menu" aria-label={`Actions for ${key.name}`} style={menuPosition}>
            {canUpdate && <button type="button" role="menuitem" onClick={() => {
              setRenameTarget(key);
              setRenameValue(key.name);
              setOpenMenuKeyId("");
            }}><Pencil size={14} />Rename</button>}
            {canRotate && <button type="button" role="menuitem" disabled={disabled} onClick={() => {
              setOpenMenuKeyId("");
              beginAction(key, "rotate");
            }}><RotateCw size={14} />Regenerate</button>}
            {canRevoke && <button className="credential-key-menu__danger" type="button" role="menuitem" disabled={disabled} onClick={() => {
              setOpenMenuKeyId("");
              beginAction(key, "revoke");
            }}><Trash2 size={14} />Delete key</button>}
            {!canUpdate && !canRotate && !canRevoke && <span>No management actions available</span>}
          </div>, document.body)}
        </div>
      </div>
    );
  }

  if (!isAuthenticated) return <><PageHeader eyebrow="CREDENTIALS" title="API keys" description="Sign in to load and manage real API credentials." /><section className="panel backend-capability-state"><span className="backend-capability-icon"><KeyRound size={18} /></span><h2>Authentication required</h2><p>API key data is private. Sign in to view keys returned by the system; no example credentials are displayed.</p></section></>;

  return (
    <>
      <PageHeader
        eyebrow="BUILD · SECURITY"
        title="API credentials"
        description="Issue environment-bound keys, constrain access, and respond to credential risk."
        action={<div className="credential-header-actions">
          {canCreate && <button
            className="button button--primary"
            type="button"
            aria-haspopup="dialog"
            aria-expanded={createDialogOpen}
            onClick={() => { setError(""); setNotice(""); setCreateDialogOpen(true); }}
          ><Plus size={15} />Create API key</button>}
          <a className="button button--secondary" href={`${externalLinks.docs}/security/`} target="_blank" rel="noreferrer">Key security guide <ArrowUpRight size={14} /></a>
        </div>}
      />

      <div className="credential-page">
      {error && !createDialogOpen && <p className="workflow-error" role="alert">{error}</p>}
      {notice && <p className="workflow-success" role="status">{notice}</p>}

      <section className="credential-summary credential-hero panel">
        <div className="credential-icon"><ShieldCheck size={20} /></div>
        <div className="credential-hero-copy">
          <span className="section-eyebrow">CREDENTIAL CONTROL</span>
          <h2>Keys designed for least-privilege access</h2>
          <p>Bind every key to one environment, grant only the scopes it needs, and rotate or revoke access from this workspace.</p>
          <div className="credential-hero-points">
            <span><i />Environment-bound</span>
            <span><i />Scoped permissions</span>
            <span><i />One-time secret reveal</span>
          </div>
        </div>
        <a className="credential-hero-link" href={`${externalLinks.docs}/security/`} target="_blank" rel="noreferrer">
          <KeyRound size={18} />
          <span><strong>Protect your credentials</strong><small>Read the security guide</small></span>
          <ArrowUpRight size={14} />
        </a>
      </section>

      <section className="credential-metrics" aria-label="Credential summary">
        <article className="panel credential-metric credential-metric--active">
          <div className="credential-metric-top"><span>Active keys</span><i><KeyRound size={15} /></i></div>
          <strong>{isAuthenticated ? activeKeys : "—"}</strong><small>Bound to selected environment</small>
        </article>
        <article className="panel credential-metric credential-metric--expiring">
          <div className="credential-metric-top"><span>Expiring soon</span><i><Clock3 size={15} /></i></div>
          <strong>{isAuthenticated ? expiringKeys : "—"}</strong><small>Within the next 30 days</small>
        </article>
        <article className="panel credential-metric credential-metric--requests">
          <div className="credential-metric-top"><span>Recorded requests</span><i><Activity size={15} /></i></div>
          <strong>{isAuthenticated ? totalRequests.toLocaleString() : "—"}</strong><small>Reported by key authentication telemetry</small>
        </article>
        <article className="panel credential-metric credential-metric--environment">
          <div className="credential-metric-top"><span>Selected environment</span><i><Gauge size={15} /></i></div>
          <strong>{selectedEnvironment?.type ?? "—"}</strong><small>{selectedEnvironment?.name ?? "Choose an environment"}</small>
        </article>
      </section>

      {isAuthenticated && (
        <div className="credential-workspace">
          {canRead ? <div className="credential-main">
            <section className="panel credential-filter-panel">
              <div className="panel-heading"><div><h2>Environment key inventory</h2><p>Key lists are scoped to one project and one environment.</p></div><span className="table-tag">{selectedEnvironment?.type ?? "NO ENVIRONMENT"}</span></div>
              <div className="credential-selectors">
                <label><span>Project</span><select className="field-control" value={projectId} onChange={(event) => selectProject(event.target.value)} disabled={loading || projects.length === 0}>{projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
                <label><span>Environment</span><select className="field-control" value={environmentId} onChange={(event) => selectEnvironment(event.target.value)} disabled={loading || projectEnvironments.length === 0}>{projectEnvironments.map((environment) => <option key={environment.id} value={environment.id}>{environment.name} · {environment.type}</option>)}</select></label>
              </div>
              <div className="table-scroll credential-desktop-inventory">
                <table className="data-table credential-table">
                  <thead><tr><th>KEY</th><th>BOUND ENVIRONMENT</th><th>SCOPES</th><th>LAST USED</th><th>REQUESTS</th><th>STATE</th><th><span className="visually-hidden">Actions</span></th></tr></thead>
                  <tbody>
                    {keys.map((key) => (
                      <tr key={key.id}>
                        <td><strong>{key.name}</strong><code className="masked-key">{key.prefix}••••••••••••••</code><small>Expires {formatDate(key.expiresAt)}</small></td>
                        <td>{environments.find((environment) => environment.id === key.environmentId)?.name ?? "Environment"}<small>{environments.find((environment) => environment.id === key.environmentId)?.type ?? "—"}</small></td>
                        <td><span className="credential-scope-count">{key.scopes.length} scopes</span><small>{key.scopes.slice(0, 2).join(", ")}{key.scopes.length > 2 ? ` +${key.scopes.length - 2}` : ""}</small></td>
                        <td>{formatDate(key.lastUsedAt)}<small>{key.lastUsedIp ? `IP ${key.lastUsedIp}` : "Source IP not reported"}</small><small>{[key.lastUsedCountry, key.lastUsedDevice].filter(Boolean).join(" · ") || "Country/device metadata not reported"}</small></td>
                        <td>{key.requestCount.toLocaleString()}</td>
                        <td><span className={`credential-status ${statusClass(key.status)}`}><i />{statusLabel(key.status)}</span></td>
                        <td>{renderKeyActions(key)}</td>
                      </tr>
                    ))}
                    {keys.length === 0 && <tr><td colSpan={7} className="credential-empty-row">{loading ? "Loading keys…" : "No API keys exist for this environment yet."}</td></tr>}
                  </tbody>
                </table>
              </div>
              <div className="credential-mobile-inventory" aria-label="Environment key inventory">
                {keys.map((key) => {
                  const environment = environments.find((item) => item.id === key.environmentId);
                  return (
                    <article className="credential-key-card panel" key={key.id}>
                      <div className="credential-key-card-heading">
                        <div><strong>{key.name}</strong><code className="masked-key">{key.prefix}••••••••••••••</code></div>
                        <span className={`credential-status ${statusClass(key.status)}`}><i />{statusLabel(key.status)}</span>
                      </div>
                      <dl className="credential-key-card-details">
                        <div><dt>Environment</dt><dd>{environment?.name ?? "Environment"} · {environment?.type ?? "—"}</dd></div>
                        <div><dt>Scopes</dt><dd>{key.scopes.length} · {key.scopes.slice(0, 2).join(", ")}{key.scopes.length > 2 ? ` +${key.scopes.length - 2}` : ""}</dd></div>
                        <div><dt>Last used</dt><dd>{formatDate(key.lastUsedAt)}</dd></div>
                        <div><dt>Requests</dt><dd>{key.requestCount.toLocaleString()}</dd></div>
                        <div><dt>Expires</dt><dd>{formatDate(key.expiresAt)}</dd></div>
                      </dl>
                      <div className="credential-key-card-actions">{renderKeyActions(key)}</div>
                    </article>
                  );
                })}
                {keys.length === 0 && <p className="credential-empty-row">{loading ? "Loading keys…" : "No API keys exist for this environment yet."}</p>}
              </div>
              <div className="table-bottom"><span>{keys.length} keys in selected environment</span><span>Secrets are not returned by this list.</span></div>
            </section>

          </div> : <section className="panel backend-capability-state"><h2>Key inventory access is not granted</h2><p>You can issue credentials in the permitted project scope, but cannot view existing key metadata.</p></section>}
        </div>
      )}
      </div>

      {(createDialogOpen || revealedKey || pendingAction || renameTarget) && (
        <div className="credential-modal-backdrop" onMouseDown={(event) => {
          if (event.target === event.currentTarget) {
            if (createDialogOpen && !saving) {
              setCreateDialogOpen(false);
              setError("");
            }
            closeReveal();
            setPendingAction(null);
            setSelectedKey(null);
            setRenameTarget(null);
          }
        }}>
          {createDialogOpen && canCreate ? (
            <section className="credential-modal credential-create-modal credential-create panel" role="dialog" aria-modal="true" aria-labelledby="credential-create-title">
              <div className="panel-heading">
                <div className="credential-create-heading">
                  <span className="credential-modal-icon"><KeyRound size={18} /></span>
                  <div><span className="section-eyebrow">ONE-TIME SECRET</span><h2 id="credential-create-title">Create API key</h2><p>Choose an environment and grant only the access this key needs.</p></div>
                </div>
                <button className="credential-modal-close" type="button" aria-label="Close create API key dialog" disabled={saving} onClick={() => { setCreateDialogOpen(false); setError(""); }}><X size={18} /></button>
              </div>
              <ValidatedForm onSubmit={(event) => void createKey(event)}>
                <div className="credential-create-fields">
                  <div>
                    <label className="field-label" htmlFor="credential-project">Project</label>
                    <select id="credential-project" className="field-control" value={projectId} onChange={(event) => selectProject(event.target.value)} disabled={loading || projects.length === 0}>
                      <option value="">Select project</option>
                      {projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
                    </select>
                  </div>
                  <div>
                    <label className="field-label" htmlFor="credential-environment">Environment binding</label>
                    <select id="credential-environment" className="field-control" value={environmentId} onChange={(event) => selectEnvironment(event.target.value)} disabled={loading || projectEnvironments.length === 0}>
                      <option value="">Select environment</option>
                      {projectEnvironments.map((environment) => <option key={environment.id} value={environment.id}>{environment.name} · {environment.type} · {environment.status}</option>)}
                    </select>
                  </div>
                  <div>
                    <label className="field-label" htmlFor="credential-name">Key name</label>
                    <input id="credential-name" className="field-control" value={name} onChange={(event) => setName(event.target.value)} minLength={2} maxLength={120} placeholder="e.g. Payments system" required />
                  </div>
                  <div>
                    <label className="field-label" htmlFor="credential-expiration">Expiration</label>
                    <select id="credential-expiration" className="field-control" value={expiresIn} onChange={(event) => setExpiresIn(event.target.value)}>
                      {expirationChoices.map((choice) => <option key={choice.value} value={choice.value}>{choice.label}</option>)}
                    </select>
                  </div>
                </div>
                <fieldset className="credential-scope-fieldset">
                  <legend>Allowed API scopes</legend>
                  <p className="field-help">Only the selected scopes will be attached to this credential.</p>
                  {scopes.length === 0
                    ? <p className="credential-empty-scopes">{loading ? "Loading scope catalog…" : "No scopes are available to assign."}</p>
                    : <div className="credential-scope-list">{scopes.map((scope) => {
                      const disabled = !scope.apiKeyAssignable || scope.deprecated || scope.restricted;
                      const unavailableNote = !scope.apiKeyAssignable ? " · Not implemented for API keys" : "";
                      return (
                        <label className={`credential-scope${disabled ? " credential-scope--disabled" : ""}`} key={scope.name}>
                          <input
                            type="checkbox"
                            checked={selectedScopes.includes(scope.name)}
                            disabled={disabled}
                            onChange={(event) => setSelectedScopes((current) => event.target.checked
                              ? [...current, scope.name]
                              : current.filter((item) => item !== scope.name))}
                          />
                          <span><strong>{scope.name}</strong><small>{scope.description}{unavailableNote}{scope.deprecated ? " · Deprecated" : scope.restricted ? " · Restricted by policy" : ""}</small></span>
                        </label>
                      );
                    })}</div>}
                </fieldset>
                {error && <p className="workflow-error" role="alert">{error}</p>}
                {selectedEnvironment && selectedEnvironment.status !== "ACTIVE" && <p className="field-help">This environment is not active, so key issuance is disabled.</p>}
                <div className="credential-modal-actions">
                  <button className="button button--secondary" type="button" disabled={saving} onClick={() => { setCreateDialogOpen(false); setError(""); }}>Cancel</button>
                  <button className="button button--primary" type="submit" disabled={saving || loading || !isAuthenticated || !selectedEnvironment || selectedEnvironment.status !== "ACTIVE" || scopes.length === 0}>
                    <Plus size={15} />{saving ? "Creating…" : "Create API key"}
                  </button>
                </div>
              </ValidatedForm>
            </section>
          ) : revealedKey ? (
            <section className="credential-modal panel" role="dialog" aria-modal="true" aria-labelledby="credential-reveal-title">
              <button className="credential-modal-close" type="button" aria-label="Close one-time reveal" onClick={closeReveal}><X size={18} /></button>
              <div className="credential-modal-icon"><KeyRound size={19} /></div>
              <h2 id="credential-reveal-title">Copy your new API key</h2>
              <p><strong>{revealedName}</strong> — this secret is displayed once. Close this view only after storing it securely.</p>
              <div className="credential-secret-box"><code>{revealedKey}</code><button className="button button--secondary" type="button" onClick={() => void copySecret()}>{copied ? <Check size={15} /> : <Copy size={15} />}{copied ? "Copied" : "Copy secret"}</button></div>
              <div className="credential-reveal-integration">
                <div><span>Environment Base URL</span><code>{revealedBaseUrl}</code><button className="text-button" type="button" onClick={() => void copyBaseUrl(revealedBaseUrl)}><Copy size={13} />Copy URL</button></div>
                <div><span>Authentication</span><code>Authorization: Bearer &lt;API_KEY&gt;</code></div>
              </div>
              <p className="credential-warning"><AlertTriangle size={15} />After closing this view, the full secret cannot be retrieved. If lost, rotate the key.</p>
              <button className="button button--primary credential-submit" type="button" onClick={closeReveal}>I’ve stored this key</button>
            </section>
          ) : pendingAction ? (
            <section className="credential-modal panel" role="alertdialog" aria-modal="true" aria-labelledby="credential-action-title">
              <button className="credential-modal-close" type="button" aria-label="Cancel action" onClick={() => setPendingAction(null)}><X size={18} /></button>
              <div className="credential-modal-icon credential-modal-icon--warning"><AlertTriangle size={19} /></div>
              <h2 id="credential-action-title">{pendingAction === "rotate" ? "Regenerate this API key?" : "Delete this API key?"}</h2>
              <p>{pendingAction === "rotate"
                ? `The current secret for “${selectedKey?.name}” will stop working immediately. The replacement secret is shown only once.`
                : `“${selectedKey?.name}” will be disabled immediately. Its audit history will be retained.`}</p>
              <div className="credential-modal-actions"><button className="button button--secondary" type="button" onClick={() => setPendingAction(null)}>Cancel</button><button className="button button--danger" type="button" disabled={workingKeyId === selectedKey?.id} onClick={() => void confirmAction()}>{workingKeyId ? "Working…" : pendingAction === "rotate" ? "Regenerate key" : "Delete key"}</button></div>
            </section>
          ) : renameTarget ? (
            <section className="credential-modal panel" role="dialog" aria-modal="true" aria-labelledby="credential-rename-title">
              <button className="credential-modal-close" type="button" aria-label="Close rename dialog" disabled={renaming} onClick={() => setRenameTarget(null)}><X size={18} /></button>
              <div className="credential-modal-icon"><Pencil size={18} /></div>
              <h2 id="credential-rename-title">Rename API key</h2>
              <p>Change the label for <strong>{renameTarget.name}</strong>. Its secret and access remain unchanged.</p>
              <ValidatedForm onSubmit={(event) => void renameKey(event)}>
                <label className="field-label" htmlFor="credential-rename-name">Key name</label>
                <input id="credential-rename-name" className="field-control" value={renameValue} onChange={(event) => setRenameValue(event.target.value)} minLength={2} maxLength={120} required autoFocus />
                {error && <p className="workflow-error" role="alert">{error}</p>}
                <div className="credential-modal-actions"><button className="button button--secondary" type="button" disabled={renaming} onClick={() => setRenameTarget(null)}>Cancel</button><button className="button button--primary" type="submit" disabled={renaming || renameValue.trim().length < 2}>{renaming ? "Saving…" : "Save name"}</button></div>
              </ValidatedForm>
            </section>
          ) : null}
        </div>
      )}
    </>
  );
}
