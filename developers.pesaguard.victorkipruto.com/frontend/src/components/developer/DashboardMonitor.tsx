import { getUserMessage } from "../../lib/errors";
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Clock3,
  Gauge,
  RefreshCw,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { externalLinks } from "../../app/routes";
import type { PageId } from "../../app/routes";
import { useAuth } from "../../context/AuthContext";
import { apiData, apiFetch } from "../../lib/api";
import {
  readActiveEnvironmentContext,
  readActiveEnvironmentId,
  readActiveProjectId,
  setActiveEnvironment,
  setActiveProject,
} from "../../lib/activeEnvironment";

type MetricStatus = "live" | "unavailable";

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

type UsageResponse = {
  summary: {
    totalRequests: number;
    successfulRequests: number;
    failedRequests: number;
    errorRate: number;
    averageLatencyMs: number;
    p95LatencyMs: number;
  };
  points: UsagePoint[];
};

type ProjectResponse = {
  items: { id: string; name: string; status: string }[];
  totalElements: number;
};

type EnvironmentResponse = { id: string; projectId: string; name: string; type: string; status: string }[];

type LoadState<T> =
  | { status: "loading" }
  | { status: "unavailable"; message: string }
  | { status: "ready"; data: T };

type UsageData = UsageResponse;
type UsageRange = "24h" | "7d" | "30d";

const usageRangeMs: Record<UsageRange, number> = {
  "24h": 24 * 60 * 60 * 1000,
  "7d": 7 * 24 * 60 * 60 * 1000,
  "30d": 30 * 24 * 60 * 60 * 1000,
};

const numberFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });

function displayMetric(value: number | null | undefined, suffix = "") {
  if (value === null || value === undefined || !Number.isFinite(value)) return "—";
  return `${numberFormat.format(value)}${suffix}`;
}

function displayPercent(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(value)) return "—";
  return `${(value * 100).toFixed(2)}%`;
}

function ageLabel(value: string) {
  const elapsedSeconds = Math.max(0, Math.floor((Date.now() - Date.parse(value)) / 1000));
  if (elapsedSeconds < 60) return `${elapsedSeconds}s ago`;
  if (elapsedSeconds < 3600) return `${Math.floor(elapsedSeconds / 60)}m ago`;
  return `${Math.floor(elapsedSeconds / 3600)}h ago`;
}

function unavailableMessage(error: unknown) {
  return getUserMessage(error, "The endpoint could not be reached.");
}

export function DashboardMonitor({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  const { isAuthenticated } = useAuth();
  const [usage, setUsage] = useState<LoadState<UsageData>>({ status: "loading" });
  const [projects, setProjects] = useState<LoadState<{ total: number; items: ProjectResponse["items"] }>>({ status: "loading" });
  const [environments, setEnvironments] = useState<LoadState<EnvironmentResponse>>({ status: "ready", data: [] });
  const [projectFilter, setProjectFilter] = useState(readActiveProjectId);
  const [environmentFilter, setEnvironmentFilter] = useState(
    () => readActiveEnvironmentContext().environmentId,
  );
  const [readiness, setReadiness] = useState<"checking" | "ready" | "unavailable">("checking");
  const [readinessError, setReadinessError] = useState<string | null>(null);
  const [refreshedAt, setRefreshedAt] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [usageRange, setUsageRange] = useState<UsageRange>("24h");
  const [refreshSequence, setRefreshSequence] = useState(0);
  const usageRequest = useRef<AbortController | null>(null);

  useEffect(() => {
    function syncEnvironmentSelection() {
      const context = readActiveEnvironmentContext();
      setProjectFilter(context.projectId);
      setEnvironmentFilter(context.environmentId);
      setUsage({ status: "loading" });
    }
    window.addEventListener("pesaguard:active-context-changed", syncEnvironmentSelection);
    return () => window.removeEventListener("pesaguard:active-context-changed", syncEnvironmentSelection);
  }, []);

  const refreshUsage = useCallback(async () => {
    if (!isAuthenticated) {
      setUsage({ status: "unavailable", message: "Live usage data is unavailable in the unauthenticated frontend preview." });
      return;
    }
    if (!projectFilter || !environmentFilter) {
      setRefreshing(false);
      setUsage({ status: "unavailable", message: "Select an active project environment to view its usage." });
      return;
    }

    setRefreshing(true);
    usageRequest.current?.abort();
    const controller = new AbortController();
    usageRequest.current = controller;
    let timedOut = false;
    const timeout = window.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, 10_000);
    try {
      const to = new Date();
      const from = new Date(to.getTime() - usageRangeMs[usageRange]);
      const params = new URLSearchParams({
        from: from.toISOString(),
        to: to.toISOString(),
      });
      params.set("projectId", projectFilter);
      params.set("environmentId", environmentFilter);
      const response = await apiData<UsageResponse>(`/api/v1/usage?${params.toString()}`, { signal: controller.signal });
      if (!response.summary || !Array.isArray(response.points)) {
        throw new Error("The usage API returned an incomplete response.");
      }
      setUsage({ status: "ready", data: response });
      setRefreshedAt(new Date().toISOString());
    } catch (error) {
      if (!controller.signal.aborted || timedOut) {
        setUsage({ status: "unavailable", message: timedOut ? "The usage request timed out." : unavailableMessage(error) });
      }
    } finally {
      window.clearTimeout(timeout);
      if (usageRequest.current === controller) {
        usageRequest.current = null;
        setRefreshing(false);
      }
    }
  }, [isAuthenticated, usageRange, projectFilter, environmentFilter]);

  useEffect(() => {
    if (!isAuthenticated || !projectFilter) {
      setEnvironments({ status: "ready", data: [] });
      return;
    }

    let active = true;
    const controller = new AbortController();
    setEnvironments({ status: "loading" });
    apiData<EnvironmentResponse>(`/api/v1/projects/${encodeURIComponent(projectFilter)}/environments`, {
      signal: controller.signal,
    }).then((items) => {
      if (!Array.isArray(items)) throw new Error("The environments API returned an invalid response.");
      if (active) {
        setEnvironments({ status: "ready", data: items });
        const savedEnvironmentId = readActiveEnvironmentId(projectFilter);
        const selected = items.find((environment) => environment.id === savedEnvironmentId)
          ?? items.find((environment) => environment.type === "SANDBOX" && environment.status === "ACTIVE")
          ?? items.find((environment) => environment.status === "ACTIVE")
          ?? items[0];
        if (selected && selected.id !== environmentFilter) {
          setEnvironmentFilter(selected.id);
          const project = projects.status === "ready"
            ? projects.data.items.find((item) => item.id === projectFilter)
            : undefined;
          if (project) setActiveEnvironment(project, selected);
        }
      }
    }).catch((error: unknown) => {
      if (active && !controller.signal.aborted) {
        setEnvironments({ status: "unavailable", message: unavailableMessage(error) });
      }
    });
    return () => {
      active = false;
      controller.abort();
    };
  }, [isAuthenticated, projectFilter]);

  useEffect(() => {
    let active = true;

    async function checkReadiness() {
      const controller = new AbortController();
      const timeout = window.setTimeout(() => controller.abort(), 10_000);
      try {
        await apiFetch("/health/ready", { signal: controller.signal });
        if (active) {
          setReadiness("ready");
          setReadinessError(null);
        }
      } catch (error) {
        if (active) {
          setReadiness("unavailable");
          setReadinessError(unavailableMessage(error));
        }
      } finally {
        window.clearTimeout(timeout);
      }
    }

    if (!isAuthenticated) {
      setProjects({ status: "unavailable", message: "Live project data is unavailable in the unauthenticated frontend preview." });
      setProjectFilter("");
      setEnvironmentFilter("");
      setEnvironments({ status: "ready", data: [] });
      setReadiness("checking");
      void checkReadiness();
      const readinessTimer = window.setInterval(() => { void checkReadiness(); }, 30_000);
      return () => {
        active = false;
        window.clearInterval(readinessTimer);
      };
    }

    const workspaceController = new AbortController();
    const workspaceTimeout = window.setTimeout(() => workspaceController.abort(), 10_000);

    async function loadWorkspace() {
      try {
        const page = await apiData<ProjectResponse>("/api/v1/projects?page=0&size=100", {
          signal: workspaceController.signal,
        });
        if (!active) return;
        if (!Array.isArray(page?.items) || typeof page.totalElements !== "number") {
          setProjects({ status: "unavailable", message: "The projects API returned an incomplete response." });
        } else {
          if (!page.items.some((project) => project.id === projectFilter)) {
            const activeProjectId = readActiveProjectId();
            const selected = page.items.find((project) => project.id === activeProjectId) ?? page.items[0];
            setProjectFilter(selected?.id ?? "");
            setEnvironmentFilter("");
            if (selected) setActiveProject(selected);
          }
          setProjects({
            status: "ready",
            data: {
              total: page.totalElements,
              items: page.items,
            },
          });
        }
      } catch (error) {
        if (active) {
          setProjects({
            status: "unavailable",
            message: workspaceController.signal.aborted
              ? "The projects request timed out."
              : unavailableMessage(error),
          });
        }
      } finally {
        window.clearTimeout(workspaceTimeout);
      }
    }

    void loadWorkspace();
    void refreshUsage();
    void checkReadiness();
    const usageTimer = window.setInterval(() => { void refreshUsage(); }, 30_000);
    const readinessTimer = window.setInterval(() => { void checkReadiness(); }, 30_000);

    return () => {
      active = false;
      usageRequest.current?.abort();
      workspaceController.abort();
      window.clearTimeout(workspaceTimeout);
      window.clearInterval(usageTimer);
      window.clearInterval(readinessTimer);
    };
  }, [isAuthenticated, refreshUsage, refreshSequence]);

  const latestPoint = useMemo(() => {
    if (usage.status !== "ready" || usage.data.points.length === 0) return null;
    return usage.data.points.reduce((latest, point) =>
      Date.parse(point.windowStart) > Date.parse(latest.windowStart) ? point : latest,
    );
  }, [usage]);

  const usageRangeLabel = usageRange === "24h"
    ? "Last 24 hours"
    : usageRange === "7d" ? "Last 7 days" : "Last 30 days";

  const metrics = [
    { label: "API requests", value: usage.status === "ready" ? displayMetric(usage.data.summary.totalRequests) : "—", footnote: usageRangeLabel, icon: Activity, status: usage.status === "ready" ? "live" as MetricStatus : "unavailable" as MetricStatus, destination: "usage" as PageId },
    { label: "Successful", value: usage.status === "ready" ? displayMetric(usage.data.summary.successfulRequests) : "—", footnote: "Accepted by the API", icon: CheckCircle2, status: usage.status === "ready" ? "live" as MetricStatus : "unavailable" as MetricStatus, destination: "usage" as PageId },
    { label: "Failed", value: usage.status === "ready" ? displayMetric(usage.data.summary.failedRequests) : "—", footnote: "Requests with an error response", icon: AlertTriangle, status: usage.status === "ready" ? "live" as MetricStatus : "unavailable" as MetricStatus, destination: "logs" as PageId },
    { label: "Error rate", value: usage.status === "ready" ? displayPercent(usage.data.summary.errorRate) : "—", footnote: "Based on returned request records", icon: Gauge, status: usage.status === "ready" ? "live" as MetricStatus : "unavailable" as MetricStatus, destination: "usage" as PageId },
    { label: "p95 latency", value: latestPoint ? displayMetric(latestPoint.p95LatencyMs, " ms") : "—", footnote: latestPoint ? "Latest usage interval" : "No latency interval returned", icon: Clock3, status: latestPoint ? "live" as MetricStatus : "unavailable" as MetricStatus },
    { label: "Projects", value: projects.status === "ready" ? displayMetric(projects.data.total) : "—", footnote: "Total in this workspace", icon: Activity, status: projects.status === "ready" ? "live" as MetricStatus : "unavailable" as MetricStatus, destination: "projects" as PageId },
  ];

  const readinessLabel = readiness === "ready"
    ? "API ready"
    : readiness === "unavailable"
      ? "API readiness unavailable"
      : "Checking API readiness";

  return (
    <div className="dashboard-monitor">
      <section className="panel live-status-panel" aria-label="Live platform status">
        <div className="live-status-main">
          <span className={`live-status-dot live-status-dot--${readiness}`} />
          <div><strong>{readinessLabel}</strong><small>{readiness === "unavailable" ? `Readiness check failed: ${readinessError ?? "unknown error"}. This alone does not confirm an incident.` : "System readiness · checked every 30 seconds."}</small></div>
        </div>
        <a className="text-button" href={externalLinks.status} target="_blank" rel="noreferrer">Public status page</a>
      </section>

      <div className="monitor-toolbar">
        <label className="monitor-range-control">
          <span>Usage range</span>
          <select aria-label="Usage time range" value={usageRange} onChange={(event) => setUsageRange(event.target.value as UsageRange)} disabled={!isAuthenticated || refreshing}>
            <option value="24h">Last 24 hours</option>
            <option value="7d">Last 7 days</option>
            <option value="30d">Last 30 days</option>
          </select>
        </label>
        <label className="monitor-range-control">
          <span>Project</span>
          <select aria-label="Project filter" value={projectFilter} onChange={(event) => {
            const project = projects.status === "ready"
              ? projects.data.items.find((item) => item.id === event.target.value)
              : undefined;
            if (!project) return;
            setProjectFilter(project.id);
            setEnvironmentFilter("");
            setActiveProject(project);
          }} disabled={!isAuthenticated || projects.status !== "ready" || refreshing}>
            {projects.status === "ready" && projects.data.items.map((project) => (
              <option key={project.id} value={project.id}>{project.name}</option>
            ))}
          </select>
        </label>
        <label className="monitor-range-control">
          <span>Environment</span>
          <select aria-label="Environment filter" value={environmentFilter} onChange={(event) => {
            const environment = environments.status === "ready"
              ? environments.data.find((item) => item.id === event.target.value)
              : undefined;
            const project = projects.status === "ready"
              ? projects.data.items.find((item) => item.id === projectFilter)
              : undefined;
            if (!environment || !project) return;
            setEnvironmentFilter(environment.id);
            setActiveEnvironment(project, environment);
          }} disabled={!projectFilter || environments.status !== "ready" || refreshing}>
            {environments.status === "ready" && environments.data.map((environment) => (
              <option key={environment.id} value={environment.id}>{environment.name}</option>
            ))}
          </select>
        </label>
        <span className="monitor-refresh-state">{refreshing ? "Refreshing…" : refreshedAt ? `Updated ${ageLabel(refreshedAt)}` : isAuthenticated ? "Waiting for workspace data" : "Sign in to load live data"}</span>
        <button className="text-button" type="button" disabled={!isAuthenticated || refreshing} onClick={() => setRefreshSequence((sequence) => sequence + 1)}>
          <RefreshCw size={14} aria-hidden="true" /> Refresh
        </button>
      </div>
      {usage.status === "unavailable" && isAuthenticated && (
        <div className="monitor-error" role="status">Usage metrics unavailable: {usage.message}</div>
      )}
      {environments.status === "unavailable" && projectFilter && (
        <div className="monitor-error" role="status">Environment options unavailable: {environments.message}</div>
      )}

      <section className="monitor-metrics" aria-label="Workspace dashboard metrics">
        {metrics.map(({ label, value, footnote, icon: Icon, status, destination }) => (
          <article className="panel monitor-metric" key={label}>
            <div className="monitor-metric-heading"><span>{label}</span><span className="stat-icon"><Icon size={16} aria-hidden="true" /></span></div>
            <strong className={`monitor-metric-value${value === "—" ? " monitor-metric-value--empty" : ""}`}>{value}</strong>
            <div className="monitor-metric-foot"><span className={`metric-source metric-source--${status}`} />{footnote}</div>
            {destination && <button className="monitor-metric-link" type="button" onClick={() => onNavigate(destination)}>View details</button>}
          </article>
        ))}
      </section>

    </div>
  );
}
