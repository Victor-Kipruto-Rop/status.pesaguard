import { getUserMessage } from "../../lib/errors";
import { useEffect, useMemo, useState } from "react";
import { Check, Clipboard, Eye, EyeOff, KeyRound, Play, ShieldCheck, Terminal, Timer } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { API_BASE_URL as PORTAL_API_BASE_URL, ApiError, NetworkRequestError, apiData } from "../../lib/api";
import { copyTextToClipboard } from "../../lib/clipboard";
import {
  readActiveEnvironmentId,
  readActiveProjectId,
  setActiveEnvironment,
  setActiveProject,
} from "../../lib/activeEnvironment";

type Endpoint = {
  id: string;
  label: string;
  path: string;
  description: string;
  scope?: string;
};
type AuthenticationMode = "session" | "api-key";
type ApiEnvironment = {
  id: string;
  projectId: string;
  name: string;
  type: string;
  status: string;
  baseUrl: string;
};
type ApiProject = { id: string; name: string };
type ProjectList = { items: ApiProject[] };

const sessionEndpoints: Endpoint[] = [
  { id: "organization", label: "Personal workspace", path: "/api/v1/organization", description: "Read the personal workspace associated with your signed-in session." },
  { id: "projects", label: "Projects", path: "/api/v1/projects?page=0&size=20", description: "List projects available to your personal workspace." },
  { id: "members", label: "Organization members", path: "/api/v1/organization/members", description: "Read persisted member and invitation status records." },
  { id: "sessions", label: "Active sessions", path: "/api/v1/auth/sessions", description: "List sessions owned by your signed-in account." },
  { id: "audit", label: "Audit events", path: "/api/v1/audit-events?page=0&size=20", description: "Read the latest organization-scoped audit records." },
  { id: "notifications", label: "Notification inbox", path: "/api/v1/notifications", description: "Read saved notifications and their current read state." },
  { id: "sandboxes", label: "Sandbox instances", path: "/api/v1/sandboxes", description: "List persisted sandbox instances for this organization." },
];

const apiKeyEndpoints: Endpoint[] = [
  { id: "usage", label: "Usage summary", path: "/api/v1/key-data/usage", description: "Usage totals for the key's bound project and environment.", scope: "usage:read" },
  { id: "audit", label: "Project audit events", path: "/api/v1/key-data/audit-events?page=0&size=20", description: "A project-scoped page of persisted audit events.", scope: "audit:read" },
  { id: "events", label: "Project events", path: "/api/v1/key-data/events?page=0&size=20", description: "Recent event records for the key's project.", scope: "events:read" },
  { id: "event-catalog", label: "Event catalog", path: "/api/v1/key-data/events/catalog", description: "Active and deprecated event definitions.", scope: "events:read" },
  { id: "subscriptions", label: "Event subscriptions", path: "/api/v1/key-data/events/subscriptions", description: "Subscriptions for the key's project and environment.", scope: "events:read" },
  { id: "deliveries", label: "Event deliveries", path: "/api/v1/key-data/events/deliveries", description: "Delivery attempts for the key's project and environment.", scope: "events:read" },
  { id: "webhooks", label: "Webhook endpoints", path: "/api/v1/key-data/webhooks", description: "Webhook endpoints registered for the key's project.", scope: "webhooks:read" },
];

export function ApiExplorerPage() {
  const [authenticationMode, setAuthenticationMode] = useState<AuthenticationMode>("session");
  const [apiKey, setApiKey] = useState("");
  const [apiKeyVisible, setApiKeyVisible] = useState(false);
  const [projects, setProjects] = useState<ApiProject[]>([]);
  const [environments, setEnvironments] = useState<ApiEnvironment[]>([]);
  const [projectId, setProjectId] = useState("");
  const [environmentId, setEnvironmentId] = useState("");
  const [environmentError, setEnvironmentError] = useState("");
  const [environmentLoading, setEnvironmentLoading] = useState(false);
  const [endpointId, setEndpointId] = useState(sessionEndpoints[0].id);
  const [response, setResponse] = useState("");
  const [error, setError] = useState("");
  const [durationMs, setDurationMs] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [requestPolicy, setRequestPolicy] = useState<{ timeoutMs: number; retryCount: number } | null>(null);
  const [policyLoading, setPolicyLoading] = useState(true);
  const [preferencesError, setPreferencesError] = useState("");
  const [policyRetry, setPolicyRetry] = useState(0);
  const [copyState, setCopyState] = useState<"idle" | "request" | "response">("idle");
  const [copyError, setCopyError] = useState("");

  const availableEndpoints = authenticationMode === "session" ? sessionEndpoints : apiKeyEndpoints;
  const endpoint = availableEndpoints.find((item) => item.id === endpointId) ?? availableEndpoints[0];
  const exampleToken = authenticationMode === "api-key" ? "$PESAGUARD_API_KEY" : "$PESAGUARD_ACCESS_TOKEN";
  const projectEnvironments = useMemo(
    () => environments.filter((environment) => environment.projectId === projectId),
    [environments, projectId],
  );
  const selectedEnvironment = projectEnvironments.find((environment) => environment.id === environmentId);
  const productionKeyBlocked = authenticationMode === "api-key" && selectedEnvironment?.type === "PRODUCTION";
  const requestBaseUrl = authenticationMode === "api-key"
    ? selectedEnvironment?.baseUrl?.replace(/\/+$/, "") ?? ""
    : PORTAL_API_BASE_URL;
  const curlExample = `curl --request GET "${requestBaseUrl}${endpoint.path}" \\
  --header "Accept: application/json" \\
  --header "Authorization: Bearer ${exampleToken}"`;

  useEffect(() => {
    if (authenticationMode !== "api-key") return;
    let active = true;
    setEnvironmentLoading(true);
    setEnvironmentError("");
    apiData<ProjectList>("/api/v1/projects?page=0&size=100")
      .then(async (payload) => {
        if (!Array.isArray(payload.items)) throw new Error("The projects API returned an invalid list.");
        const nextEnvironments = (await Promise.all(payload.items.map((project) =>
          apiData<ApiEnvironment[]>(`/api/v1/projects/${encodeURIComponent(project.id)}/environments`))))
          .flat();
        if (!active) return;
        setProjects(payload.items);
        setEnvironments(nextEnvironments);
        const savedProjectId = readActiveProjectId();
        const nextProjectId = payload.items.some((project) => project.id === savedProjectId)
          ? savedProjectId
          : payload.items.some((project) => project.id === projectId) ? projectId : payload.items[0]?.id ?? "";
        setProjectId(nextProjectId);
        const savedEnvironmentId = readActiveEnvironmentId(nextProjectId);
        const nextEnvironment = nextEnvironments.find((environment) =>
          environment.id === savedEnvironmentId && environment.projectId === nextProjectId)
          ?? nextEnvironments.find((environment) => environment.projectId === nextProjectId
            && environment.type === "SANDBOX" && environment.status === "ACTIVE")
          ?? nextEnvironments.find((environment) => environment.projectId === nextProjectId
            && environment.status === "ACTIVE");
        setEnvironmentId(nextEnvironment?.id ?? "");
        const nextProject = payload.items.find((project) => project.id === nextProjectId);
        if (nextProject && nextEnvironment) setActiveEnvironment(nextProject, nextEnvironment);
      })
      .catch((cause: unknown) => {
        if (active) setEnvironmentError(getUserMessage(cause, "Unable to load project environments."));
      })
      .finally(() => {
        if (active) setEnvironmentLoading(false);
      });
    return () => { active = false; };
  }, [authenticationMode]);

  useEffect(() => {
    function syncEnvironmentSelection() {
      const nextProjectId = readActiveProjectId();
      if (projects.some((project) => project.id === nextProjectId)) {
        setProjectId(nextProjectId);
        const nextEnvironmentId = readActiveEnvironmentId(nextProjectId);
        if (environments.some((environment) =>
          environment.id === nextEnvironmentId && environment.projectId === nextProjectId)) {
          setEnvironmentId(nextEnvironmentId);
          setApiKey("");
          clearResult();
        }
      }
    }
    window.addEventListener("pesaguard:active-context-changed", syncEnvironmentSelection);
    return () => window.removeEventListener("pesaguard:active-context-changed", syncEnvironmentSelection);
  }, [environments, projects]);

  useEffect(() => {
    let active = true;
    setPolicyLoading(true);
    setPreferencesError("");
    apiData<{ requestTimeoutMs: number; retryCount: number }>("/api/v1/developer/preferences")
      .then((preferences) => {
        if (active) {
          setRequestPolicy({
            timeoutMs: preferences.requestTimeoutMs,
            retryCount: preferences.retryCount,
          });
        }
      })
      .catch((cause: unknown) => {
        if (active) setPreferencesError(getUserMessage(cause, "Unable to load request preferences."));
      })
      .finally(() => {
        if (active) setPolicyLoading(false);
      });
    return () => { active = false; };
  }, [policyRetry]);

  function clearResult() {
    setResponse("");
    setError("");
    setDurationMs(null);
    setCopyError("");
    setCopyState("idle");
  }

  function selectAuthentication(mode: AuthenticationMode) {
    if (mode === authenticationMode) return;
    setAuthenticationMode(mode);
    setEndpointId((mode === "session" ? sessionEndpoints : apiKeyEndpoints)[0].id);
    setApiKey("");
    setApiKeyVisible(false);
    clearResult();
  }

  function selectProject(nextProjectId: string) {
    const project = projects.find((item) => item.id === nextProjectId);
    if (!project) return;
    setProjectId(project.id);
    const nextProjectEnvironments = environments.filter((item) => item.projectId === project.id);
    const environment = nextProjectEnvironments.find((item) =>
      item.id === readActiveEnvironmentId(project.id))
      ?? nextProjectEnvironments.find((item) => item.type === "SANDBOX" && item.status === "ACTIVE")
      ?? nextProjectEnvironments.find((item) => item.status === "ACTIVE");
    setEnvironmentId(environment?.id ?? "");
    setApiKey("");
    setApiKeyVisible(false);
    clearResult();
    setActiveProject(project);
    if (environment) setActiveEnvironment(project, environment);
  }

  function selectEnvironment(nextEnvironmentId: string) {
    const project = projects.find((item) => item.id === projectId);
    const environment = projectEnvironments.find((item) => item.id === nextEnvironmentId);
    if (!project || !environment) return;
    setEnvironmentId(environment.id);
    setApiKey("");
    setApiKeyVisible(false);
    clearResult();
    setActiveEnvironment(project, environment);
  }

  async function sendRequest() {
    if (!requestPolicy || (authenticationMode === "api-key"
      && (!apiKey.trim() || !selectedEnvironment || !requestBaseUrl || productionKeyBlocked))) return;
    setLoading(true);
    clearResult();
    const startedAt = performance.now();
    try {
      const data = await apiData<unknown>(endpoint.path, authenticationMode === "api-key"
        ? {
          anonymous: true,
          headers: { Authorization: `Bearer ${apiKey.trim()}` },
          requestPolicy,
          baseUrl: requestBaseUrl,
        }
        : { requestPolicy });
      setResponse(data === undefined ? "No response body returned." : JSON.stringify(data, null, 2));
    } catch (requestError) {
      if (requestError instanceof ApiError) {
        const reference = requestError.supportReference
          ? `\nReference: ${requestError.supportReference}`
          : "";
        setError(`Request failed\n\n${getUserMessage(requestError, "We couldn't complete this request. Please try again.")}\n\nStatus: ${requestError.status}${reference}`);
      } else {
        setError(requestError instanceof NetworkRequestError
          ? getUserMessage(requestError)
          : "We couldn't complete this request. Please try again.");
      }
    } finally {
      setDurationMs(Math.round(performance.now() - startedAt));
      setLoading(false);
      if (authenticationMode === "api-key") {
        setApiKey("");
        setApiKeyVisible(false);
      }
    }
  }

  async function copyText(text: string, kind: "request" | "response") {
    setCopyError("");
    try {
      await copyTextToClipboard(text);
      setCopyState(kind);
      window.setTimeout(() => setCopyState((current) => current === kind ? "idle" : current), 1800);
    } catch {
      setCopyError("Clipboard access is unavailable. Select and copy the text manually.");
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="DEVELOPER TOOLS"
        title="API Explorer"
        description="Choose a supported endpoint, send a real read-only request, and inspect the response from your PesaGuard server."
      />

      <div className="api-live-workbench">
        <div className="api-live-workbench-heading">
          <div>
            <span className="section-eyebrow">INTERACTIVE WORKBENCH</span>
            <h2>Make a safe test request</h2>
            <p>Explore supported endpoints with scoped credentials. Requests are read-only and secrets are cleared after use.</p>
          </div>
          <div className="api-live-workbench-badges" aria-label="Explorer capabilities">
            <span><ShieldCheck size={13} />GET only</span>
            <span><KeyRound size={13} />Scoped access</span>
            {authenticationMode === "api-key" && <span><Terminal size={13} />Environment host</span>}
          </div>
        </div>
        <section className="api-live-explorer" aria-label="API request explorer">
        <div className="panel api-live-request">
          <div className="api-live-heading">
            <div>
              <span className="section-eyebrow">REQUEST BUILDER</span>
              <h2>Try an endpoint</h2>
              <p>Requests are sent to your configured API server. This explorer only makes GET requests.</p>
            </div>
            <span className="api-live-readonly"><ShieldCheck size={14} />Read-only</span>
          </div>

          <fieldset className="api-live-auth">
            <legend>Choose authentication</legend>
            <div className="api-live-auth-options">
              <button type="button" className={authenticationMode === "session" ? "is-active" : ""} aria-pressed={authenticationMode === "session"} onClick={() => selectAuthentication("session")}>
                <ShieldCheck size={15} /><span><strong>Portal session</strong><small>Use your current sign-in</small></span>
              </button>
              <button type="button" className={authenticationMode === "api-key" ? "is-active" : ""} aria-pressed={authenticationMode === "api-key"} onClick={() => selectAuthentication("api-key")}>
                <KeyRound size={15} /><span><strong>API key</strong><small>Use a project-scoped key</small></span>
              </button>
            </div>
          </fieldset>

          {authenticationMode === "api-key" && (
            <>
              <div className="api-live-environment-fields">
                <label className="api-live-label">Project
                  <select className="field-control" value={projectId} onChange={(event) => selectProject(event.target.value)} disabled={environmentLoading || projects.length === 0}>
                    <option value="">Select project</option>
                    {projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
                  </select>
                </label>
                <label className="api-live-label">Environment
                  <select className="field-control" value={environmentId} onChange={(event) => selectEnvironment(event.target.value)} disabled={environmentLoading || projectEnvironments.length === 0}>
                    <option value="">Select environment</option>
                    {projectEnvironments.map((environment) => <option key={environment.id} value={environment.id} disabled={environment.status !== "ACTIVE"}>{environment.name} · {environment.type} · {environment.status}</option>)}
                  </select>
                </label>
              </div>
              {environmentError && <p className="workflow-error" role="alert">Unable to load environments: {environmentError}</p>}
              {selectedEnvironment && <div className="api-live-environment-url">
                <span>{selectedEnvironment.type} Base URL</span><code>{selectedEnvironment.baseUrl}</code>
                <small>API-key requests go directly to this environment host so the key is validated against its bound environment.</small>
              </div>}
              {productionKeyBlocked
                ? <div className="api-live-production-key-notice" role="status"><ShieldCheck size={16} /><span><strong>Production keys stay server-side.</strong> Do not paste production credentials into a browser. Use the generated integration snippet with this production Base URL.</span></div>
                : <div className="api-live-label">
                  <label htmlFor="api-live-api-key">API key</label>
                  <span className="api-live-secret-field">
                    <input id="api-live-api-key" className="field-control" type={apiKeyVisible ? "text" : "password"} autoComplete="off" spellCheck={false} aria-describedby="api-live-api-key-help" value={apiKey} onChange={(event) => setApiKey(event.target.value)} placeholder="Paste a sandbox API key" disabled={!selectedEnvironment || selectedEnvironment.type === "PRODUCTION"} />
                    <button
                      className="api-live-secret-toggle"
                      type="button"
                      aria-label={apiKeyVisible ? "Hide API key" : "Show API key"}
                      aria-pressed={apiKeyVisible}
                      onClick={() => setApiKeyVisible((visible) => !visible)}
                      disabled={!apiKey}
                    >
                      {apiKeyVisible ? <EyeOff size={16} /> : <Eye size={16} />}
                    </button>
                  </span>
                  <span id="api-live-api-key-help" className="api-live-description">Sandbox key is used for this request only, is not stored, and is cleared after completion.</span>
                </div>}
            </>
          )}

          <label className="api-live-label">
            Endpoint
            <select className="field-control" value={endpoint.id} onChange={(event) => { setEndpointId(event.target.value); clearResult(); }}>
              {availableEndpoints.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}
            </select>
          </label>
          <p className="api-live-description api-live-endpoint-description">{endpoint.description}{endpoint.scope && <> Required scope: <code>{endpoint.scope}</code>.</>}</p>

          <div className="api-live-request-preview">
            <div className="api-live-selected">
              <span className="api-live-method">GET</span>
              <code>{endpoint.path}</code>
            </div>
            <span className="api-live-host">{requestBaseUrl || "Select an environment to see the API host"}</span>
            {endpoint.scope && <span className="api-live-scope">Required API-key scope <code>{endpoint.scope}</code></span>}
          </div>

          <div className="api-live-policy">
            <span><Timer size={14} />Request policy</span>
            {requestPolicy
              ? <small>{requestPolicy.timeoutMs.toLocaleString()} ms timeout · {requestPolicy.retryCount} safe retries</small>
              : <small>{policyLoading ? "Loading your saved preferences…" : "Unavailable"}</small>}
          </div>
          {preferencesError && <div className="api-live-policy-error" role="alert">
            <span>Could not load request preferences: {preferencesError}</span>
            <button type="button" className="text-button" onClick={() => setPolicyRetry((retry) => retry + 1)}>Try again</button>
          </div>}

          <details className="api-live-curl">
            <summary><Terminal size={14} />View cURL example</summary>
            <pre>{curlExample}</pre>
            <button className="text-button" type="button" onClick={() => void copyText(curlExample, "request")}>
              {copyState === "request" ? <Check size={14} /> : <Clipboard size={14} />}
              {copyState === "request" ? "Copied" : "Copy example"}
            </button>
          </details>

          <div className="api-live-actions">
            <button className="button button--primary" type="button" disabled={loading || !requestPolicy || (authenticationMode === "api-key" && (!apiKey.trim() || !selectedEnvironment || !requestBaseUrl || productionKeyBlocked))} onClick={() => void sendRequest()}>
              <Play size={14} />{loading ? "Waiting for the server…" : "Send request"}
            </button>
          </div>
        </div>

        <section className="panel api-live-response" aria-label="Server response">
          <div className="api-live-heading api-live-response-heading">
            <div>
              <span className="section-eyebrow">LIVE SERVER RESPONSE</span>
              <h2>Response</h2>
              <p>{durationMs === null ? "Your response will appear here." : `Request completed in ${durationMs.toLocaleString()} ms.`}</p>
            </div>
            {response && <button className="button button--secondary api-live-copy" type="button" onClick={() => void copyText(response, "response")}>
              {copyState === "response" ? <Check size={14} /> : <Clipboard size={14} />}
              {copyState === "response" ? "Copied" : "Copy response"}
            </button>}
          </div>
          {error
            ? <pre className="api-live-output api-live-error" role="alert">{error}</pre>
            : response
              ? <pre className="api-live-output" aria-live="polite">{response}</pre>
              : <div className={`api-live-empty${loading ? " is-loading" : ""}`} aria-live="polite">
                <span className="api-live-empty-icon">{loading ? <Timer size={18} /> : <Terminal size={18} />}</span>
                <strong>{loading ? "Waiting for the server" : "Ready when you are"}</strong>
                <span>{loading ? "The request is in progress." : "Send a request to see live data from this endpoint."}</span>
              </div>}
          {copyError && <p className="api-live-copy-error" role="status">{copyError}</p>}
        </section>
        </section>
      </div>
    </>
  );
}
