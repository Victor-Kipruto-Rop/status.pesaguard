import { useEffect, useMemo, useState } from "react";
import {
  Activity,
  ArrowRight,
  Check,
  ChevronDown,
  CircleAlert,
  Layers3,
  RefreshCw,
  Search,
  ShieldCheck,
} from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { getUserMessage } from "../../lib/errors";
import { apiData } from "../../lib/api";

type Scope = {
  name: string;
  description: string;
  category: string;
  version: number;
  restricted: boolean;
  apiKeyAssignable: boolean;
  deprecated: boolean;
  replacedBy: string | null;
  requiresSecurityReview: boolean;
};

type EventType = {
  name: string;
  description: string;
  category: string;
  version: number;
  schema: string;
  lifecycle: string;
};

type CatalogTab = "overview" | "scopes" | "events";
type ScopeFilter = "all" | "assignable" | "restricted" | "deprecated";

const tabs: { id: CatalogTab; label: string; description: string }[] = [
  { id: "overview", label: "Overview", description: "Registry status and discovery" },
  { id: "scopes", label: "API scopes", description: "Permissions available to credentials" },
  { id: "events", label: "Event contracts", description: "Event names, lifecycle, and schemas" },
];

function matchesQuery(query: string, values: string[]) {
  const normalized = query.trim().toLowerCase();
  return !normalized || values.some((value) => value.toLowerCase().includes(normalized));
}

function formatSchema(schema: string) {
  try {
    return JSON.stringify(JSON.parse(schema) as unknown, null, 2);
  } catch {
    return schema;
  }
}

function ScopeStatus({ scope }: { scope: Scope }) {
  const label = scope.deprecated ? "Deprecated"
    : !scope.apiKeyAssignable ? "Unavailable"
      : scope.restricted ? "Restricted" : "Assignable";
  const tone = scope.deprecated || !scope.apiKeyAssignable ? "muted"
    : scope.restricted ? "warning" : "positive";
  return <span className={`api-catalog-status api-catalog-status--${tone}`}>{scope.apiKeyAssignable && !scope.deprecated && <Check size={12} />}{label}</span>;
}

export function ApiCatalogPage() {
  const [scopes, setScopes] = useState<Scope[]>([]);
  const [eventTypes, setEventTypes] = useState<EventType[]>([]);
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("all");
  const [scopeFilter, setScopeFilter] = useState<ScopeFilter>("all");
  const [activeTab, setActiveTab] = useState<CatalogTab>("overview");
  const [expandedEvent, setExpandedEvent] = useState("");
  const [loading, setLoading] = useState(true);
  const [scopeError, setScopeError] = useState("");
  const [eventError, setEventError] = useState("");

  async function loadCatalog() {
    setLoading(true);
    const [scopeResult, eventResult] = await Promise.allSettled([
      apiData<Scope[]>("/api/v1/scopes"),
      apiData<EventType[]>("/api/v1/events/catalog"),
    ]);
    if (scopeResult.status === "fulfilled") {
      setScopes(scopeResult.value);
      setScopeError("");
    } else {
      setScopeError(getUserMessage(scopeResult.reason, "Could not load API scopes."));
      setScopes([]);
    }
    if (eventResult.status === "fulfilled") {
      setEventTypes(eventResult.value);
      setEventError("");
    } else {
      setEventError(getUserMessage(eventResult.reason, "Could not load event contracts."));
      setEventTypes([]);
    }
    setLoading(false);
  }

  useEffect(() => { void loadCatalog(); }, []);

  const categories = useMemo(() => Array.from(new Set([
    ...scopes.map((scope) => scope.category),
    ...eventTypes.map((event) => event.category),
  ])).sort((left, right) => left.localeCompare(right)), [eventTypes, scopes]);
  const visibleScopes = useMemo(
    () => scopes.filter((scope) => {
      const scopeMatches = scopeFilter === "all"
        || (scopeFilter === "assignable" && scope.apiKeyAssignable && !scope.deprecated)
        || (scopeFilter === "restricted" && scope.restricted)
        || (scopeFilter === "deprecated" && scope.deprecated);
      return scopeMatches
        && (category === "all" || scope.category === category)
        && matchesQuery(query, [scope.name, scope.description, scope.category]);
    }),
    [category, query, scopeFilter, scopes],
  );
  const visibleEventTypes = useMemo(
    () => eventTypes.filter((event) => (category === "all" || event.category === category)
      && matchesQuery(query, [event.name, event.description, event.category, event.lifecycle])),
    [category, eventTypes, query],
  );
  const assignableCount = scopes.filter((scope) => scope.apiKeyAssignable && !scope.deprecated).length;
  const restrictedCount = scopes.filter((scope) => scope.restricted).length;
  const activeEvents = eventTypes.filter((event) => event.lifecycle.toUpperCase() === "ACTIVE").length;

  function selectTab(tab: CatalogTab) {
    setActiveTab(tab);
    setCategory("all");
  }

  return (
    <>
      <PageHeader
        eyebrow="BUILD · REFERENCE"
        title="API catalog"
        description="Explore the live scope registry and event contracts. Availability and lifecycle labels are sourced from the system."
        action={<button className="button button--secondary" type="button" disabled={loading} onClick={() => void loadCatalog()}><RefreshCw size={14} className={loading ? "usage-refresh-icon" : ""} />{loading ? "Refreshing…" : "Refresh catalog"}</button>}
      />

      <section className="api-catalog-hero" aria-label="API catalog summary">
        <div className="api-catalog-hero__intro">
          <span className="api-catalog-hero__icon"><Layers3 size={21} /></span>
          <div>
            <span className="api-catalog-kicker">Developer reference</span>
            <h2>One source of truth for integration contracts</h2>
            <p>Inspect what a credential can access, what the platform emits, and the lifecycle constraints before you build.</p>
          </div>
        </div>
        <div className="api-catalog-summary">
          <div><span>Registry scopes</span><strong>{scopeError ? "—" : scopes.length}</strong><small>{scopeError ? "Unavailable" : "System-defined"}</small></div>
          <div><span>Assignable</span><strong>{scopeError ? "—" : assignableCount}</strong><small>Current API-key options</small></div>
          <div><span>Event contracts</span><strong>{eventError ? "—" : eventTypes.length}</strong><small>{eventError ? "Unavailable" : `${activeEvents} active`}</small></div>
        </div>
      </section>

      <nav className="api-catalog-tabs" role="tablist" aria-label="API catalog sections">
        {tabs.map((tab) => (
          <button key={tab.id} id={`api-catalog-tab-${tab.id}`} type="button" role="tab"
            aria-selected={activeTab === tab.id} aria-controls={`api-catalog-panel-${tab.id}`}
            tabIndex={activeTab === tab.id ? 0 : -1} className={activeTab === tab.id ? "is-active" : ""}
            onClick={() => selectTab(tab.id)}
            onKeyDown={(event) => {
              if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
              event.preventDefault();
              const currentIndex = tabs.findIndex((item) => item.id === tab.id);
              const nextIndex = event.key === "Home" ? 0
                : event.key === "End" ? tabs.length - 1
                  : (currentIndex + (event.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length;
              const nextTab = tabs[nextIndex];
              selectTab(nextTab.id);
              document.getElementById(`api-catalog-tab-${nextTab.id}`)?.focus();
            }}>
            <span>{tab.label}</span><small>{tab.description}</small>
          </button>
        ))}
      </nav>

      {activeTab !== "overview" && <div className="api-catalog-toolbar">
        <label className="api-catalog-search">
          <Search size={16} />
          <span className="visually-hidden">Search catalog</span>
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search names, descriptions, categories…" />
        </label>
        <label className="api-catalog-category">
          <span className="visually-hidden">Filter by category</span>
          <select value={category} onChange={(event) => setCategory(event.target.value)}>
            <option value="all">All categories</option>
            {categories.map((value) => <option key={value} value={value}>{value}</option>)}
          </select>
          <ChevronDown size={15} />
        </label>
      </div>}

      {scopeError && <p className="notification-alert" role="alert">Scope registry: {scopeError}</p>}
      {eventError && <p className="notification-alert" role="alert">Event catalog: {eventError}</p>}

      {activeTab === "overview" && (
        <section className="api-catalog-panel" id="api-catalog-panel-overview" role="tabpanel" aria-labelledby="api-catalog-tab-overview">
          <div className="api-catalog-panel-heading">
            <div><span className="api-catalog-kicker">Registry map</span><h2>Explore by contract type</h2><p>Choose a collection to inspect definitions, supported lifecycle, and implementation constraints.</p></div>
          </div>
          <div className="api-catalog-collection-grid">
            <button type="button" className="api-catalog-collection" onClick={() => selectTab("scopes")}>
              <span className="api-catalog-collection__icon"><ShieldCheck size={19} /></span>
              <span className="api-catalog-collection__meta"><strong>Credential scopes</strong><small>Authorization surface</small></span>
              <span className="api-catalog-collection__count">{scopeError ? "—" : scopes.length}</span>
              <p>See permission descriptions, assignment availability, review gates, and deprecation replacements.</p>
              <span className="api-catalog-collection__foot">{restrictedCount} restricted <ArrowRight size={14} /></span>
            </button>
            <button type="button" className="api-catalog-collection" onClick={() => selectTab("events")}>
              <span className="api-catalog-collection__icon api-catalog-collection__icon--event"><Activity size={19} /></span>
              <span className="api-catalog-collection__meta"><strong>Event contracts</strong><small>Webhook payload surface</small></span>
              <span className="api-catalog-collection__count">{eventError ? "—" : eventTypes.length}</span>
              <p>Review event names, lifecycle state, versions, categories, and the schema supplied by the events API.</p>
              <span className="api-catalog-collection__foot">{activeEvents} active <ArrowRight size={14} /></span>
            </button>
          </div>
          <div className="api-catalog-note"><CircleAlert size={16} /><p><strong>Availability is explicit.</strong> Scopes marked unavailable or deprecated should not be used to infer support that the system has not confirmed.</p></div>
        </section>
      )}

      {activeTab === "scopes" && (
        <section className="api-catalog-panel" id="api-catalog-panel-scopes" role="tabpanel" aria-labelledby="api-catalog-tab-scopes">
          <div className="api-catalog-panel-heading">
            <div><span className="api-catalog-kicker">Authorization</span><h2>API-key scope registry</h2><p>Each scope is a system-defined permission with explicit assignment and lifecycle metadata.</p></div>
            <span className="api-catalog-result-count">{visibleScopes.length} of {scopeError ? "—" : scopes.length}</span>
          </div>
          <div className="api-catalog-filters" aria-label="Scope availability filters">
            {([
              ["all", "All scopes"],
              ["assignable", "Assignable"],
              ["restricted", "Restricted"],
              ["deprecated", "Deprecated"],
            ] as const).map(([id, label]) => <button type="button" key={id} className={scopeFilter === id ? "is-active" : ""}
              aria-pressed={scopeFilter === id} onClick={() => setScopeFilter(id)}>{label}</button>)}
          </div>
          {loading && scopes.length === 0 ? <p className="security-empty-state">Loading the system scope registry…</p>
            : visibleScopes.length === 0 ? <p className="security-empty-state">{scopeError ? "Scope data is unavailable." : "No scopes match these filters."}</p>
              : <div className="api-catalog-scope-list">{visibleScopes.map((scope) => (
                <article className="api-catalog-scope-card" key={scope.name}>
                  <div className="api-catalog-scope-card__top">
                    <div><code>{scope.name}</code><p>{scope.description}</p></div>
                    <ScopeStatus scope={scope} />
                  </div>
                  <div className="api-catalog-scope-card__metadata">
                    <span><small>Category</small><strong>{scope.category}</strong></span>
                    <span><small>Version</small><strong>v{scope.version}</strong></span>
                    <span><small>Access model</small><strong>{scope.restricted ? "Restricted" : "Standard"}</strong></span>
                    <span><small>Security review</small><strong>{scope.requiresSecurityReview ? "Required" : "Not required"}</strong></span>
                  </div>
                  {scope.replacedBy && <div className="api-catalog-replacement"><span>Replacement</span><code>{scope.replacedBy}</code></div>}
                </article>
              ))}</div>}
        </section>
      )}

      {activeTab === "events" && (
        <section className="api-catalog-panel" id="api-catalog-panel-events" role="tabpanel" aria-labelledby="api-catalog-tab-events">
          <div className="api-catalog-panel-heading">
            <div><span className="api-catalog-kicker">Event delivery</span><h2>Event contract catalog</h2><p>Inspect the event lifecycle and expand a contract to review its schema.</p></div>
            <span className="api-catalog-result-count">{visibleEventTypes.length} of {eventError ? "—" : eventTypes.length}</span>
          </div>
          {loading && eventTypes.length === 0 ? <p className="security-empty-state">Loading event contracts…</p>
            : visibleEventTypes.length === 0 ? <p className="security-empty-state">{eventError ? "Event contract data is unavailable." : "No event contracts match these filters."}</p>
              : <div className="api-catalog-event-list">{visibleEventTypes.map((event) => {
                const expanded = expandedEvent === event.name;
                return <article className={`api-catalog-event-card${expanded ? " is-expanded" : ""}`} key={event.name}>
                  <button type="button" className="api-catalog-event-card__summary" aria-expanded={expanded}
                    onClick={() => setExpandedEvent(expanded ? "" : event.name)}>
                    <span className="api-catalog-event-card__icon"><Activity size={16} /></span>
                    <span className="api-catalog-event-card__identity"><strong>{event.name}</strong><small>{event.description}</small></span>
                    <span className="api-catalog-event-card__category">{event.category}</span>
                    <span className="api-catalog-event-card__version">v{event.version}</span>
                    <span className={`api-catalog-status api-catalog-status--${event.lifecycle.toUpperCase() === "ACTIVE" ? "positive" : "muted"}`}>{event.lifecycle}</span>
                    <ChevronDown size={15} className="api-catalog-event-card__chevron" />
                  </button>
                  {expanded && <div className="api-catalog-schema">
                    <div><strong>Payload schema</strong><span>Version {event.version} · {event.category}</span></div>
                    <pre><code>{formatSchema(event.schema)}</code></pre>
                  </div>}
                </article>;
              })}</div>}
        </section>
      )}
    </>
  );
}
