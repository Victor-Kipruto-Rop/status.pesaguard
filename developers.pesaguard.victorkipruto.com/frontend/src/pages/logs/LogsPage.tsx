import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useEffect, useState } from "react";
import { Activity, AlertTriangle, ArrowRight, Clock3, Download, FileText, RefreshCw, Search } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiBlob, apiData } from "../../lib/api";
import { readActiveEnvironmentContext } from "../../lib/activeEnvironment";

interface LogsPageProps {
  apiKeyId?: string;
  logId: string | null;
  onOpenLog: (logId: string) => void;
  onBack: () => void;
}

type RequestLog = {
  requestId: string;
  projectId: string;
  environmentId: string;
  endpoint: string;
  method: string;
  statusCode: number;
  latencyMs: number;
  responseBytes: number | null;
  occurredAt: string;
  recordedAt: string;
};
type RequestPage = { content: RequestLog[]; totalElements: number; number: number; size: number };

export function LogsPage({ apiKeyId, logId, onOpenLog, onBack }: LogsPageProps) {
  const initialContext = readActiveEnvironmentContext();
  const [projectId, setProjectId] = useState(initialContext.projectId);
  const [environmentId, setEnvironmentId] = useState(initialContext.environmentId);
  const [logs, setLogs] = useState<RequestLog[]>([]);
  const [selected, setSelected] = useState<RequestLog | null>(null);
  const [requestIdFilter, setRequestIdFilter] = useState("");
  const [methodFilter, setMethodFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState("");
  const [exportTruncated, setExportTruncated] = useState(false);

  useEffect(() => {
    function syncEnvironmentSelection() {
      const context = readActiveEnvironmentContext();
      setProjectId(context.projectId);
      setEnvironmentId(context.environmentId);
      setLogs([]);
      setSelected(null);
      setPage(0);
      setTotal(0);
    }
    window.addEventListener("pesaguard:active-context-changed", syncEnvironmentSelection);
    return () => window.removeEventListener("pesaguard:active-context-changed", syncEnvironmentSelection);
  }, []);

  async function exportLogs() {
    const params = requestFilters();
    setExporting(true);
    setExportError("");
    setExportTruncated(false);
    try {
      const query = params.toString();
      const blob = await apiBlob(`/api/v1/exports/logs${query ? `?${query}` : ""}`, {
        onResponse: (response) => setExportTruncated(response.headers.get("X-Export-Truncated") === "true"),
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "pesaguard-request-logs.csv";
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (requestError) {
      setExportError(getUserMessage(requestError, "Request log export failed."));
    } finally {
      setExporting(false);
    }
  }

  function requestFilters() {
    const params = new URLSearchParams();
    if (projectId) params.set("projectId", projectId);
    if (environmentId) params.set("environmentId", environmentId);
    if (apiKeyId) params.set("apiKeyId", apiKeyId);
    if (requestIdFilter.trim()) params.set("requestId", requestIdFilter.trim());
    if (methodFilter) params.set("method", methodFilter);
    if (statusFilter) params.set("statusCode", statusFilter);
    if (fromDate) params.set("from", new Date(`${fromDate}T00:00:00`).toISOString());
    if (toDate) {
      const exclusiveEnd = new Date(`${toDate}T00:00:00`);
      exclusiveEnd.setDate(exclusiveEnd.getDate() + 1);
      params.set("to", exclusiveEnd.toISOString());
    }
    return params;
  }

  useEffect(() => {
    if (logId) {
      if (!projectId || !environmentId) {
        setLoading(false);
        setSelected(null);
        setError("Select a project and environment to inspect its request logs.");
        return;
      }
      const controller = new AbortController();
      setLoading(true);
      setError("");
      const params = new URLSearchParams({ projectId, environmentId });
      if (apiKeyId) params.set("apiKeyId", apiKeyId);
      void apiData<RequestLog>(`/api/v1/usage/requests/${encodeURIComponent(logId)}?${params.toString()}`, { signal: controller.signal })
        .then(setSelected)
        .catch((requestError: unknown) => {
          if (!controller.signal.aborted) {
            setSelected(null);
            setError(getUserMessage(requestError, "Request details could not be loaded."));
          }
          if (!projectId || !environmentId) {
            setLoading(false);
            setLogs([]);
            setTotal(0);
            setError("Select a project and environment to view request logs.");
            return;
          }
        })
        .finally(() => { if (!controller.signal.aborted) setLoading(false); });
      return () => controller.abort();
    }
    const controller = new AbortController();
    const params = requestFilters();
    params.set("page", String(page));
    params.set("size", "50");
    setLoading(true);
    setError("");
    void apiData<RequestPage>(`/api/v1/usage/requests?${params.toString()}`, { signal: controller.signal })
      .then((result) => {
        if (!result || !Array.isArray(result.content)) throw new Error("The request logs API returned an invalid response.");
        setLogs(result.content);
        setTotal(result.totalElements);
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) {
          setLogs([]);
          setTotal(0);
          setError(getUserMessage(requestError, "Request logs could not be loaded."));
        }
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [apiKeyId, logId, page, projectId, environmentId, refreshKey, requestIdFilter, methodFilter, statusFilter, fromDate, toDate]);

  const failedCount = logs.filter((log) => log.statusCode >= 400).length;
  const averageLatency = logs.length
    ? Math.round(logs.reduce((totalLatency, log) => totalLatency + log.latencyMs, 0) / logs.length)
    : 0;

  return (
    <>
      <PageHeader eyebrow="OBSERVABILITY · REQUEST TELEMETRY" title={logId ? "Request details" : apiKeyId ? "API key request logs" : "Request logs"} description="Investigate persisted API request metadata without exposing request bodies, credentials, or response payloads." action={logId ? <button className="button button--secondary" type="button" onClick={onBack}>Back to logs</button> : <div className="usage-header-actions"><button className="button button--secondary" type="button" disabled={loading} onClick={() => setRefreshKey((key) => key + 1)}><RefreshCw size={14} />Refresh</button><button className="button button--primary" type="button" disabled={exporting} onClick={() => void exportLogs()}><Download size={14} />{exporting ? "Preparing CSV…" : "Export filtered CSV"}</button></div>} />
      {apiKeyId && <div className="api-key-scope-banner"><Activity size={16} /><span>Logs are filtered to this API key</span><code>{apiKeyId}</code></div>}
      {error && <p className="workflow-error" role="alert">{error}</p>}
      {exportError && <p className="workflow-error" role="alert">Unable to export request logs: {exportError}</p>}
      {exportTruncated && !logId && <p className="workflow-hint" role="status">The export reached the 5,000-row limit. Narrow the date or request filters and export again.</p>}
      {logId ? (
        <section className="panel">
          <div className="panel-heading"><div><h2>Persisted request record</h2><p>This is request-level telemetry, not a distributed span trace.</p></div><FileText size={18} /></div>
          {loading ? <p className="workflow-hint" role="status">Loading request details…</p> : selected && <dl className="request-log-details">
            <dt>Request ID</dt><dd><code>{selected.requestId}</code></dd>
            <dt>Method</dt><dd>{selected.method}</dd>
            <dt>Endpoint</dt><dd><code>{selected.endpoint}</code></dd>
            <dt>Status</dt><dd>{selected.statusCode}</dd>
            <dt>Latency</dt><dd>{selected.latencyMs} ms</dd>
            <dt>Response bytes</dt><dd>{selected.responseBytes ?? "Not recorded"}</dd>
            <dt>Project</dt><dd><code>{selected.projectId}</code></dd>
            <dt>Environment</dt><dd><code>{selected.environmentId}</code></dd>
            <dt>Occurred</dt><dd>{new Date(selected.occurredAt).toLocaleString()}</dd>
            <dt>Recorded</dt><dd>{new Date(selected.recordedAt).toLocaleString()}</dd>
          </dl>}
        </section>
      ) : (
        <>
        <section className="request-log-metrics">
          <article><span className="request-log-metric-icon"><FileText size={16} /></span><div><small>Matching requests</small><strong>{loading ? "…" : total.toLocaleString()}</strong><span>Last 7 days by default</span></div></article>
          <article><span className="request-log-metric-icon request-log-metric-icon--warning"><AlertTriangle size={16} /></span><div><small>Errors on this page</small><strong>{loading ? "…" : failedCount.toLocaleString()}</strong><span>HTTP 4xx and 5xx</span></div></article>
          <article><span className="request-log-metric-icon request-log-metric-icon--latency"><Clock3 size={16} /></span><div><small>Average latency on this page</small><strong>{loading ? "…" : `${averageLatency} ms`}</strong><span>Based on visible records</span></div></article>
        </section>
        <section className="panel table-panel request-log-panel">
          <div className="panel-heading"><div><h2>Request activity</h2><p>Filter, inspect, and export request-level telemetry for the active workspace context.</p></div><span className="table-tag">PERSISTED EVENTS</span></div>
          <ValidatedForm className="workflow-form request-log-filters request-log-filters--advanced" onSubmit={(event) => { event.preventDefault(); setPage(0); setRefreshKey((key) => key + 1); }}>
            <label><span>Request ID</span><input value={requestIdFilter} onChange={(event) => setRequestIdFilter(event.target.value)} maxLength={64} placeholder="Enter request ID" /></label>
            <label><span>Method</span><select value={methodFilter} onChange={(event) => setMethodFilter(event.target.value)}><option value="">All methods</option><option value="GET">GET</option><option value="POST">POST</option><option value="PUT">PUT</option><option value="PATCH">PATCH</option><option value="DELETE">DELETE</option></select></label>
            <label><span>HTTP status</span><select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}><option value="">All statuses</option><option value="200">200 · Success</option><option value="400">400 · Bad request</option><option value="401">401 · Unauthorized</option><option value="403">403 · Forbidden</option><option value="404">404 · Not found</option><option value="429">429 · Rate limited</option><option value="500">500 · Server error</option></select></label>
            <label><span>From date</span><input type="date" value={fromDate} max={toDate || undefined} onChange={(event) => setFromDate(event.target.value)} /></label>
            <label><span>To date</span><input type="date" value={toDate} min={fromDate || undefined} onChange={(event) => setToDate(event.target.value)} /></label>
            <button className="button button--primary" type="submit"><Search size={14} />Apply filters</button>
          </ValidatedForm>
          {loading ? <p className="workflow-hint" role="status">Loading request logs…</p> : logs.length === 0 ? <div className="organization-live-empty"><FileText size={18} />No request records match this search.</div> :
            <div className="table-scroll"><table className="data-table request-log-table"><thead><tr><th>OCCURRED</th><th>METHOD</th><th>ENDPOINT</th><th>STATUS</th><th>LATENCY</th><th>REQUEST ID</th><th></th></tr></thead><tbody>{logs.map((log) => <tr key={log.requestId}><td>{new Date(log.occurredAt).toLocaleString()}</td><td><span className={`request-log-method request-log-method--${log.method.toLowerCase()}`}>{log.method}</span></td><td><code>{log.endpoint}</code></td><td><span className={`request-log-status${log.statusCode >= 500 ? " is-server-error" : log.statusCode >= 400 ? " is-client-error" : " is-success"}`}>{log.statusCode}</span></td><td><span className="request-log-latency">{log.latencyMs}<small>ms</small></span></td><td><code>{log.requestId}</code></td><td><button className="request-log-details-link" type="button" onClick={() => onOpenLog(log.requestId)}>Inspect <ArrowRight size={13} /></button></td></tr>)}</tbody></table></div>}
          <div className="request-log-pagination"><span>Showing {logs.length ? page * 50 + 1 : 0}–{page * 50 + logs.length} of {total.toLocaleString()}</span><div><button className="button button--secondary" type="button" disabled={page === 0 || loading} onClick={() => setPage((current) => Math.max(0, current - 1))}>Previous</button><button className="button button--secondary" type="button" disabled={loading || (page + 1) * 50 >= total} onClick={() => setPage((current) => current + 1)}>Next</button></div></div>
        </section>
        </>
      )}
    </>
  );
}
