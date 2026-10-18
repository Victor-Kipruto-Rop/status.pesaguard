import { getUserMessage } from "../../lib/errors";
import { Activity, AlertCircle, CheckCircle2, Download, Gauge, RefreshCw, TimerReset, TrendingUp } from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiBlob, apiData } from "../../lib/api";
import {
  readActiveEnvironmentContext,
  readActiveEnvironmentId,
  readActiveProjectId,
  setActiveEnvironment,
  setActiveProject,
} from "../../lib/activeEnvironment";

type DateRange = "24h" | "7d" | "30d" | "custom";
type UsageGranularity = "HOUR" | "DAY";

type UsagePoint = {
  windowStart: string;
  totalRequests: number;
  successfulRequests: number;
  failedRequests: number;
  errorRate: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  p99LatencyMs: number;
};

type UsageSeries = {
  granularity: UsageGranularity;
  from: string;
  to: string;
  summary: {
    totalRequests: number;
    successfulRequests: number;
    failedRequests: number;
    errorRate: number;
    averageLatencyMs: number;
    p95LatencyMs: number;
    responseBytes: number | null;
  };
  points: UsagePoint[];
};

type Project = { id: string; name: string };
type ProjectList = { items: Project[] };
type Environment = { id: string; projectId: string; name: string; type: string; status: string };

const numberFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });

function inputDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function getRange(range: DateRange, fromDate: string, toDate: string) {
  const to = new Date();
  let from: Date;

  if (range === "custom") {
    from = new Date(`${fromDate}T00:00:00`);
    to.setTime(new Date(`${toDate}T00:00:00`).getTime());
    to.setDate(to.getDate() + 1);
  } else {
    from = new Date(to);
    from.setHours(from.getHours() - (range === "24h" ? 24 : range === "7d" ? 24 * 7 : 24 * 30));
  }

  if (!Number.isFinite(from.getTime()) || !Number.isFinite(to.getTime()) || from >= to) {
    return { error: "Choose a valid start and end date.", from: null, to: null, granularity: "HOUR" as const };
  }
  if (to.getTime() - from.getTime() > 366 * 24 * 60 * 60 * 1000) {
    return { error: "Usage ranges cannot exceed 366 days.", from: null, to: null, granularity: "DAY" as const };
  }

  const span = to.getTime() - from.getTime();
  return {
    error: null,
    from,
    to,
    granularity: range === "30d" || span > 10 * 24 * 60 * 60 * 1000 ? "DAY" as const : "HOUR" as const,
  };
}

function formatCount(value: number) {
  return numberFormat.format(value);
}

function formatPercent(value: number) {
  return `${(value * 100).toFixed(2)}%`;
}

function formatBytes(value: number | null | undefined) {
  if (value == null) return "Not reported";
  if (value < 1024) return `${formatCount(value)} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let amount = value / 1024;
  let unit = 0;
  while (amount >= 1024 && unit < units.length - 1) {
    amount /= 1024;
    unit += 1;
  }
  return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(amount)} ${units[unit]}`;
}

function formatWindow(value: string, granularity: UsageGranularity) {
  const date = new Date(value);
  return new Intl.DateTimeFormat(undefined, granularity === "HOUR"
    ? { month: "short", day: "numeric", hour: "numeric" }
    : { month: "short", day: "numeric" }).format(date);
}

export function UsagePage({ apiKeyId }: { apiKeyId?: string }) {
  const { isAuthenticated } = useAuth();
  const today = inputDate(new Date());
  const initialFrom = new Date();
  initialFrom.setDate(initialFrom.getDate() - 29);
  const [range, setRange] = useState<DateRange>("30d");
  const [fromDate, setFromDate] = useState(inputDate(initialFrom));
  const [toDate, setToDate] = useState(today);
  const [projectId, setProjectId] = useState(readActiveProjectId);
  const [environmentId, setEnvironmentId] = useState(
    () => readActiveEnvironmentContext().environmentId,
  );
  const [projects, setProjects] = useState<Project[]>([]);
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [series, setSeries] = useState<UsageSeries | null>(null);
  const [endpoints, setEndpoints] = useState<string[]>([]);
  const [loading, setLoading] = useState(isAuthenticated);
  const [endpointLoading, setEndpointLoading] = useState(isAuthenticated);
  const [error, setError] = useState<string | null>(null);
  const [endpointError, setEndpointError] = useState<string | null>(null);
  const [projectError, setProjectError] = useState<string | null>(null);
  const [environmentError, setEnvironmentError] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState(0);
  const [refreshedAt, setRefreshedAt] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exportTruncated, setExportTruncated] = useState(false);

  const requestRange = useMemo(() => getRange(range, fromDate, toDate), [fromDate, range, toDate]);

  useEffect(() => {
    function syncEnvironmentSelection() {
      const context = readActiveEnvironmentContext();
      setProjectId(context.projectId);
      setEnvironmentId(context.environmentId);
      setSeries(null);
      setEndpoints([]);
    }
    window.addEventListener("pesaguard:active-context-changed", syncEnvironmentSelection);
    return () => window.removeEventListener("pesaguard:active-context-changed", syncEnvironmentSelection);
  }, []);

  useEffect(() => {
    if (!isAuthenticated) {
      setProjects([]);
      setProjectError(null);
      return;
    }
    const controller = new AbortController();
    setProjectError(null);
    apiData<ProjectList>("/api/v1/projects?page=0&size=100", { signal: controller.signal })
      .then((response) => {
        if (!Array.isArray(response.items)) throw new Error("The projects API returned an invalid list.");
        setProjects(response.items);
        const savedProjectId = readActiveProjectId();
        const selected = response.items.find((project) => project.id === savedProjectId)
          ?? response.items[0];
        if (selected && selected.id !== projectId) {
          setProjectId(selected.id);
          setActiveProject(selected);
        }
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setProjectError(getUserMessage(requestError, "Unable to load projects."));
      });
    return () => controller.abort();
  }, [isAuthenticated, retryKey]);

  useEffect(() => {
    setEnvironmentError(null);
    if (!isAuthenticated || !projectId) {
      setEnvironments([]);
      return;
    }

    const controller = new AbortController();
    apiData<Environment[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/environments`, { signal: controller.signal })
      .then((response) => {
        if (!Array.isArray(response)) throw new Error("The environments API returned an invalid list.");
        setEnvironments(response);
        const savedEnvironmentId = readActiveEnvironmentId(projectId);
        const selected = response.find((environment) => environment.id === savedEnvironmentId)
          ?? response.find((environment) => environment.name.toUpperCase() === "SANDBOX"
            && environment.status === "ACTIVE")
          ?? response.find((environment) => environment.status === "ACTIVE")
          ?? response[0];
        if (selected && selected.id !== environmentId) {
          setEnvironmentId(selected.id);
          const project = projects.find((item) => item.id === projectId);
          if (project) setActiveEnvironment(project, selected);
        }
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setEnvironmentError(getUserMessage(requestError, "Unable to load environments."));
      });
    return () => controller.abort();
  }, [isAuthenticated, projectId, retryKey]);

  useEffect(() => {
    if (!isAuthenticated) {
      setSeries(null);
      setLoading(false);
      setError("Sign in to view workspace usage.");
      return;
    }
    if (!projectId || !environmentId) {
      setSeries(null);
      setLoading(false);
      setError(null);
      return;
    }
    if (requestRange.error || !requestRange.from || !requestRange.to) {
      setLoading(false);
      setError(requestRange.error);
      setSeries(null);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setError(null);
    const params = new URLSearchParams({
      from: requestRange.from.toISOString(),
      to: requestRange.to.toISOString(),
      granularity: requestRange.granularity,
    });
    if (projectId) params.set("projectId", projectId);
    if (environmentId) params.set("environmentId", environmentId);
    if (apiKeyId) params.set("apiKeyId", apiKeyId);

    apiData<UsageSeries>(`/api/v1/usage?${params.toString()}`, { signal: controller.signal })
      .then((response) => {
        if (!response.summary || !Array.isArray(response.points)) {
          throw new Error("The usage API returned an incomplete response.");
        }
        setSeries(response);
        setRefreshedAt(new Date().toISOString());
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) {
          setSeries(null);
          setError(getUserMessage(requestError, "Unable to load usage."));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [apiKeyId, environmentId, isAuthenticated, projectId, requestRange, retryKey]);

  useEffect(() => {
    if (!isAuthenticated || !projectId || !environmentId || !requestRange.from || !requestRange.to || requestRange.error) {
      setEndpoints([]);
      setEndpointLoading(false);
      return;
    }
    const controller = new AbortController();
    setEndpointLoading(true);
    setEndpointError(null);
    const params = new URLSearchParams({
      from: requestRange.from.toISOString(),
      to: requestRange.to.toISOString(),
      granularity: requestRange.granularity,
    });
    if (projectId) params.set("projectId", projectId);
    if (environmentId) params.set("environmentId", environmentId);
    if (apiKeyId) params.set("apiKeyId", apiKeyId);
    apiData<string[]>(`/api/v1/usage/endpoints?${params.toString()}`, { signal: controller.signal })
      .then((response) => {
        if (!Array.isArray(response)) throw new Error("The endpoints API returned an invalid list.");
        setEndpoints(response);
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setEndpointError(getUserMessage(requestError, "Unable to load observed endpoints."));
      })
      .finally(() => {
        if (!controller.signal.aborted) setEndpointLoading(false);
      });
    return () => controller.abort();
  }, [apiKeyId, environmentId, isAuthenticated, projectId, requestRange, retryKey]);

  const points = series?.points ?? [];
  const latestPoint = points.at(-1);
  const highestRequests = Math.max(1, ...points.map((point) => point.totalRequests));
  const rangeLabel = range === "24h" ? "Last 24 hours" : range === "7d" ? "Last 7 days" : range === "30d" ? "Last 30 days" : `${fromDate} to ${toDate}`;
  const summary = series?.summary;

  async function exportUsage() {
    if (!projectId || !environmentId || !requestRange.from || !requestRange.to || requestRange.error) return;
    const params = new URLSearchParams({
      from: requestRange.from.toISOString(),
      to: requestRange.to.toISOString(),
      granularity: requestRange.granularity,
    });
    if (projectId) params.set("projectId", projectId);
    if (environmentId) params.set("environmentId", environmentId);
    if (apiKeyId) params.set("apiKeyId", apiKeyId);
    setExporting(true);
    setExportError(null);
    setExportTruncated(false);
    try {
      const blob = await apiBlob(`/api/v1/exports/usage?${params.toString()}`, {
        onResponse: (response) => setExportTruncated(response.headers.get("X-Export-Truncated") === "true"),
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "pesaguard-usage.csv";
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (requestError) {
      setExportError(getUserMessage(requestError, "Usage export failed."));
    } finally {
      setExporting(false);
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="MONITOR"
        title={apiKeyId ? "API key usage" : "Usage"}
        description={apiKeyId ? `Monitor request volume, reliability, and response times for the selected API key only.` : "Monitor request volume, reliability, and response times across your workspace."}
        action={
          <div className="usage-header-actions">
            <button className="button button--secondary" type="button" onClick={() => setRetryKey((current) => current + 1)} disabled={loading}>
              <RefreshCw size={14} className={loading ? "usage-refresh-icon" : ""} />Refresh
            </button>
            <button className="button button--primary" type="button" onClick={() => void exportUsage()} disabled={!series || loading || exporting}>
              <Download size={14} />{exporting ? "Preparing CSV…" : "Export CSV"}
            </button>
          </div>
        }
      />

      {apiKeyId && <div className="api-key-scope-banner"><Activity size={16} /><span>Showing usage for one API key</span><code>{apiKeyId}</code></div>}

      {!isAuthenticated && <div className="preview-notice"><span className="notice-icon"><Gauge size={16} /></span><p><strong>Sign in required</strong> — Usage is workspace-scoped and no sample billing or request data is shown.</p></div>}
      {exportError && <p className="workflow-error" role="alert">Unable to export usage: {exportError}</p>}
      {exportTruncated && <p className="workflow-hint" role="status">The export reached the 5,000-row limit. Narrow the date or project filters and export again.</p>}
      {error && isAuthenticated && <div className="workflow-error" role="alert"><p>Unable to load usage: {error}</p><button className="text-button" type="button" onClick={() => setRetryKey((current) => current + 1)}>Retry</button></div>}
      {projectError && <p className="workflow-error" role="alert">Project filters unavailable: {projectError}</p>}

      <section className="panel usage-filter-panel" aria-label="Usage filters">
        <div className="usage-filter-heading">
          <div><span className="usage-eyebrow">REPORTING WINDOW</span><h2>Usage overview</h2></div>
          <button className="text-button" type="button" onClick={() => { setRange("30d"); setFromDate(inputDate(initialFrom)); setToDate(today); }}>Reset filters</button>
        </div>
        <div className="usage-filter-layout">
          <div className="usage-range-options" role="group" aria-label="Select a time range">
            {([
              ["24h", "24 hours"],
              ["7d", "7 days"],
              ["30d", "30 days"],
              ["custom", "Custom"],
            ] as const).map(([value, label]) => (
              <button
                className={`usage-range-option${range === value ? " usage-range-option--active" : ""}`}
                type="button"
                key={value}
                aria-pressed={range === value}
                onClick={() => setRange(value)}
              >{label}</button>
            ))}
          </div>
          <div className="usage-filter-grid">
            <label className="usage-filter-field"><span>Project</span><select value={projectId} onChange={(event) => {
              const project = projects.find((item) => item.id === event.target.value);
              if (project) {
                setProjectId(project.id);
                setEnvironmentId("");
                setSeries(null);
                setEndpoints([]);
                setActiveProject(project);
              }
            }} disabled={Boolean(projectError) || projects.length === 0}>{projects.map((project) => <option value={project.id} key={project.id}>{project.name}</option>)}</select></label>
            <label className="usage-filter-field"><span>Environment</span><select value={environmentId} onChange={(event) => {
              const environment = environments.find((item) => item.id === event.target.value);
              const project = projects.find((item) => item.id === projectId);
              if (environment && project) {
                setEnvironmentId(environment.id);
                setSeries(null);
                setEndpoints([]);
                setActiveEnvironment(project, environment);
              }
            }} disabled={!projectId || Boolean(environmentError) || environments.length === 0}>{environments.map((environment) => <option value={environment.id} key={environment.id}>{environment.name}</option>)}</select></label>
          </div>
          {range === "custom" && <div className="usage-date-range"><label className="usage-filter-field"><span>From</span><input type="date" value={fromDate} max={toDate || today} onChange={(event) => setFromDate(event.target.value)} /></label><label className="usage-filter-field"><span>To</span><input type="date" value={toDate} min={fromDate} max={today} onChange={(event) => setToDate(event.target.value)} /></label></div>}
        </div>
        {environmentError && <p className="workflow-error" role="alert">Environment filter unavailable: {environmentError}</p>}
        <div className="usage-filter-footer">
          <p className="usage-filter-result" role="status">{loading ? "Loading workspace usage…" : `${rangeLabel} · ${projectId ? projects.find((project) => project.id === projectId)?.name ?? "Selected project" : "All projects"}${environmentId ? ` · ${environments.find((environment) => environment.id === environmentId)?.name ?? "Selected environment"}` : ""}`}</p>
          <span>{refreshedAt ? `Updated ${new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" }).format(new Date(refreshedAt))}` : "Live workspace data"}</span>
        </div>
      </section>

      <section className="usage-kpi-grid" aria-label="Usage summary">
        <UsageStat icon={<Activity size={16} />} label="Total requests" value={summary ? formatCount(summary.totalRequests) : "—"} detail={rangeLabel} tone="primary" />
        <UsageStat icon={<CheckCircle2 size={16} />} label="Successful requests" value={summary ? formatCount(summary.successfulRequests) : "—"} detail={summary && summary.totalRequests > 0 ? `${formatPercent(summary.successfulRequests / summary.totalRequests)} success rate` : "2xx responses"} tone="good" />
        <UsageStat icon={<AlertCircle size={16} />} label="Failed requests" value={summary ? formatCount(summary.failedRequests) : "—"} detail={summary ? `${formatPercent(summary.errorRate)} error rate` : "4xx and 5xx responses"} tone={summary && summary.failedRequests > 0 ? "bad" : "neutral"} />
        <UsageStat icon={<Gauge size={16} />} label="P95 latency" value={summary ? `${formatCount(summary.p95LatencyMs)} ms` : "—"} detail={summary ? `${formatCount(summary.averageLatencyMs)} ms average` : "Across returned usage buckets"} tone="neutral" />
      </section>

      <section className="panel usage-chart-panel">
        <div className="usage-chart-heading">
          <div><span className="usage-eyebrow">TRAFFIC</span><h2>Request volume</h2><p>{rangeLabel} · {series?.granularity.toLowerCase() ?? requestRange.granularity.toLowerCase()} buckets</p></div>
          <div className="usage-chart-highlights">
            <span><small>Peak bucket</small><strong>{latestPoint && points.length ? formatCount(Math.max(...points.map((point) => point.totalRequests))) : "—"}</strong></span>
            <span className="usage-chart-legend"><i />Requests</span>
          </div>
        </div>
        {loading ? <p className="workflow-hint" role="status">Loading request buckets…</p> : points.length === 0 ? <div className="usage-empty-state"><Activity size={19} /><strong>No request activity in this range</strong><span>Try a wider time window or select another project.</span></div> : (
          <>
            <div className="usage-chart" role="img" aria-label={`Request volume for ${rangeLabel}`}>
              <div className="usage-axis"><span>{formatCount(highestRequests)}</span><span>{formatCount(Math.round(highestRequests / 2))}</span><span>0</span></div>
              <div className="usage-bars">{points.map((point) => <span key={point.windowStart} title={`${formatWindow(point.windowStart, series?.granularity ?? "HOUR")}: ${formatCount(point.totalRequests)} requests · ${formatCount(point.failedRequests)} failed`} style={{ height: `${Math.max(point.totalRequests ? 3 : 1, point.totalRequests / highestRequests * 100)}%` }} />)}</div>
            </div>
            <div className="usage-dates"><span>{formatWindow(points[0].windowStart, series?.granularity ?? "HOUR")}</span><span>{formatWindow(points[points.length - 1].windowStart, series?.granularity ?? "HOUR")}</span></div>
          </>
        )}
      </section>

      <section className="panel usage-breakdown-panel">
        <div className="usage-chart-heading usage-breakdown-heading"><div><span className="usage-eyebrow">BUCKET DETAILS</span><h2>Usage by time window</h2><p>Request totals, error rates, and latency percentiles returned by the usage service.</p></div><TimerReset size={18} className="heading-icon" /></div>
        <div className="table-scroll"><table className="data-table usage-table"><thead><tr><th>TIME WINDOW</th><th>REQUESTS</th><th>SUCCESSFUL</th><th>FAILED</th><th>ERROR RATE</th><th>P95 LATENCY</th></tr></thead><tbody>{points.map((point) => <tr key={point.windowStart}><td>{formatWindow(point.windowStart, series?.granularity ?? "HOUR")}</td><td><strong>{formatCount(point.totalRequests)}</strong></td><td>{formatCount(point.successfulRequests)}</td><td>{formatCount(point.failedRequests)}</td><td><span className={`usage-error-rate${point.errorRate > 0 ? " usage-error-rate--has-errors" : ""}`}>{formatPercent(point.errorRate)}</span></td><td>{formatCount(point.p95LatencyMs)} ms</td></tr>)}{!loading && points.length === 0 && <tr><td colSpan={6}>No usage data is available for the selected range.</td></tr>}</tbody></table></div>
        {points.length > 0 && <p className="usage-table-footnote">Showing {formatCount(points.length)} {series?.granularity.toLowerCase()} time windows. Export the CSV for the full underlying series.</p>}
      </section>

      <section className="panel usage-endpoints-panel">
        <div className="usage-chart-heading usage-breakdown-heading"><div><span className="usage-eyebrow">REQUEST FOOTPRINT</span><h2>Observed endpoints</h2><p>Routes seen in this workspace during the selected date range.</p></div><span className="usage-endpoint-count">{endpointLoading ? "…" : endpoints.length}</span></div>
        {endpointError && <p className="workflow-error" role="alert">Unable to load observed endpoints: {endpointError}</p>}
        {endpointLoading ? <p className="workflow-hint" role="status">Loading observed endpoints…</p> : endpoints.length > 0 ? <ul className="observed-endpoint-list">{endpoints.map((endpoint) => <li key={endpoint}><span><Activity size={14} /></span><code>{endpoint}</code><TrendingUp size={13} className="usage-endpoint-indicator" /></li>)}</ul> : !endpointError && <div className="usage-empty-state usage-empty-state--compact"><span>No endpoints were returned for this date range.</span></div>}
        <p className="usage-data-note">Endpoint totals are not available from this API. Response size: {formatBytes(summary?.responseBytes)}.</p>
      </section>
      {latestPoint && <p className="usage-data-note">Most recent bucket latency: p50 {formatCount(latestPoint.p50LatencyMs)} ms · p95 {formatCount(latestPoint.p95LatencyMs)} ms · p99 {formatCount(latestPoint.p99LatencyMs)} ms. Metrics are aggregated from recorded request telemetry.</p>}
    </>
  );
}

function UsageStat({ icon, label, value, detail, tone }: { icon: ReactNode; label: string; value: string; detail: string; tone: "primary" | "good" | "bad" | "neutral" }) {
  return (
    <article className={`usage-kpi-card usage-kpi-card--${tone}`}>
      <div className="usage-kpi-top"><span>{label}</span><span className="usage-kpi-icon">{icon}</span></div>
      <strong>{value}</strong>
      <small>{detail}</small>
    </article>
  );
}
