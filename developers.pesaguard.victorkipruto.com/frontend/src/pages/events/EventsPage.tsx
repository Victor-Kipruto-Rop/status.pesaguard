import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useEffect, useState, type FormEvent } from "react";
import { Activity, RefreshCw } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";
import {
  readActiveEnvironmentContext,
  readActiveEnvironmentId,
  readActiveProjectId,
  setActiveEnvironment,
  setActiveProject,
} from "../../lib/activeEnvironment";

type Project = { id: string; name: string };
type Environment = { id: string; projectId: string; name: string; type: string; status: string; baseUrl?: string };
type EventType = { name: string; description: string; category: string; version: number; lifecycle: string };
type EmittedEvent = {
  id: string;
  eventId: string;
  eventType: string;
  version: number;
  projectId: string | null;
  correlationId: string | null;
  traceId: string | null;
  status: string;
  attemptCount: number;
  createdAt: string;
  publishedAt: string | null;
};
type Subscription = { id: string; projectId: string; environmentId: string; endpointId: string; eventType: string; status: string; createdAt: string };
type PageResponse<T> = { items: T[]; page: number; size: number; totalElements: number; totalPages: number };
type Endpoint = { id: string; name: string; status: string };

export function EventsPage() {
  const { isAuthenticated } = useAuth();
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState("");
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [environmentId, setEnvironmentId] = useState("");
  const [catalog, setCatalog] = useState<EventType[]>([]);
  const [events, setEvents] = useState<EmittedEvent[]>([]);
  const [eventPage, setEventPage] = useState(0);
  const [eventTotal, setEventTotal] = useState(0);
  const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [subscriptionPage, setSubscriptionPage] = useState(0);
  const [subscriptionTotal, setSubscriptionTotal] = useState(0);
  const [endpoints, setEndpoints] = useState<Endpoint[]>([]);
  const [eventType, setEventType] = useState("");
  const [endpointId, setEndpointId] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    if (!isAuthenticated) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError("");
    void Promise.all([
      apiData<PageResponse<Project>>("/api/v1/projects?page=0&size=100", { signal: controller.signal }),
      apiData<EventType[]>("/api/v1/events/catalog", { signal: controller.signal }),
    ]).then(([projectResult, eventTypes]) => {
      if (!Array.isArray(projectResult.items) || !Array.isArray(eventTypes)) {
        throw new Error("The events API returned an invalid response.");
      }
      setProjects(projectResult.items);
      setCatalog(eventTypes);
      const selected = projectResult.items.find((project) => project.id === readActiveProjectId())
        ?? projectResult.items[0];
      setProjectId((current) => projectResult.items.some((project) => project.id === current)
        ? current : selected?.id ?? "");
      if (selected) setActiveProject(selected);
      setEventType((current) => current || eventTypes[0]?.name || "");
    }).catch((requestError: unknown) => {
      if (!controller.signal.aborted) setError(getUserMessage(requestError, "Event data could not be loaded."));
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [isAuthenticated, refreshKey]);

  useEffect(() => {
    if (!isAuthenticated || !projectId) {
      setEnvironments([]);
      setEnvironmentId("");
      setEvents([]);
      setSubscriptions([]);
      setEventTotal(0);
      setSubscriptionTotal(0);
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
    if (!isAuthenticated || !projectId || !environmentId) {
      setEvents([]);
      setSubscriptions([]);
      return;
    }
    const controller = new AbortController();
    void Promise.all([
      apiData<PageResponse<EmittedEvent>>(
        `/api/v1/events?projectId=${encodeURIComponent(projectId)}&environmentId=${encodeURIComponent(environmentId)}&page=${eventPage}&size=50`,
        { signal: controller.signal },
      ),
      apiData<PageResponse<Subscription>>(
        `/api/v1/events/subscriptions?projectId=${encodeURIComponent(projectId)}&environmentId=${encodeURIComponent(environmentId)}&page=${subscriptionPage}&size=50`,
        { signal: controller.signal },
      ),
    ]).then(([eventRecords, subscriptionRecords]) => {
      if (!Array.isArray(eventRecords.items) || !Array.isArray(subscriptionRecords.items)) {
        throw new Error("The events API returned an invalid environment response.");
      }
      setEvents(eventRecords.items);
      setEventTotal(eventRecords.totalElements);
      setSubscriptions(subscriptionRecords.items);
      setSubscriptionTotal(subscriptionRecords.totalElements);
    }).catch((requestError: unknown) => {
      if (!controller.signal.aborted) {
        setEvents([]);
        setSubscriptions([]);
        setError(getUserMessage(requestError, "Environment event data could not be loaded."));
      }
    });
    return () => controller.abort();
  }, [isAuthenticated, projectId, environmentId, eventPage, subscriptionPage, refreshKey]);

  useEffect(() => {
    if (!isAuthenticated || !projectId || !environmentId) {
      setEndpoints([]);
      setEndpointId("");
      return;
    }
    const controller = new AbortController();
    void apiData<Endpoint[]>(
      `/api/v1/webhooks/endpoints?projectId=${encodeURIComponent(projectId)}&environmentId=${encodeURIComponent(environmentId)}`,
      { signal: controller.signal },
    )
      .then((result) => {
        setEndpoints(result.filter((endpoint) => endpoint.status === "ACTIVE"));
        setEndpointId((current) => result.some((endpoint) => endpoint.id === current && endpoint.status === "ACTIVE")
          ? current : result.find((endpoint) => endpoint.status === "ACTIVE")?.id ?? "");
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setError(getUserMessage(requestError, "Webhook endpoints could not be loaded."));
      });
    return () => controller.abort();
  }, [isAuthenticated, projectId, environmentId, refreshKey]);

  async function createSubscription(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError("");
    setMessage("");
    try {
      const created = await apiData<Subscription>("/api/v1/events/subscriptions", {
        method: "POST",
        body: JSON.stringify({ projectId, environmentId, endpointId, eventType, filters: {} }),
      });
      if (!created?.id) throw new Error("The events API did not return the created subscription.");
      setMessage(`Subscribed ${created.endpointId} to ${created.eventType}.`);
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "The event subscription could not be created."));
    } finally {
      setSaving(false);
    }
  }

  async function changeSubscription(subscription: Subscription, status: "ACTIVE" | "SUSPENDED" | "CANCELLED") {
    setError("");
    setMessage("");
    try {
      await apiData<Subscription>(`/api/v1/events/subscriptions/${encodeURIComponent(subscription.id)}/status?environmentId=${encodeURIComponent(environmentId)}`, {
        method: "PATCH",
        body: JSON.stringify({ status }),
      });
      setMessage(`Subscription to ${subscription.eventType} is now ${status.toLowerCase()}.`);
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Subscription status could not be updated."));
    }
  }

  return (
    <>
      <PageHeader eyebrow="EVENTS" title="Event delivery" description="Inspect the registered event contract and real events committed to the platform outbox." action={<button className="button button--secondary" type="button" disabled={loading} onClick={() => setRefreshKey((key) => key + 1)}><RefreshCw size={14} />Refresh</button>} />
      {!isAuthenticated && <p className="workflow-error" role="alert">Sign in to inspect organization event data.</p>}
      {error && <p className="workflow-error" role="alert">{error}</p>}
      {message && <p className="workflow-success" role="status">{message}</p>}
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Event catalog</h2><p>Active and deprecated event contracts available for subscriptions.</p></div><span className="table-tag">{catalog.length} TYPES</span></div>
        {loading ? <p className="workflow-hint" role="status">Loading event catalog…</p> : catalog.length === 0 ? <div className="organization-live-empty"><Activity size={18} />No event types were returned by the API.</div> :
          <div className="table-scroll"><table className="data-table"><thead><tr><th>EVENT TYPE</th><th>DESCRIPTION</th><th>CATEGORY</th><th>VERSION</th><th>LIFECYCLE</th></tr></thead><tbody>{catalog.map((type) => <tr key={type.name}><td><code>{type.name}</code></td><td>{type.description}</td><td>{type.category}</td><td>v{type.version}</td><td>{type.lifecycle}</td></tr>)}</tbody></table></div>}
      </section>
      <section className="panel">
        <div className="panel-heading"><div><h2>Subscribe a webhook endpoint</h2><p>Subscriptions are scoped to an organization project and a registered endpoint.</p></div></div>
        <ValidatedForm className="workflow-form" onSubmit={createSubscription}>
          <label>Project<select required value={projectId} onChange={(event) => {
            const project = projects.find((item) => item.id === event.target.value);
            if (!project) return;
            setProjectId(project.id);
            setEnvironmentId("");
            setEventPage(0);
            setSubscriptionPage(0);
            setActiveProject(project);
          }} disabled={!isAuthenticated || saving || projects.length === 0}><option value="">Select a project</option>{projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
          <label>Environment<select required value={environmentId} onChange={(event) => {
            const project = projects.find((item) => item.id === projectId);
            const environment = environments.find((item) => item.id === event.target.value);
            if (!project || !environment) return;
            setEnvironmentId(environment.id);
            setEventPage(0);
            setSubscriptionPage(0);
            setActiveEnvironment(project, environment);
          }} disabled={!projectId || saving || environments.length === 0}><option value="">Select an environment</option>{environments.map((environment) => <option key={environment.id} value={environment.id}>{environment.name} ({environment.type})</option>)}</select></label>
          <label>Webhook endpoint<select required value={endpointId} onChange={(event) => setEndpointId(event.target.value)} disabled={!projectId || saving || endpoints.length === 0}><option value="">Select an active endpoint</option>{endpoints.map((endpoint) => <option key={endpoint.id} value={endpoint.id}>{endpoint.name}</option>)}</select></label>
          <label>Event type<select required value={eventType} onChange={(event) => setEventType(event.target.value)} disabled={saving || catalog.length === 0}>{catalog.map((type) => <option key={type.name} value={type.name}>{type.name}</option>)}</select></label>
          <div className="workflow-form-actions"><button className="button button--primary" type="submit" disabled={!isAuthenticated || saving || !projectId || !environmentId || !endpointId || !eventType}>{saving ? "Saving…" : "Create subscription"}</button></div>
        </ValidatedForm>
      </section>
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Committed platform events</h2><p>Latest outbox records. Payload bodies are intentionally not exposed in this list.</p></div><span className="table-tag">{eventTotal} RECORDS</span></div>
        {loading ? <p className="workflow-hint" role="status">Loading event records…</p> : events.length === 0 ? <div className="organization-live-empty"><Activity size={18} />No events are available for this organization.</div> :
          <div className="table-scroll"><table className="data-table"><thead><tr><th>CREATED</th><th>TYPE</th><th>PROJECT</th><th>OUTBOX STATUS</th><th>ATTEMPTS</th><th>TRACE ID</th></tr></thead><tbody>{events.map((item) => <tr key={item.id}><td>{new Date(item.createdAt).toLocaleString()}</td><td><code>{item.eventType}</code></td><td>{item.projectId ?? "Organization"}</td><td>{item.status}</td><td>{item.attemptCount}</td><td>{item.traceId ? <code>{item.traceId}</code> : "—"}</td></tr>)}</tbody></table></div>}
        {eventTotal > 50 && <div className="events-pagination"><span>Page {eventPage + 1} of {Math.max(1, Math.ceil(eventTotal / 50))}</span><div><button className="button button--secondary" type="button" disabled={loading || eventPage === 0} onClick={() => setEventPage((current) => Math.max(0, current - 1))}>Previous</button><button className="button button--secondary" type="button" disabled={loading || (eventPage + 1) * 50 >= eventTotal} onClick={() => setEventPage((current) => current + 1)}>Next</button></div></div>}
      </section>
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Subscriptions</h2><p>Persisted event subscriptions for this organization.</p></div><span className="table-tag">{subscriptionTotal} RECORDS</span></div>
        {subscriptions.length === 0 ? <div className="organization-live-empty">No event subscriptions are configured.</div> :
          <div className="table-scroll"><table className="data-table"><thead><tr><th>EVENT</th><th>PROJECT</th><th>ENDPOINT ID</th><th>STATUS</th><th>ACTIONS</th></tr></thead><tbody>{subscriptions.map((subscription) => <tr key={subscription.id}><td><code>{subscription.eventType}</code></td><td><code>{subscription.projectId}</code></td><td><code>{subscription.endpointId}</code></td><td>{subscription.status}</td><td>{subscription.status !== "CANCELLED" && <button className="text-button" type="button" onClick={() => void changeSubscription(subscription, subscription.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE")}>{subscription.status === "ACTIVE" ? "Suspend" : "Resume"}</button>}{subscription.status !== "CANCELLED" && <button className="text-button text-button--danger" type="button" onClick={() => void changeSubscription(subscription, "CANCELLED")}>Cancel</button>}</td></tr>)}</tbody></table></div>}
        {subscriptionTotal > 50 && <div className="events-pagination"><span>Page {subscriptionPage + 1} of {Math.max(1, Math.ceil(subscriptionTotal / 50))}</span><div><button className="button button--secondary" type="button" disabled={loading || subscriptionPage === 0} onClick={() => setSubscriptionPage((current) => Math.max(0, current - 1))}>Previous</button><button className="button button--secondary" type="button" disabled={loading || (subscriptionPage + 1) * 50 >= subscriptionTotal} onClick={() => setSubscriptionPage((current) => current + 1)}>Next</button></div></div>}
      </section>
    </>
  );
}
