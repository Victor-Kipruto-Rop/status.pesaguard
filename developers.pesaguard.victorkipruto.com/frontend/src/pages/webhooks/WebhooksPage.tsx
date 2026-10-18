import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useEffect, useState, type FormEvent } from "react";
import {
  Activity,
  AlertCircle,
  Check,
  Copy,
  Download,
  KeyRound,
  Pause,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  RotateCw,
  Send,
  ShieldCheck,
  Trash2,
  Webhook,
  X,
  Zap,
} from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiBlob, apiData } from "../../lib/api";
import { copyTextToClipboard } from "../../lib/clipboard";
import {
  readActiveEnvironmentContext,
  readActiveEnvironmentId,
  readActiveProjectId,
  setActiveEnvironment,
  setActiveProject,
} from "../../lib/activeEnvironment";

type Project = { id: string; name: string };
type Environment = { id: string; projectId: string; name: string; type: string; status: string; baseUrl?: string };
type ProjectResponse = { items: Project[] };
type Endpoint = {
  id: string;
  projectId: string;
  environmentId: string;
  name: string;
  url: string;
  status: string;
  createdAt: string;
};
type CreatedEndpoint = { endpoint: Endpoint; signingSecret: string };
type EventType = {
  name: string;
  description: string;
  category: string;
  version: number;
  lifecycle: string;
};
type Subscription = {
  id: string;
  projectId: string;
  environmentId: string;
  endpointId: string;
  eventType: string;
  eventVersion: number;
  status: string;
  description: string | null;
  createdAt: string;
};
type Delivery = {
  id: string;
  eventId: string;
  eventType: string;
  subscriptionId: string;
  environmentId: string;
  endpointId: string;
  attempt: number;
  status: string;
  responseCode: number | null;
  errorCode: string | null;
  latencyMs: number | null;
  nextAttemptAt: string | null;
  createdAt: string;
};
type PageResponse<T> = { items: T[]; totalElements: number };
type DialogMode = "create" | "secret" | null;

function formatTimestamp(value: string | null | undefined) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString();
}

function statusTone(status: string) {
  if (["ACTIVE", "DELIVERED"].includes(status)) return "good";
  if (["SUSPENDED", "PENDING", "IN_FLIGHT", "RETRY_SCHEDULED"].includes(status)) return "warn";
  if (["FAILED", "DEAD_LETTERED"].includes(status)) return "bad";
  return "muted";
}

export function WebhooksPage() {
  const { isAuthenticated, hasPermission } = useAuth();
  const canRead = hasPermission("webhook:read");
  const canCreate = hasPermission("webhook:create");
  const canUpdate = hasPermission("webhook:update");
  const canDelete = hasPermission("webhook:delete");

  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState("");
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [environmentId, setEnvironmentId] = useState("");
  const [endpoints, setEndpoints] = useState<Endpoint[]>([]);
  const [selectedEndpointId, setSelectedEndpointId] = useState("");
  const [catalog, setCatalog] = useState<EventType[]>([]);
  const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [deliveryTotal, setDeliveryTotal] = useState(0);
  const [deliveryPage, setDeliveryPage] = useState(0);
  const [deliveryStatusFilter, setDeliveryStatusFilter] = useState("ALL");
  const [deliveryEndpointFilter, setDeliveryEndpointFilter] = useState("ALL");

  const [dialogMode, setDialogMode] = useState<DialogMode>(null);
  const [created, setCreated] = useState<CreatedEndpoint | null>(null);
  const [endpointName, setEndpointName] = useState("");
  const [url, setUrl] = useState("");
  const [copied, setCopied] = useState(false);

  const [editingEndpointId, setEditingEndpointId] = useState("");
  const [editName, setEditName] = useState("");
  const [editUrl, setEditUrl] = useState("");
  const [eventType, setEventType] = useState("");
  const [subscriptionEndpointId, setSubscriptionEndpointId] = useState("");
  const [saving, setSaving] = useState(false);
  const [replayingDeliveryId, setReplayingDeliveryId] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);
  const [exportingDeliveries, setExportingDeliveries] = useState(false);
  const [deliveryExportTruncated, setDeliveryExportTruncated] = useState(false);

  const selectedEndpoint = endpoints.find((endpoint) => endpoint.id === selectedEndpointId) ?? null;
  const activeEndpoints = endpoints.filter((endpoint) => endpoint.status === "ACTIVE");
  const activeSubscriptions = subscriptions.filter((subscription) => subscription.status === "ACTIVE").length;
  const failedDeliveries = deliveries.filter((delivery) =>
    delivery.status === "FAILED" || delivery.status === "DEAD_LETTERED").length;
  const filteredDeliveries = deliveries.filter((delivery) => {
    const endpointMatches = deliveryEndpointFilter === "ALL" || delivery.endpointId === deliveryEndpointFilter;
    const statusMatches = deliveryStatusFilter === "ALL" || delivery.status === deliveryStatusFilter;
    return endpointMatches && statusMatches;
  });

  useEffect(() => {
    if (!isAuthenticated || (!canRead && !canCreate)) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError("");
    void apiData<ProjectResponse>("/api/v1/projects?page=0&size=100", { signal: controller.signal })
      .then((result) => {
        if (!Array.isArray(result.items)) throw new Error("The projects API returned an invalid response.");
        setProjects(result.items);
        const selected = result.items.find((project) => project.id === readActiveProjectId())
          ?? result.items[0];
        setProjectId((current) =>
          result.items.some((project) => project.id === current) ? current : selected?.id ?? "");
        if (selected) setActiveProject(selected);
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) {
          setError(getUserMessage(requestError, "Projects could not be loaded."));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [isAuthenticated, canRead, canCreate]);

  useEffect(() => {
    if (!isAuthenticated || !projectId) {
      setEnvironments([]);
      setEnvironmentId("");
      return;
    }
    const project = projects.find((item) => item.id === projectId);
    if (!project) return;
    const controller = new AbortController();
    void apiData<Environment[]>(
      `/api/v1/projects/${encodeURIComponent(projectId)}/environments`,
      { signal: controller.signal },
    ).then((result) => {
      if (!Array.isArray(result)) throw new Error("The environments API returned an invalid response.");
      if (controller.signal.aborted) return;
      setEnvironments(result);
      const selected = result.find((environment) => environment.id === readActiveEnvironmentId(projectId))
        ?? result.find((environment) => environment.type === "SANDBOX" && environment.status === "ACTIVE")
        ?? result.find((environment) => environment.status === "ACTIVE");
      setEnvironmentId(selected?.id ?? "");
      if (selected) setActiveEnvironment(project, selected);
    }).catch((requestError: unknown) => {
      if (!controller.signal.aborted) {
        setEnvironments([]);
        setEnvironmentId("");
        setError(getUserMessage(requestError, "Project environments could not be loaded."));
      }
    });
    return () => controller.abort();
  }, [isAuthenticated, projectId, projects]);

  useEffect(() => {
    function syncActiveEnvironment() {
      const context = readActiveEnvironmentContext();
      setProjectId(context.projectId);
      setEnvironmentId(context.environmentId);
    }
    window.addEventListener("pesaguard:active-context-changed", syncActiveEnvironment);
    return () => window.removeEventListener("pesaguard:active-context-changed", syncActiveEnvironment);
  }, []);

  useEffect(() => {
    if (!isAuthenticated || !canRead || !projectId || !environmentId) {
      setEndpoints([]);
      setCatalog([]);
      setSubscriptions([]);
      setDeliveries([]);
      setDeliveryTotal(0);
      setSelectedEndpointId("");
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setEndpoints([]);
    setSubscriptions([]);
    setDeliveries([]);
    setDeliveryTotal(0);
    setSelectedEndpointId("");
    setLoading(true);
    setError("");
    void Promise.allSettled([
      apiData<Endpoint[]>(
        `/api/v1/webhooks/endpoints?projectId=${encodeURIComponent(projectId)}&environmentId=${encodeURIComponent(environmentId)}`,
        { signal: controller.signal },
      ),
      apiData<PageResponse<Delivery>>(
        `/api/v1/events/deliveries?projectId=${encodeURIComponent(projectId)}&environmentId=${encodeURIComponent(environmentId)}&page=${deliveryPage}&size=50`,
        { signal: controller.signal },
      ),
      apiData<EventType[]>("/api/v1/events/catalog", { signal: controller.signal }),
      apiData<PageResponse<Subscription>>(
        `/api/v1/events/subscriptions?projectId=${encodeURIComponent(projectId)}&environmentId=${encodeURIComponent(environmentId)}&page=0&size=100`,
        { signal: controller.signal },
      ),
    ]).then(([endpointResult, deliveryResult, catalogResult, subscriptionResult]) => {
      if (controller.signal.aborted) return;
      const failures: string[] = [];

      if (endpointResult.status === "fulfilled" && Array.isArray(endpointResult.value)) {
        setEndpoints(endpointResult.value);
        setSelectedEndpointId((current) =>
          endpointResult.value.some((endpoint) => endpoint.id === current)
            ? current
            : endpointResult.value.find((endpoint) => endpoint.status === "ACTIVE")?.id
              ?? endpointResult.value[0]?.id
              ?? "");
      } else {
        failures.push(endpointResult.status === "rejected"
          ? getUserMessage(endpointResult.reason, "Endpoints could not be loaded.")
          : "The webhook API returned an invalid endpoint response.");
      }

      if (deliveryResult.status === "fulfilled"
        && deliveryResult.value
        && Array.isArray(deliveryResult.value.items)) {
        setDeliveries(deliveryResult.value.items);
        setDeliveryTotal(deliveryResult.value.totalElements);
      } else {
        failures.push(deliveryResult.status === "rejected"
          ? getUserMessage(deliveryResult.reason, "Delivery history could not be loaded.")
          : "The events API returned an invalid delivery response.");
      }

      if (catalogResult.status === "fulfilled" && Array.isArray(catalogResult.value)) {
        setCatalog(catalogResult.value);
        setEventType((current) =>
          current && catalogResult.value.some((type) => type.name === current && type.lifecycle === "ACTIVE")
            ? current
            : catalogResult.value.find((type) => type.lifecycle === "ACTIVE")?.name ?? "");
      } else {
        failures.push(catalogResult.status === "rejected"
          ? getUserMessage(catalogResult.reason, "Event types could not be loaded.")
          : "The events API returned an invalid catalog response.");
      }

      if (subscriptionResult.status === "fulfilled" && Array.isArray(subscriptionResult.value.items)) {
        setSubscriptions(subscriptionResult.value.items);
      } else {
        failures.push(subscriptionResult.status === "rejected"
          ? getUserMessage(subscriptionResult.reason, "Subscriptions could not be loaded.")
          : "The events API returned an invalid subscription response.");
      }
      setError(failures.join(" "));
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [isAuthenticated, canRead, projectId, environmentId, deliveryPage, refreshKey]);

  useEffect(() => {
    if (!activeEndpoints.some((endpoint) => endpoint.id === subscriptionEndpointId)) {
      setSubscriptionEndpointId(activeEndpoints[0]?.id ?? "");
    }
  }, [activeEndpoints, subscriptionEndpointId]);

  useEffect(() => {
    if (!dialogMode) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !saving) closeDialog();
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", closeOnEscape);
    };
  }, [dialogMode, saving]);

  function closeDialog() {
    setDialogMode(null);
    setCreated(null);
    setCopied(false);
  }

  async function createEndpoint(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError("");
    setMessage("");
    setCopied(false);
    try {
      const result = await apiData<CreatedEndpoint>("/api/v1/webhooks/endpoints", {
        method: "POST",
        body: JSON.stringify({ projectId, environmentId, name: endpointName.trim(), url: url.trim() }),
      });
      if (!result?.endpoint?.id || !result.signingSecret) {
        throw new Error("The webhook API did not return the one-time signing secret.");
      }
      setCreated(result);
      setMessage(`Endpoint ${result.endpoint.name} has been registered.`);
      setEndpointName("");
      setUrl("");
      setDialogMode("secret");
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "The webhook endpoint could not be created."));
    } finally {
      setSaving(false);
    }
  }

  async function changeEndpoint(endpoint: Endpoint, status: "ACTIVE" | "SUSPENDED" | "DELETED") {
    if (status === "DELETED" && !window.confirm(`Delete the endpoint “${endpoint.name}”? This cannot be undone.`)) return;
    setError("");
    setMessage("");
    try {
      await apiData<Endpoint>(`/api/v1/webhooks/endpoints/${encodeURIComponent(endpoint.id)}/status?environmentId=${encodeURIComponent(environmentId)}`, {
        method: "PATCH",
        body: JSON.stringify({ status }),
      });
      setMessage(`${endpoint.name} is now ${status.toLowerCase()}.`);
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Endpoint status could not be updated."));
    }
  }

  async function saveEndpoint(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedEndpoint) return;
    setSaving(true);
    setError("");
    setMessage("");
    try {
      const updated = await apiData<Endpoint>(
        `/api/v1/webhooks/endpoints/${encodeURIComponent(selectedEndpoint.id)}?environmentId=${encodeURIComponent(environmentId)}`,
        {
          method: "PATCH",
          body: JSON.stringify({ name: editName.trim(), url: editUrl.trim() }),
        },
      );
      setMessage(`${updated.name} configuration saved.`);
      setEditingEndpointId("");
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Endpoint configuration could not be saved."));
    } finally {
      setSaving(false);
    }
  }

  async function rotateSecret(endpoint: Endpoint) {
    if (!window.confirm(`Rotate the signing secret for ${endpoint.name}? The current secret will stop working immediately.`)) return;
    setError("");
    setMessage("");
    setCreated(null);
    setCopied(false);
    setSaving(true);
    try {
      const result = await apiData<CreatedEndpoint>(
        `/api/v1/webhooks/endpoints/${encodeURIComponent(endpoint.id)}/rotate-secret?environmentId=${encodeURIComponent(environmentId)}`,
        { method: "POST" },
      );
      if (!result?.signingSecret) throw new Error("The webhook API did not return the one-time signing secret.");
      setCreated(result);
      setDialogMode("secret");
      setMessage(`Signing secret rotated for ${endpoint.name}.`);
    } catch (requestError) {
      setError(getUserMessage(requestError, "The signing secret could not be rotated."));
    } finally {
      setSaving(false);
    }
  }

  async function createSubscription(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!projectId || !environmentId || !subscriptionEndpointId || !eventType) return;
    setSaving(true);
    setError("");
    setMessage("");
    try {
      const createdSubscription = await apiData<Subscription>("/api/v1/events/subscriptions", {
        method: "POST",
        body: JSON.stringify({
          projectId,
          environmentId,
          endpointId: subscriptionEndpointId,
          eventType,
          filters: {},
        }),
      });
      if (!createdSubscription?.id) throw new Error("The events API did not return the created subscription.");
      setMessage(`Subscribed ${createdSubscription.endpointId} to ${createdSubscription.eventType}.`);
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "The subscription could not be created."));
    } finally {
      setSaving(false);
    }
  }

  async function changeSubscription(subscription: Subscription, status: "ACTIVE" | "SUSPENDED" | "CANCELLED") {
    if (status === "CANCELLED"
      && !window.confirm(`Cancel the ${subscription.eventType} subscription? No new deliveries will be sent.`)) return;
    setError("");
    setMessage("");
    try {
      await apiData<Subscription>(
        `/api/v1/events/subscriptions/${encodeURIComponent(subscription.id)}/status?environmentId=${encodeURIComponent(environmentId)}`,
        { method: "PATCH", body: JSON.stringify({ status }) },
      );
      setMessage(`${subscription.eventType} subscription is now ${status.toLowerCase()}.`);
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Subscription status could not be updated."));
    }
  }

  async function replayDelivery(delivery: Delivery) {
    setError("");
    setMessage("");
    setReplayingDeliveryId(delivery.id);
    try {
      const attempt = await apiData<Delivery>(
        `/api/v1/events/deliveries/${encodeURIComponent(delivery.id)}/replay?environmentId=${encodeURIComponent(delivery.environmentId)}`,
        { method: "POST" },
      );
      setMessage(`Replay recorded as attempt ${attempt.attempt} (${attempt.status.toLowerCase()}).`);
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "The delivery could not be replayed."));
    } finally {
      setReplayingDeliveryId("");
    }
  }

  async function exportDeliveryHistory() {
    if (!projectId || !environmentId || !canRead) return;
    setExportingDeliveries(true);
    setDeliveryExportTruncated(false);
    setError("");
    try {
      const blob = await apiBlob(
        `/api/v1/exports/webhook-deliveries?projectId=${encodeURIComponent(projectId)}&environmentId=${encodeURIComponent(environmentId)}`,
        {
          onResponse: (response) => setDeliveryExportTruncated(response.headers.get("X-Export-Truncated") === "true"),
        },
      );
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "pesaguard-webhook-deliveries.csv";
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Delivery history could not be exported."));
    } finally {
      setExportingDeliveries(false);
    }
  }

  async function copySecret() {
    if (!created?.signingSecret) return;
    try {
      await copyTextToClipboard(created.signingSecret);
      setCopied(true);
    } catch (copyError) {
      setError(getUserMessage(copyError, "Could not copy the signing secret."));
    }
  }

  function endpointNameFor(id: string) {
    const endpoint = endpoints.find((item) => item.id === id);
    return endpoint?.name ?? `${id.slice(0, 8)}…`;
  }

  return (
    <>
      <PageHeader
        eyebrow="INTEGRATIONS"
        title="Webhooks"
        description="Connect event streams to your services and inspect delivery outcomes."
        action={
          <div className="usage-header-actions">
            <button className="button button--secondary" type="button" disabled={loading || !canRead} onClick={() => setRefreshKey((key) => key + 1)}>
              <RefreshCw size={14} />Refresh
            </button>
            <button className="button button--primary" type="button" disabled={loading || !canRead || !projectId || !environmentId || exportingDeliveries} onClick={() => void exportDeliveryHistory()}>
              <Download size={14} />{exportingDeliveries ? "Preparing CSV…" : "Export delivery history"}
            </button>
          </div>
        }
      />

      {!isAuthenticated && <p className="workflow-error" role="alert">Sign in to manage webhook endpoints.</p>}
      {deliveryExportTruncated && <p className="workflow-hint" role="status">The export reached the 5,000-row limit. Select a different project and export again to narrow the result set.</p>}
      {isAuthenticated && !canRead && !canCreate && (
        <p className="workflow-error" role="alert">You do not have permission to manage webhook endpoints.</p>
      )}

      {isAuthenticated && (canRead || canCreate) && (
        <>
          <div className="webhooks-toolbar">
            <label>
              <span>Project</span>
              <select
                value={projectId}
                onChange={(event) => {
                  const project = projects.find((item) => item.id === event.target.value);
                  if (!project) return;
                  setProjectId(project.id);
                  setEnvironmentId("");
                  setDeliveryPage(0);
                  setEditingEndpointId("");
                  setActiveProject(project);
                }}
                disabled={loading || saving || projects.length === 0}
              >
                <option value="">Select a project</option>
                {projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
              </select>
            </label>
            <label>
              <span>Environment</span>
              <select
                value={environmentId}
                onChange={(event) => {
                  const project = projects.find((item) => item.id === projectId);
                  const environment = environments.find((item) => item.id === event.target.value);
                  if (!project || !environment) return;
                  setEnvironmentId(environment.id);
                  setDeliveryPage(0);
                  setActiveEnvironment(project, environment);
                }}
                disabled={!projectId || saving || environments.length === 0}
              >
                <option value="">Select an environment</option>
                {environments.map((environment) => (
                  <option key={environment.id} value={environment.id}>
                    {environment.name} ({environment.type})
                  </option>
                ))}
              </select>
            </label>
            {canCreate && (
              <button
                className="button button--primary"
                type="button"
                disabled={!projectId || !environmentId || saving}
                onClick={() => {
                  setError("");
                  setEndpointName("");
                  setUrl("");
                  setDialogMode("create");
                }}
              >
                <Plus size={15} />Add endpoint
              </button>
            )}
          </div>

          {error && <p className="workflow-error" role="alert">{error}</p>}
          {message && <p className="workflow-success" role="status">{message}</p>}

          {canRead && projectId && environmentId && (
            <>
              <section className="webhooks-summary-grid" aria-label="Webhook activity summary">
                <article className="webhooks-summary-card">
                  <span className="webhooks-summary-icon"><Webhook size={17} /></span>
                  <span><small>Active endpoints</small><strong>{activeEndpoints.length}</strong></span>
                </article>
                <article className="webhooks-summary-card">
                  <span className="webhooks-summary-icon"><Zap size={17} /></span>
                  <span><small>Active subscriptions</small><strong>{activeSubscriptions}</strong></span>
                </article>
                <article className="webhooks-summary-card">
                  <span className="webhooks-summary-icon webhooks-summary-icon--warn"><AlertCircle size={17} /></span>
                  <span><small>Failed attempts</small><strong>{failedDeliveries}</strong><em>of latest {deliveries.length}</em></span>
                </article>
                <article className="webhooks-summary-card">
                  <span className="webhooks-summary-icon"><Activity size={17} /></span>
                  <span><small>Total attempts</small><strong>{deliveryTotal}</strong></span>
                </article>
              </section>

              <div className="webhooks-workspace">
                <section className="panel webhooks-panel webhooks-endpoint-panel">
                  <div className="webhooks-section-heading">
                    <div><span className="webhooks-eyebrow">DESTINATIONS</span><h2>Endpoints</h2></div>
                    <span className="webhooks-count">{endpoints.length}</span>
                  </div>
                  {loading && endpoints.length === 0 ? (
                    <p className="workflow-hint" role="status">Loading endpoints…</p>
                  ) : endpoints.length === 0 ? (
                    <div className="webhooks-empty">
                      <Webhook size={20} />
                      <strong>No endpoints yet</strong>
                      <span>Register an HTTPS destination to start receiving events.</span>
                      {canCreate && <button className="text-button" type="button" onClick={() => setDialogMode("create")}><Plus size={14} />Add endpoint</button>}
                    </div>
                  ) : (
                    <div className="webhooks-endpoint-list">
                      {endpoints.map((endpoint) => {
                        const endpointSubscriptions = subscriptions.filter((item) => item.endpointId === endpoint.id);
                        return (
                          <button
                            className={`webhooks-endpoint-card${endpoint.id === selectedEndpointId ? " webhooks-endpoint-card--selected" : ""}`}
                            type="button"
                            key={endpoint.id}
                            aria-pressed={endpoint.id === selectedEndpointId}
                            onClick={() => {
                              setSelectedEndpointId(endpoint.id);
                              setEditingEndpointId("");
                            }}
                          >
                            <span className={`webhooks-endpoint-glyph webhooks-tone--${statusTone(endpoint.status)}`}><Webhook size={16} /></span>
                            <span className="webhooks-endpoint-copy">
                              <strong>{endpoint.name}</strong>
                              <code title={endpoint.url}>{endpoint.url}</code>
                              <small>{endpointSubscriptions.length} subscription{endpointSubscriptions.length === 1 ? "" : "s"}</small>
                            </span>
                            <span className={`webhooks-status webhooks-status--${statusTone(endpoint.status)}`}>{endpoint.status}</span>
                          </button>
                        );
                      })}
                    </div>
                  )}
                </section>

                <section className="panel webhooks-panel webhooks-detail-panel">
                  {!selectedEndpoint ? (
                    <div className="webhooks-empty webhooks-empty--detail">
                      <ShieldCheck size={22} />
                      <strong>Select an endpoint</strong>
                      <span>Endpoint configuration, signing-secret controls, and subscriptions will appear here.</span>
                    </div>
                  ) : (
                    <>
                      <div className="webhooks-detail-heading">
                        <div className="webhooks-detail-icon"><Webhook size={19} /></div>
                        <div className="webhooks-detail-title">
                          <span className="webhooks-eyebrow">ENDPOINT DETAILS</span>
                          {editingEndpointId === selectedEndpoint.id ? (
                            <h2>Update endpoint</h2>
                          ) : (
                            <h2>{selectedEndpoint.name}</h2>
                          )}
                          <code title={selectedEndpoint.url}>{selectedEndpoint.url}</code>
                        </div>
                        <span className={`webhooks-status webhooks-status--${statusTone(selectedEndpoint.status)}`}>{selectedEndpoint.status}</span>
                      </div>

                      {editingEndpointId === selectedEndpoint.id ? (
                        <ValidatedForm className="webhooks-edit-form" onSubmit={(event) => void saveEndpoint(event)}>
                          <label>Endpoint name<input required maxLength={120} value={editName} onChange={(event) => setEditName(event.target.value)} /></label>
                          <label>HTTPS destination<input type="url" required maxLength={2048} value={editUrl} onChange={(event) => setEditUrl(event.target.value)} /></label>
                          <div className="webhooks-actions">
                            <button className="button button--primary" type="submit" disabled={saving || !editName.trim() || !editUrl.trim()}>{saving ? "Saving…" : "Save changes"}</button>
                            <button className="button button--secondary" type="button" disabled={saving} onClick={() => setEditingEndpointId("")}>Cancel</button>
                          </div>
                        </ValidatedForm>
                      ) : (
                        <>
                          <div className="webhooks-detail-facts">
                            <span><small>Created</small><strong>{formatTimestamp(selectedEndpoint.createdAt)}</strong></span>
                            <span><small>Subscriptions</small><strong>{subscriptions.filter((item) => item.endpointId === selectedEndpoint.id).length}</strong></span>
                            <span><small>Endpoint ID</small><code title={selectedEndpoint.id}>{selectedEndpoint.id}</code></span>
                          </div>
                          <p className="webhooks-security-note"><ShieldCheck size={15} />Signing secrets are only returned when created or rotated.</p>
                          <div className="webhooks-actions">
                            {canUpdate && selectedEndpoint.status !== "DELETED" && (
                              <>
                                <button
                                  className="button button--secondary"
                                  type="button"
                                  disabled={saving}
                                  onClick={() => {
                                    setEditingEndpointId(selectedEndpoint.id);
                                    setEditName(selectedEndpoint.name);
                                    setEditUrl(selectedEndpoint.url);
                                  }}
                                >
                                  <Pencil size={14} />Edit
                                </button>
                                <button className="button button--secondary" type="button" disabled={saving} onClick={() => void rotateSecret(selectedEndpoint)}>
                                  <RotateCw size={14} />Rotate secret
                                </button>
                                <button
                                  className="button button--secondary"
                                  type="button"
                                  disabled={saving}
                                  onClick={() => void changeEndpoint(selectedEndpoint, selectedEndpoint.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE")}
                                >
                                  {selectedEndpoint.status === "ACTIVE" ? <Pause size={14} /> : <Play size={14} />}
                                  {selectedEndpoint.status === "ACTIVE" ? "Suspend" : "Resume"}
                                </button>
                              </>
                            )}
                            {canDelete && selectedEndpoint.status !== "DELETED" && (
                              <button className="button button--danger" type="button" disabled={saving} onClick={() => void changeEndpoint(selectedEndpoint, "DELETED")}>
                                <Trash2 size={14} />Delete
                              </button>
                            )}
                          </div>
                        </>
                      )}
                    </>
                  )}
                </section>
              </div>

              <section className="panel webhooks-panel webhooks-subscriptions-panel">
                <div className="webhooks-section-heading">
                  <div><span className="webhooks-eyebrow">EVENT ROUTING</span><h2>Subscriptions</h2><p>Choose which platform events are delivered to active endpoints.</p></div>
                  <span className="webhooks-count">{subscriptions.length}</span>
                </div>
                {canCreate && (
                  <ValidatedForm className="webhooks-subscribe-form" onSubmit={(event) => void createSubscription(event)}>
                    <label>
                      <span>Endpoint</span>
                      <select required value={subscriptionEndpointId} onChange={(event) => setSubscriptionEndpointId(event.target.value)} disabled={saving || activeEndpoints.length === 0}>
                        <option value="">Select an active endpoint</option>
                        {activeEndpoints.map((endpoint) => <option key={endpoint.id} value={endpoint.id}>{endpoint.name}</option>)}
                      </select>
                    </label>
                    <label>
                      <span>Event type</span>
                      <select required value={eventType} onChange={(event) => setEventType(event.target.value)} disabled={saving || catalog.every((type) => type.lifecycle !== "ACTIVE")}>
                        <option value="">Select an event</option>
                        {catalog.filter((type) => type.lifecycle === "ACTIVE").map((type) => (
                          <option key={type.name} value={type.name}>{type.name} · v{type.version}</option>
                        ))}
                      </select>
                    </label>
                    <button className="button button--primary" type="submit" disabled={saving || !projectId || !subscriptionEndpointId || !eventType || activeEndpoints.length === 0}>
                      <Plus size={14} />{saving ? "Adding…" : "Add subscription"}
                    </button>
                  </ValidatedForm>
                )}
                {loading && subscriptions.length === 0 ? (
                  <p className="workflow-hint" role="status">Loading subscriptions…</p>
                ) : subscriptions.length === 0 ? (
                  <div className="webhooks-empty webhooks-empty--compact">
                    <Zap size={18} /><strong>No subscriptions configured</strong>
                    <span>Connect an active endpoint to an event type above.</span>
                  </div>
                ) : (
                  <div className="webhooks-subscription-list">
                    {subscriptions.map((subscription) => (
                      <article className="webhooks-subscription-row" key={subscription.id}>
                        <span className="webhooks-subscription-icon"><Zap size={15} /></span>
                        <div className="webhooks-subscription-main">
                          <strong>{subscription.eventType}</strong>
                          <span>{endpointNameFor(subscription.endpointId)} · v{subscription.eventVersion}</span>
                        </div>
                        <span className={`webhooks-status webhooks-status--${statusTone(subscription.status)}`}>{subscription.status}</span>
                        {canUpdate && subscription.status !== "CANCELLED" && (
                          <div className="webhooks-subscription-actions">
                            <button
                              className="text-button"
                              type="button"
                              disabled={saving}
                              onClick={() => void changeSubscription(subscription, subscription.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE")}
                            >
                              {subscription.status === "ACTIVE" ? <Pause size={13} /> : <Play size={13} />}
                              {subscription.status === "ACTIVE" ? "Suspend" : "Resume"}
                            </button>
                            <button className="text-button text-button--danger" type="button" disabled={saving} onClick={() => void changeSubscription(subscription, "CANCELLED")}>Cancel</button>
                          </div>
                        )}
                      </article>
                    ))}
                  </div>
                )}
              </section>

              <section className="panel webhooks-panel webhooks-deliveries-panel">
                <div className="webhooks-section-heading webhooks-deliveries-heading">
                  <div><span className="webhooks-eyebrow">OBSERVABILITY</span><h2>Delivery attempts</h2><p>System-recorded outcomes. Failed attempts can be replayed when the endpoint and subscription are active.</p></div>
                  <span className="webhooks-count">{deliveryTotal}</span>
                </div>
                <div className="webhooks-delivery-filters">
                  <label>
                    <span>Endpoint</span>
                    <select value={deliveryEndpointFilter} onChange={(event) => setDeliveryEndpointFilter(event.target.value)}>
                      <option value="ALL">All endpoints</option>
                      {endpoints.map((endpoint) => <option key={endpoint.id} value={endpoint.id}>{endpoint.name}</option>)}
                    </select>
                  </label>
                  <label>
                    <span>Outcome</span>
                    <select value={deliveryStatusFilter} onChange={(event) => setDeliveryStatusFilter(event.target.value)}>
                      <option value="ALL">All outcomes</option>
                      <option value="DELIVERED">Delivered</option>
                      <option value="RETRY_SCHEDULED">Retry scheduled</option>
                      <option value="FAILED">Failed</option>
                      <option value="DEAD_LETTERED">Dead-lettered</option>
                      <option value="PENDING">Pending</option>
                      <option value="IN_FLIGHT">In flight</option>
                      <option value="SUPPRESSED">Suppressed</option>
                    </select>
                  </label>
                  <span className="webhooks-filter-note">Filters apply to the current page of 50 attempts.</span>
                </div>
                {loading && deliveries.length === 0 ? (
                  <p className="workflow-hint" role="status">Loading delivery history…</p>
                ) : filteredDeliveries.length === 0 ? (
                  <div className="webhooks-empty webhooks-empty--compact">
                    <Send size={18} />
                    <strong>{deliveries.length ? "No attempts match these filters" : "No delivery attempts yet"}</strong>
                    <span>{deliveries.length ? "Change the filters to see other results on this page." : "Delivery results will appear here when subscribed events are emitted."}</span>
                  </div>
                ) : (
                  <div className="webhooks-delivery-list">
                    {filteredDeliveries.map((delivery) => (
                      <article className="webhooks-delivery-card" key={delivery.id}>
                        <div className="webhooks-delivery-topline">
                          <div className="webhooks-delivery-event">
                            <span className="webhooks-delivery-icon"><Send size={15} /></span>
                            <span><strong>{delivery.eventType}</strong><small>{formatTimestamp(delivery.createdAt)}</small></span>
                          </div>
                          <span className={`webhooks-status webhooks-status--${statusTone(delivery.status)}`}>{delivery.status.replaceAll("_", " ")}</span>
                        </div>
                        <div className="webhooks-delivery-meta">
                          <span><small>Endpoint</small><strong title={delivery.endpointId}>{endpointNameFor(delivery.endpointId)}</strong></span>
                          <span><small>Attempt</small><strong>#{delivery.attempt}</strong></span>
                          <span><small>HTTP</small><strong>{delivery.responseCode ?? "—"}</strong></span>
                          <span><small>Latency</small><strong>{delivery.latencyMs == null ? "—" : `${delivery.latencyMs} ms`}</strong></span>
                          <span><small>Next retry</small><strong>{formatTimestamp(delivery.nextAttemptAt)}</strong></span>
                        </div>
                        {delivery.errorCode && <p className="webhooks-delivery-error"><AlertCircle size={13} />{delivery.errorCode}</p>}
                        {(delivery.status === "FAILED" || delivery.status === "DEAD_LETTERED") && canUpdate && (
                          <div className="webhooks-delivery-actions">
                            <button
                              className="button button--secondary"
                              type="button"
                              disabled={replayingDeliveryId === delivery.id}
                              onClick={() => void replayDelivery(delivery)}
                            >
                              <RotateCw size={14} />{replayingDeliveryId === delivery.id ? "Replaying…" : "Replay delivery"}
                            </button>
                          </div>
                        )}
                      </article>
                    ))}
                  </div>
                )}
                <div className="webhooks-pagination">
                  <span>Page {deliveryPage + 1} · {deliveries.length} of {deliveryTotal} attempts</span>
                  <div>
                    <button className="button button--secondary" type="button" disabled={loading || deliveryPage === 0} onClick={() => setDeliveryPage((page) => Math.max(0, page - 1))}>Previous</button>
                    <button className="button button--secondary" type="button" disabled={loading || (deliveryPage + 1) * 50 >= deliveryTotal} onClick={() => setDeliveryPage((page) => page + 1)}>Next</button>
                  </div>
                </div>
              </section>
            </>
          )}
        </>
      )}

      {dialogMode && (
        <div
          className="webhooks-dialog-backdrop"
          role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !saving) closeDialog();
          }}
        >
          <section className="webhooks-dialog" role="dialog" aria-modal="true" aria-labelledby="webhooks-dialog-title">
            <div className="webhooks-dialog-heading">
              <div>
                <span className="webhooks-eyebrow">{dialogMode === "secret" ? "SAVE THIS CREDENTIAL" : "NEW DESTINATION"}</span>
                <h2 id="webhooks-dialog-title">{dialogMode === "secret" ? "Signing secret" : "Add webhook endpoint"}</h2>
                <p>{dialogMode === "secret"
                  ? "This secret is shown once. Copy it now and store it in your secret manager."
                  : "Register an HTTPS endpoint to receive signed event notifications."}</p>
              </div>
              <button className="webhooks-dialog-close" type="button" aria-label="Close dialog" disabled={saving} onClick={closeDialog}><X size={18} /></button>
            </div>

            {dialogMode === "create" ? (
              <ValidatedForm className="webhooks-create-form" onSubmit={(event) => void createEndpoint(event)}>
                <label>
                  <span>Endpoint name</span>
                  <input required maxLength={120} value={endpointName} onChange={(event) => setEndpointName(event.target.value)} placeholder="Production notifications" disabled={saving} />
                </label>
                <label>
                  <span>HTTPS destination URL</span>
                  <input type="url" required maxLength={2048} value={url} onChange={(event) => setUrl(event.target.value)} placeholder="https://example.com/webhooks/pesaguard" disabled={saving} />
                  <small>Public HTTPS targets only. Redirects are not followed during delivery.</small>
                </label>
                <div className="webhooks-dialog-security"><ShieldCheck size={16} /><span>Requests include a signature header. Keep your signing secret private.</span></div>
                <div className="webhooks-dialog-actions">
                  <button className="button button--secondary" type="button" disabled={saving} onClick={closeDialog}>Cancel</button>
                  <button className="button button--primary" type="submit" disabled={saving || !projectId}>{saving ? "Registering…" : "Register endpoint"}</button>
                </div>
              </ValidatedForm>
            ) : (
              <div className="webhooks-secret-content">
                <div className="webhooks-secret-info">
                  <KeyRound size={17} />
                  <span><strong>{created?.endpoint.name ?? "Webhook endpoint"}</strong><small>{created?.endpoint.url ?? "Signing secret rotated"}</small></span>
                </div>
                <code className="webhooks-secret-value">{created?.signingSecret}</code>
                <p>This value will not be available after closing this dialog.</p>
                <div className="webhooks-dialog-actions">
                  <button className="button button--secondary" type="button" onClick={() => void copySecret()}>{copied ? <Check size={14} /> : <Copy size={14} />}{copied ? "Copied" : "Copy secret"}</button>
                  <button className="button button--primary" type="button" onClick={closeDialog}>I’ve saved it</button>
                </div>
              </div>
            )}
          </section>
        </div>
      )}
    </>
  );
}
