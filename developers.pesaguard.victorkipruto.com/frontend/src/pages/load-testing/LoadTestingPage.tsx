import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import {
  Activity,
  Copy,
  Gauge,
  LoaderCircle,
  Plus,
  RefreshCw,
  ShieldAlert,
  Square,
  X,
  XCircle,
} from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";
import { readActiveEnvironmentContext } from "../../lib/activeEnvironment";

type LoadTestStage = { durationSeconds: number; targetVus: number };
type LoadTestThreshold = { metric: string; operator: string; value: number };
type LoadTest = {
  id: string;
  name: string;
  lifecycle?: string;
  description?: string | null;
  projectId: string;
  environmentId: string;
  targetUrl: string;
  endpointPath: string;
  loadPattern: string;
  targetVus: number;
  targetRps?: number | null;
  maximumRps?: number | null;
  apiKeyId?: string | null;
  maximumDurationSeconds: number;
  status?: string | null;
  stages: LoadTestStage[];
  thresholds: LoadTestThreshold[];
  createdAt?: string | null;
  updatedAt?: string | null;
};
type LoadTestRun = {
  id: string;
  loadTestId: string;
  status: string;
  startedAt?: string | null;
  completedAt?: string | null;
  createdAt?: string | null;
};
type LoadTestRunListResponse = { items: LoadTestRun[]; totalElements?: number };
type MetricsSnapshot = {
  requestsPerSecond?: number | null;
  totalRequests?: number | null;
  successfulRequests?: number | null;
  failedRequests?: number | null;
  errorRatePercent?: number | null;
  p50LatencyMs?: number | null;
  p95LatencyMs?: number | null;
  p99LatencyMs?: number | null;
  updatedAt?: string | null;
};
type StageDraft = { durationSeconds: string; targetVus: string };
type ApiKeyOption = { id: string; name: string; prefix: string; status: string };
type TestDraft = {
  name: string;
  description: string;
  targetUrl: string;
  apiKeyId: string;
  loadPattern: string;
  stages: StageDraft[];
  p95LatencyMs: string;
  errorRatePercent: string;
};
type BusyAction = "save" | "start" | "stop" | "cancel" | "clone" | "";

const LOAD_TESTS_PATH = "/api/v1/load-tests";
const ACTIVE_RUN_STATUSES = new Set(["QUEUED", "PENDING", "STARTING", "PREPARING", "RUNNING", "IN_PROGRESS", "STOPPING"]);
const EMPTY_DRAFT: TestDraft = {
  name: "",
  description: "",
  targetUrl: "",
  apiKeyId: "",
  loadPattern: "RAMP",
  stages: [{ durationSeconds: "", targetVus: "" }],
  p95LatencyMs: "",
  errorRatePercent: "",
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function normalizeLoadTests(payload: unknown): LoadTest[] {
  const candidate = Array.isArray(payload) ? payload : asRecord(payload)?.items;
  if (!Array.isArray(candidate)) throw new Error("The load tests API returned an invalid list.");
  return candidate.filter((item): item is LoadTest => {
    const record = asRecord(item);
    return typeof record?.id === "string" && typeof record.name === "string"
      && typeof record.projectId === "string" && typeof record.environmentId === "string"
      && typeof record.targetUrl === "string" && typeof record.endpointPath === "string";
  }).map((test) => ({
    ...test,
    status: test.status ?? test.lifecycle ?? "READY",
    stages: Array.isArray(test.stages) ? test.stages : [],
    thresholds: Array.isArray(test.thresholds) ? test.thresholds : [],
  }));
}

function normalizeRuns(payload: unknown): LoadTestRun[] {
  const candidate = Array.isArray(payload) ? payload : asRecord(payload)?.items;
  if (!Array.isArray(candidate)) throw new Error("The run history API returned an invalid list.");
  return candidate.flatMap((item): LoadTestRun[] => {
    const record = asRecord(item);
    const id = typeof record?.runId === "string" ? record.runId : record?.id;
    if (typeof id !== "string" || typeof record?.status !== "string") return [];
    return [{
      id,
      loadTestId: typeof record.loadTestId === "string" ? record.loadTestId : "",
      status: record.status,
      startedAt: typeof record.startedAt === "string" ? record.startedAt : null,
      completedAt: typeof record.finishedAt === "string"
        ? record.finishedAt
        : typeof record.completedAt === "string" ? record.completedAt : null,
      createdAt: typeof record.createdAt === "string" ? record.createdAt : null,
    }];
  });
}

function numericField(record: Record<string, unknown>, key: string): number | null | undefined {
  const value = record[key];
  return typeof value === "number" && Number.isFinite(value) ? value : value === null ? null : undefined;
}

function firstNumeric(record: Record<string, unknown>, keys: string[]): number | null | undefined {
  for (const key of keys) {
    const value = numericField(record, key);
    if (value !== undefined) return value;
  }
  return undefined;
}

function mapMetrics(payload: unknown): MetricsSnapshot {
  const outer = asRecord(payload);
  const rows = Array.isArray(payload) ? payload : [payload];
  const latest = rows.map(asRecord).filter((record): record is Record<string, unknown> => record !== null).at(-1);
  const record = asRecord(latest?.values)
    ?? asRecord(outer?.summary)
    ?? asRecord(outer?.metrics)
    ?? outer
    ?? {};
  return {
    requestsPerSecond: firstNumeric(record, ["requestsPerSecond", "sustainedRps", "peakRps"]),
    totalRequests: firstNumeric(record, ["totalRequests", "httpReqs", "http_reqs"]),
    successfulRequests: firstNumeric(record, ["successfulRequests", "successfulReqs"]),
    failedRequests: firstNumeric(record, ["failedRequests", "failedReqs"]),
    errorRatePercent: firstNumeric(record, ["errorRatePercent", "errorRate", "httpReqFailed"]),
    p50LatencyMs: firstNumeric(record, ["p50LatencyMs", "p50Ms"]),
    p95LatencyMs: firstNumeric(record, ["p95LatencyMs", "p95Ms"]),
    p99LatencyMs: firstNumeric(record, ["p99LatencyMs", "p99Ms"]),
    updatedAt: typeof latest?.observedAt === "string"
      ? latest.observedAt
      : typeof outer?.recordedAt === "string"
        ? outer.recordedAt
        : typeof outer?.updatedAt === "string" ? outer.updatedAt : null,
  };
}

function formatDate(value?: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString();
}

function formatMetric(value: number | null | undefined, suffix = "") {
  return typeof value === "number" ? `${Number(value.toFixed(2))}${suffix}` : "—";
}

function isActive(status?: string | null) {
  return Boolean(status && ACTIVE_RUN_STATUSES.has(status.toUpperCase()));
}

function environmentIsProduction(test: LoadTest, context: ReturnType<typeof readActiveEnvironmentContext>) {
  return test.environmentId === context.environmentId
    && context.environmentType.toUpperCase() === "PRODUCTION";
}

function requiresExplicitRunConfirmation(test: LoadTest, context: ReturnType<typeof readActiveEnvironmentContext>) {
  if (environmentIsProduction(test, context)) return true;
  const selectedType = test.environmentId === context.environmentId ? context.environmentType.trim() : "";
  return !selectedType;
}

function routePathIsSafe(value: string) {
  return /^\/api\/v1\/[A-Za-z0-9._~/-]+$/.test(value)
    && !value.includes("..")
    && !value.includes("//");
}

function emptyMetrics(metrics?: MetricsSnapshot | null) {
  return !metrics || Object.entries(metrics)
    .filter(([key]) => key !== "updatedAt")
    .every(([, value]) => value === undefined || value === null);
}

function thresholdValue(thresholds: LoadTestThreshold[], metric: string) {
  return thresholds.find((threshold) => threshold.metric === metric)?.value ?? null;
}

function draftFromTest(test: LoadTest): TestDraft {
  return {
    name: test.name,
    description: test.description ?? "",
    targetUrl: test.endpointPath,
    apiKeyId: test.apiKeyId ?? "",
    loadPattern: test.loadPattern,
    stages: Array.isArray(test.stages) && test.stages.length
      ? test.stages.map((stage) => ({
        durationSeconds: String(stage.durationSeconds),
        targetVus: String(stage.targetVus),
      }))
      : [{ durationSeconds: "", targetVus: "" }],
    p95LatencyMs: thresholdValue(test.thresholds, "p95Ms") == null ? "" : String(thresholdValue(test.thresholds, "p95Ms")),
    errorRatePercent: thresholdValue(test.thresholds, "errorRate") == null ? "" : String(thresholdValue(test.thresholds, "errorRate")),
  };
}

export function LoadTestingPage() {
  const [context, setContext] = useState(readActiveEnvironmentContext);
  const [tests, setTests] = useState<LoadTest[]>([]);
  const [apiKeys, setApiKeys] = useState<ApiKeyOption[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [selectedTest, setSelectedTest] = useState<LoadTest | null>(null);
  const [runs, setRuns] = useState<LoadTestRun[]>([]);
  const [selectedRunId, setSelectedRunId] = useState("");
  const [selectedRun, setSelectedRun] = useState<LoadTestRun | null>(null);
  const [metrics, setMetrics] = useState<MetricsSnapshot | null>(null);
  const [results, setResults] = useState<MetricsSnapshot | null>(null);
  const [draft, setDraft] = useState<TestDraft>(EMPTY_DRAFT);
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshKey, setRefreshKey] = useState(0);
  const [busy, setBusy] = useState<BusyAction>("");
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [productionConfirmation, setProductionConfirmation] = useState(false);
  const [productionAcknowledged, setProductionAcknowledged] = useState(false);
  const [productionPhrase, setProductionPhrase] = useState("");

  useEffect(() => {
    const syncContext = () => setContext(readActiveEnvironmentContext());
    window.addEventListener("pesaguard:active-context-changed", syncContext);
    window.addEventListener("pesaguard:environment-selected", syncContext);
    window.addEventListener("pesaguard:project-selected", syncContext);
    return () => {
      window.removeEventListener("pesaguard:active-context-changed", syncContext);
      window.removeEventListener("pesaguard:environment-selected", syncContext);
      window.removeEventListener("pesaguard:project-selected", syncContext);
    };
  }, []);

  const reload = useCallback(() => setRefreshKey((key) => key + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    if (!context.projectId || !context.environmentId) {
      setApiKeys([]);
      setDraft((current) => ({ ...current, apiKeyId: "" }));
      return () => controller.abort();
    }
    apiData<ApiKeyOption[]>(
      `/api/v1/projects/${encodeURIComponent(context.projectId)}/environments/${encodeURIComponent(context.environmentId)}/api-keys`,
      { signal: controller.signal },
    )
      .then((payload) => {
        if (controller.signal.aborted) return;
        if (!Array.isArray(payload)) throw new Error("The API keys service returned an invalid list.");
        const usableKeys = payload.filter((key) => key.status === "ACTIVE");
        setApiKeys(usableKeys);
        setDraft((current) => ({
          ...current,
          apiKeyId: usableKeys.some((key) => key.id === current.apiKeyId)
            ? current.apiKeyId
            : usableKeys[0]?.id ?? "",
        }));
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setApiKeys([]);
          setError(getUserMessage(cause, "API keys could not be loaded for this environment."));
        }
      });
    return () => controller.abort();
  }, [context.environmentId, context.projectId]);

  useEffect(() => {
    const controller = new AbortController();
    if (!context.projectId || !context.environmentId) {
      setTests([]);
      setSelectedId("");
      setLoading(false);
      setError("");
      return () => controller.abort();
    }
    setLoading(true);
    setError("");
    const query = new URLSearchParams({
      projectId: context.projectId,
      environmentId: context.environmentId,
    });
    apiData<LoadTest[]>(`${LOAD_TESTS_PATH}?${query}`, { signal: controller.signal })
      .then((payload) => {
        if (controller.signal.aborted) return;
        const loaded = normalizeLoadTests(payload);
        setTests(loaded);
        const matching = loaded.filter((test) =>
          (!context.projectId || !test.projectId || test.projectId === context.projectId)
          && (!context.environmentId || !test.environmentId || test.environmentId === context.environmentId),
        );
        setSelectedId((current) => matching.some((test) => test.id === current) ? current : matching[0]?.id ?? "");
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setError(getUserMessage(cause, "Load tests could not be loaded."));
          setTests([]);
          setSelectedId("");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [context.environmentId, context.projectId, refreshKey]);

  const visibleTests = useMemo(() => tests.filter((test) =>
    (!context.projectId || !test.projectId || test.projectId === context.projectId)
    && (!context.environmentId || !test.environmentId || test.environmentId === context.environmentId),
  ), [context.environmentId, context.projectId, tests]);

  useEffect(() => {
    if (!selectedId) {
      setSelectedTest(null);
      setRuns([]);
      setSelectedRunId("");
      setSelectedRun(null);
      setMetrics(null);
      setResults(null);
      return;
    }
    const controller = new AbortController();
    setSelectedTest(visibleTests.find((test) => test.id === selectedId) ?? null);
    setSelectedRunId("");
    setSelectedRun(null);
    setMetrics(null);
    setResults(null);
    const encodedId = encodeURIComponent(selectedId);
    Promise.all([
      apiData<LoadTest>(`${LOAD_TESTS_PATH}/${encodedId}`, { signal: controller.signal }),
      apiData<LoadTestRunListResponse | LoadTestRun[]>(`${LOAD_TESTS_PATH}/${encodedId}/runs`, { signal: controller.signal }),
    ])
      .then(([test, runPayload]) => {
        if (controller.signal.aborted) return;
        if (!test || typeof test.id !== "string") throw new Error("The load test API returned an invalid configuration.");
        const runItems = normalizeRuns(runPayload);
        setSelectedTest(test);
        setDraft(draftFromTest(test));
        setRuns(runItems);
        setSelectedRunId(runItems[0]?.id ?? "");
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setError(getUserMessage(cause, "Load test details could not be loaded."));
          setRuns([]);
        }
      });
    return () => controller.abort();
  }, [selectedId, visibleTests]);

  useEffect(() => {
    if (!selectedId || !selectedRunId) {
      setSelectedRun(null);
      setMetrics(null);
      setResults(null);
      return;
    }
    let disposed = false;
    let inFlight = false;
    let shouldPoll = true;
    const path = `${LOAD_TESTS_PATH}/${encodeURIComponent(selectedId)}/runs/${encodeURIComponent(selectedRunId)}`;
    const poll = async () => {
      if (inFlight || disposed) return;
      inFlight = true;
      try {
        const [runPayload, metricPayload, resultPayload] = await Promise.all([
          apiData<unknown>(path),
          apiData<unknown>(`${path}/metrics`).catch(() => null),
          apiData<unknown>(`${path}/results`).catch(() => null),
        ]);
        if (disposed) return;
        const currentRun = normalizeRuns([runPayload])[0];
        if (!currentRun) throw new Error("The run detail API returned an invalid response.");
        shouldPoll = isActive(currentRun.status);
        setSelectedRun(currentRun);
        setRuns((current) => current.map((item) => item.id === currentRun.id ? currentRun : item));
        if (metricPayload) setMetrics(mapMetrics(metricPayload));
        if (resultPayload) setResults(mapMetrics(resultPayload));
      } catch (cause) {
        if (!disposed) setError(getUserMessage(cause, "Run details could not be loaded."));
      } finally {
        inFlight = false;
      }
    };
    void poll();
    const timer = window.setInterval(() => {
      if (shouldPoll) void poll();
    }, 5000);
    return () => {
      disposed = true;
      window.clearInterval(timer);
    };
  }, [selectedId, selectedRunId, refreshKey]);

  const saveTest = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    setMessage("");
    if (!context.projectId || !context.environmentId) {
      setError("Select a project and environment in the workspace header before configuring a load test.");
      return;
    }
    if (apiKeys.length > 0 && !draft.apiKeyId) {
      setError("Select an active API key for this environment.");
      return;
    }
    if (!routePathIsSafe(draft.targetUrl.trim())) {
      setError("Use a relative, supported /api/v1/... route only. Full URLs, query strings, fragments, and path traversal are not allowed.");
      return;
    }
    const stages = draft.stages.map((stage) => ({
      durationSeconds: Number(stage.durationSeconds),
      targetVus: Number(stage.targetVus),
    }));
    if (!draft.name.trim() || stages.some((stage) =>
      !Number.isFinite(stage.durationSeconds) || stage.durationSeconds < 1
      || !Number.isFinite(stage.targetVus) || stage.targetVus < 1 || stage.targetVus > 100000,
    )) {
      setError("Add a name and provide a duration and target virtual-user count from 1 to 100,000 for every stage.");
      return;
    }
    const maximumDurationSeconds = stages.reduce((total, stage) => total + stage.durationSeconds, 0);
    if (maximumDurationSeconds > 86400) {
      setError("The combined load-stage duration cannot exceed 24 hours.");
      return;
    }
    const p95LatencyMs = draft.p95LatencyMs.trim() ? Number(draft.p95LatencyMs) : null;
    const errorRatePercent = draft.errorRatePercent.trim() ? Number(draft.errorRatePercent) : null;
    if ((p95LatencyMs !== null && (!Number.isFinite(p95LatencyMs) || p95LatencyMs <= 0))
      || (errorRatePercent !== null && (!Number.isFinite(errorRatePercent) || errorRatePercent < 0 || errorRatePercent > 100))) {
      setError("Enter a positive p95 latency threshold and an error-rate threshold from 0 to 100.");
      return;
    }
    const thresholds = [
      ...(p95LatencyMs === null ? [] : [{ metric: "p95Ms", operator: "<=", value: p95LatencyMs }]),
      ...(errorRatePercent === null ? [] : [{ metric: "errorRate", operator: "<=", value: errorRatePercent }]),
    ];
    const body = {
      name: draft.name.trim(),
      description: draft.description.trim() || null,
      projectId: context.projectId,
      environmentId: context.environmentId,
      endpointPath: draft.targetUrl.trim(),
      httpMethod: "GET",
      contentType: "application/json",
      headers: {},
      requestBody: null,
      apiKeyId: draft.apiKeyId || null,
      loadPattern: draft.loadPattern,
      targetVus: Math.max(...stages.map((stage) => stage.targetVus)),
      targetRps: null,
      maximumRps: null,
      maximumDurationSeconds,
      stages,
      thresholds,
    };
    setBusy("save");
    try {
      const result = await apiData<LoadTest>(editing && selectedId
        ? `${LOAD_TESTS_PATH}/${encodeURIComponent(selectedId)}`
        : LOAD_TESTS_PATH, {
        method: editing && selectedId ? "PUT" : "POST",
        body: JSON.stringify(body),
      });
      setMessage(editing ? "Load test configuration updated." : "Load test configuration created.");
      setEditing(false);
      if (result?.id) setSelectedId(result.id);
      reload();
    } catch (cause) {
      setError(getUserMessage(cause, "Load test configuration could not be saved."));
    } finally {
      setBusy("");
    }
  };

  const beginEdit = () => {
    if (selectedTest) {
      setDraft(draftFromTest(selectedTest));
      setEditing(true);
      setError("");
      setMessage("");
    } else {
      setSelectedId("");
      setSelectedTest(null);
      setRuns([]);
      setSelectedRunId("");
      setSelectedRun(null);
      setDraft(EMPTY_DRAFT);
      setEditing(true);
      setError("");
      setMessage("");
    }
  };

  const startRun = async () => {
    if (!selectedTest) return;
    setProductionConfirmation(false);
    setProductionAcknowledged(false);
    setProductionPhrase("");
    setBusy("start");
    setError("");
    setMessage("");
    try {
      const created = await apiData<LoadTestRun>(
        `${LOAD_TESTS_PATH}/${encodeURIComponent(selectedTest.id)}/runs`,
        {
          method: "POST",
          body: JSON.stringify({
            productionConfirmationToken: isProduction ? "CONFIRM_PRODUCTION_LOAD_TEST" : null,
          }),
        },
      );
      const createdRun = normalizeRuns([created])[0];
      if (createdRun?.id) setSelectedRunId(createdRun.id);
      setMessage("Run start request accepted by the API.");
      reload();
    } catch (cause) {
      setError(getUserMessage(cause, "The run could not be started."));
    } finally {
      setBusy("");
    }
  };

  const confirmStart = () => {
    if (!selectedTest) return;
    if (requiresExplicitRunConfirmation(selectedTest, context)) {
      setProductionConfirmation(true);
      return;
    }
    if (window.confirm(`Start a load test for ${selectedTest.targetUrl}? Ensure the target is safe for repeated requests and has no financial or third-party side effects.`)) {
      void startRun();
    }
  };

  const runAction = async (action: "stop" | "cancel" | "clone") => {
    if (!selectedTest) return;
    const activeRun = selectedRun ?? runs.find((run) => run.id === selectedRunId) ?? null;
    if (action === "clone") {
      setBusy("clone");
      setError("");
      setMessage("");
      try {
        const created = await apiData<LoadTest>(
          `${LOAD_TESTS_PATH}/${encodeURIComponent(selectedTest.id)}/clone`,
          { method: "POST", body: JSON.stringify({}) },
        );
        setMessage("Configuration cloned.");
        if (created?.id) setSelectedId(created.id);
        reload();
      } catch (cause) {
        setError(getUserMessage(cause, "The configuration could not be cloned."));
      } finally {
        setBusy("");
      }
      return;
    }
    if (!activeRun) return;
    const label = action === "stop" ? "Stop" : "Cancel";
    if (!window.confirm(`${label} run ${activeRun.id}?`)) return;
    setBusy(action);
    setError("");
    setMessage("");
    try {
      await apiData<unknown>(
        `${LOAD_TESTS_PATH}/${encodeURIComponent(selectedTest.id)}/runs/${encodeURIComponent(activeRun.id)}/${action}`,
        { method: "POST", body: JSON.stringify({}) },
      );
      setMessage(`${label} request accepted by the API.`);
      reload();
    } catch (cause) {
      setError(getUserMessage(cause, `The run could not be ${action}ed.`));
    } finally {
      setBusy("");
    }
  };

  const isProduction = Boolean(selectedTest && environmentIsProduction(selectedTest, context));
  const confirmationPhrase = isProduction ? "RUN PRODUCTION" : "CONFIRM RUN";
  const activeRun = selectedRun ?? runs.find((run) => run.id === selectedRunId) ?? null;
  const canStop = Boolean(activeRun && ["RUNNING", "IN_PROGRESS", "STARTING", "PREPARING", "PAUSED"].includes(activeRun.status.toUpperCase()));
  const canCancel = Boolean(activeRun && ["QUEUED", "PENDING", "STARTING"].includes(activeRun.status.toUpperCase()));

  return (
    <>
      <PageHeader
        eyebrow="DEVELOPER TOOLS"
        title="Load Testing"
        description="Configure API load tests, review real run history, and monitor measured metrics."
      />

      <aside className="load-test-safety" role="note">
        <ShieldAlert size={18} aria-hidden="true" />
        <p><strong>Use safe targets only.</strong> Test only supported API routes intended for repeated requests. Do not target routes that move money, send messages, invoke third parties, or cause other financial or external side effects. Production runs require an additional explicit confirmation.</p>
      </aside>
      {!context.projectId || !context.environmentId
        ? <p className="workflow-hint load-test-context-warning" role="status">Select a project and environment in the workspace header to create a load test and scope this view.</p>
        : <p className="workflow-hint load-test-context">Current context: {context.projectName || "Selected project"} · {context.environmentName || "Selected environment"} ({context.environmentType || "environment type unavailable"})</p>}
      {error && <p className="workflow-error load-test-feedback" role="alert">{error}</p>}
      {message && <p className="workflow-success load-test-feedback" role="status">{message}</p>}

      <div className="load-test-layout">
        <section className="panel load-test-list-panel" aria-labelledby="load-test-list-title">
          <div className="panel-heading">
            <div><h2 id="load-test-list-title">Configurations</h2><p>{visibleTests.length} load tests in this context</p></div>
            <div className="load-test-heading-actions">
              <button className="button button--secondary" type="button" onClick={reload} disabled={loading} aria-label="Refresh load tests"><RefreshCw size={14} /></button>
              <button className="button button--primary" type="button" onClick={beginEdit}><Plus size={14} />New test</button>
            </div>
          </div>
          {loading ? <p className="load-test-empty" role="status"><LoaderCircle size={16} className="load-test-spin" />Loading load tests…</p>
            : visibleTests.length ? <ul className="load-test-list">
              {visibleTests.map((test) => <li key={test.id}>
                <button type="button" className={`load-test-list-item${selectedId === test.id ? " load-test-list-item--selected" : ""}`} onClick={() => { setSelectedId(test.id); setEditing(false); setError(""); setMessage(""); }}>
                  <span className="load-test-list-item-icon"><Gauge size={16} aria-hidden="true" /></span>
                  <span className="load-test-list-item-copy"><strong>{test.name}</strong><small>{test.targetUrl}</small></span>
                  <span className={`load-test-status load-test-status--${(test.status ?? "configured").toLowerCase()}`}>{test.status ?? "CONFIGURED"}</span>
                </button>
              </li>)}
            </ul> : <div className="load-test-empty"><Activity size={20} /><strong>No load tests found</strong><span>Create a configuration to start collecting real run results.</span></div>}
        </section>

        <div className="load-test-main">
          {editing && <section className="panel load-test-config-panel" aria-labelledby="load-test-config-title">
            <div className="panel-heading">
              <div><h2 id="load-test-config-title">{selectedTest ? "Edit configuration" : "New load test"}</h2><p>Configuration is sent to the load-test API; no requests are generated until a run is explicitly started.</p></div>
              <button className="icon-button" type="button" onClick={() => setEditing(false)} aria-label="Close configuration editor"><X size={17} /></button>
            </div>
            <ValidatedForm className="workflow-form load-test-form" onSubmit={(event) => void saveTest(event)}>
              <label>Name<input required maxLength={120} value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} /></label>
              <label>Description<input maxLength={500} value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.target.value })} /></label>
              <label className="load-test-target-field">Supported API route<input required value={draft.targetUrl} onChange={(event) => setDraft({ ...draft, targetUrl: event.target.value })} aria-describedby="load-test-route-hint" autoComplete="off" /><small id="load-test-route-hint">Relative path only, e.g. /api/v1/accounts. Full URLs and side-effecting routes are not allowed.</small></label>
              <label>API key<select value={draft.apiKeyId} onChange={(event) => setDraft({ ...draft, apiKeyId: event.target.value })}>
                <option value="">{apiKeys.length ? "Select an active API key" : "No active API keys"}</option>
                {apiKeys.map((key) => <option key={key.id} value={key.id}>{key.name} · {key.prefix}</option>)}
              </select><small>The selected key is used by the test worker; its secret is never displayed.</small></label>
              <label>Load pattern<select value={draft.loadPattern} onChange={(event) => setDraft({ ...draft, loadPattern: event.target.value })}>
                <option value="RAMP">Ramp</option><option value="SPIKE">Spike</option><option value="STRESS">Stress</option>
                <option value="SOAK">Soak</option><option value="CAPACITY">Capacity</option><option value="SMOKE">Smoke</option>
              </select></label>
              <fieldset className="load-test-stages">
                <legend>Load stages</legend>
                {draft.stages.map((stage, index) => <div className="load-test-stage-row" key={index}>
                  <span className="load-test-stage-label">Stage {index + 1}</span>
                  <label>Duration (seconds)<input type="number" min="1" step="1" required value={stage.durationSeconds} onChange={(event) => setDraft({ ...draft, stages: draft.stages.map((item, row) => row === index ? { ...item, durationSeconds: event.target.value } : item) })} /></label>
                  <label>Target virtual users<input type="number" min="1" max="100000" step="1" required value={stage.targetVus} onChange={(event) => setDraft({ ...draft, stages: draft.stages.map((item, row) => row === index ? { ...item, targetVus: event.target.value } : item) })} /></label>
                  <button className="icon-button" type="button" aria-label={`Remove stage ${index + 1}`} disabled={draft.stages.length === 1} onClick={() => setDraft({ ...draft, stages: draft.stages.filter((_, row) => row !== index) })}><X size={15} /></button>
                </div>)}
                <button className="button button--secondary load-test-add-stage" type="button" onClick={() => setDraft({ ...draft, stages: [...draft.stages, { durationSeconds: "", targetVus: "" }] })}><Plus size={14} />Add stage</button>
              </fieldset>
              <fieldset className="load-test-thresholds">
                <legend>Pass/fail thresholds <span>(optional)</span></legend>
                <label>Maximum p95 latency (ms)<input type="number" min="1" step="any" value={draft.p95LatencyMs} onChange={(event) => setDraft({ ...draft, p95LatencyMs: event.target.value })} /></label>
                <label>Maximum error rate (%)<input type="number" min="0" max="100" step="any" value={draft.errorRatePercent} onChange={(event) => setDraft({ ...draft, errorRatePercent: event.target.value })} /></label>
              </fieldset>
              <p className="workflow-hint load-test-form-context">Bound to {context.projectName || context.projectId || "no project"} · {context.environmentName || context.environmentId || "no environment"}. Credentials are managed by the platform and are never displayed here.</p>
              <div className="workflow-form-actions">
                <button className="button button--secondary" type="button" onClick={() => setEditing(false)}>Discard</button>
                <button className="button button--primary" type="submit" disabled={busy === "save"}>{busy === "save" ? "Saving…" : selectedTest ? "Save changes" : "Create configuration"}</button>
              </div>
            </ValidatedForm>
          </section>}

          {selectedTest && !editing ? <>
            <section className="panel load-test-overview-panel">
              <div className="panel-heading">
                <div><span className="load-test-eyebrow">CONFIGURATION</span><h2>{selectedTest.name}</h2><p>{selectedTest.description || "No description provided."}</p></div>
                <div className="load-test-heading-actions">
                  <button className="button button--secondary" type="button" onClick={() => void runAction("clone")} disabled={busy !== ""}><Copy size={14} />Clone</button>
                  <button className="button button--secondary" type="button" onClick={beginEdit} disabled={busy !== "" || runs.length > 0} title={runs.length > 0 ? "Clone this configuration to edit it without changing run history." : undefined}>Edit</button>
                  <button className="button button--primary" type="button" onClick={confirmStart} disabled={busy !== "" || !routePathIsSafe(selectedTest.endpointPath)}><Activity size={14} />{busy === "start" ? "Starting…" : "Start run"}</button>
                </div>
              </div>
              <dl className="load-test-config-summary">
                <dt>Target route</dt><dd><code>{selectedTest.targetUrl}</code></dd>
                <dt>Environment</dt><dd>{selectedTest.environmentId === context.environmentId ? context.environmentName || selectedTest.environmentId : selectedTest.environmentId} {isProduction && <span className="load-test-production-label">PRODUCTION</span>}</dd>
                <dt>Load pattern</dt><dd>{selectedTest.loadPattern}</dd>
                <dt>Load profile</dt><dd>{selectedTest.stages?.length ? selectedTest.stages.map((stage, index) => `Stage ${index + 1}: ${stage.targetVus} VUs for ${stage.durationSeconds}s`).join(" · ") : "No stages returned"}</dd>
                <dt>Thresholds</dt><dd>p95 {thresholdValue(selectedTest.thresholds, "p95Ms") ?? "—"} ms · errors {thresholdValue(selectedTest.thresholds, "errorRate") ?? "—"}%</dd>
              </dl>
              {isProduction && <p className="load-test-production-warning" role="note"><ShieldAlert size={15} />This configuration targets Production. Each run needs an additional confirmation.</p>}
            </section>

            <section className="panel load-test-runs-panel" aria-labelledby="load-test-runs-title">
              <div className="panel-heading"><div><h2 id="load-test-runs-title">Run history</h2><p>Run state, metrics, and results are fetched from the API.</p></div><button className="button button--secondary" type="button" onClick={reload} disabled={loading}><RefreshCw size={14} />Refresh</button></div>
              {runs.length ? <div className="load-test-run-list" role="list">
                {runs.map((run) => <button key={run.id} type="button" role="listitem" className={`load-test-run-row${selectedRunId === run.id ? " load-test-run-row--selected" : ""}`} onClick={() => setSelectedRunId(run.id)}>
                  <span><strong>{run.status}</strong><small>{formatDate(run.startedAt ?? run.createdAt)}</small></span><code>{run.id}</code><span>{run.completedAt ? `Finished ${formatDate(run.completedAt)}` : isActive(run.status) ? "In progress" : "—"}</span>
                </button>)}
              </div> : <p className="load-test-empty">No runs have been returned for this configuration.</p>}
            </section>

            {selectedRun && <section className="panel load-test-run-details" aria-labelledby="load-test-run-detail-title">
              <div className="panel-heading">
                <div><span className="load-test-eyebrow">RUN DETAIL</span><h2 id="load-test-run-detail-title">{selectedRun.status}</h2><p><code>{selectedRun.id}</code> · Started {formatDate(selectedRun.startedAt ?? selectedRun.createdAt)}</p></div>
                <div className="load-test-heading-actions">
                  {canStop && <button className="button button--secondary" type="button" onClick={() => void runAction("stop")} disabled={busy !== ""}><Square size={13} />{busy === "stop" ? "Stopping…" : "Stop"}</button>}
                  {canCancel && <button className="button button--danger" type="button" onClick={() => void runAction("cancel")} disabled={busy !== ""}><XCircle size={14} />{busy === "cancel" ? "Cancelling…" : "Cancel"}</button>}
                </div>
              </div>
              {isActive(selectedRun.status) && <p className="load-test-polling-note" role="status"><LoaderCircle size={14} className="load-test-spin" />Actual run metrics refresh every 5 seconds while the API reports an active run.</p>}
              <div className="load-test-metric-grid">
                <MetricCard label="Requests / sec" value={metrics?.requestsPerSecond} suffix="" />
                <MetricCard label="Requests" value={metrics?.totalRequests} />
                <MetricCard label="Successful" value={metrics?.successfulRequests} />
                <MetricCard label="Failed" value={metrics?.failedRequests} />
                <MetricCard label="Error rate" value={metrics?.errorRatePercent} suffix="%" />
                <MetricCard label="p50 latency" value={metrics?.p50LatencyMs} suffix=" ms" />
                <MetricCard label="p95 latency" value={metrics?.p95LatencyMs} suffix=" ms" />
                <MetricCard label="p99 latency" value={metrics?.p99LatencyMs} suffix=" ms" />
              </div>
              {emptyMetrics(metrics) && <p className="load-test-empty load-test-no-metrics">No metric values have been returned by the API for this run yet.</p>}
              <div className="load-test-results">
                <h3>Final results</h3>
                {results && !emptyMetrics(results) ? <dl className="load-test-result-summary">
                  {results.totalRequests != null && <><dt>Total requests</dt><dd>{results.totalRequests}</dd></>}
                  {results.successfulRequests != null && <><dt>Successful requests</dt><dd>{results.successfulRequests}</dd></>}
                  {results.failedRequests != null && <><dt>Failed requests</dt><dd>{results.failedRequests}</dd></>}
                  {results.errorRatePercent != null && <><dt>Error rate</dt><dd>{formatMetric(results.errorRatePercent, "%")}</dd></>}
                  {results.p95LatencyMs != null && <><dt>p95 latency</dt><dd>{formatMetric(results.p95LatencyMs, " ms")}</dd></>}
                  {results.updatedAt && <><dt>Updated</dt><dd>{formatDate(results.updatedAt)}</dd></>}
                </dl> : <p className="workflow-hint">No final result values have been returned.</p>}
                {metrics?.updatedAt && <p className="workflow-hint">Metrics updated {formatDate(metrics.updatedAt)}.</p>}
              </div>
            </section>}
          </> : !editing && <section className="panel load-test-empty-panel">
            <div className="load-test-empty"><Gauge size={24} /><strong>{visibleTests.length ? "Select a load test" : "No configuration selected"}</strong><span>Choose an existing configuration or create one to inspect run history and measured metrics.</span></div>
          </section>}
        </div>
      </div>

      {productionConfirmation && selectedTest && <div className="load-test-dialog-backdrop" role="presentation">
        <section className="load-test-confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="load-test-confirm-title" aria-describedby="load-test-confirm-copy">
          <div className="panel-heading"><div><h2 id="load-test-confirm-title">{isProduction ? "Confirm Production load test" : "Confirm load test run"}</h2><p>{isProduction ? "This sends real repeated requests to a Production API route." : "The environment is not classified; verify the target before starting real repeated requests."}</p></div><button className="icon-button" type="button" onClick={() => setProductionConfirmation(false)} aria-label="Close run confirmation"><XCircle size={17} /></button></div>
          <p id="load-test-confirm-copy" className="load-test-confirm-target">Target: <code>{selectedTest.targetUrl}</code></p>
          <p className="load-test-production-warning"><ShieldAlert size={15} />Proceed only if this supported route is safe under repeated requests and cannot cause financial or third-party side effects.</p>
          <label className="load-test-confirm-check"><input type="checkbox" checked={productionAcknowledged} onChange={(event) => setProductionAcknowledged(event.target.checked)} />{isProduction ? "I understand this is a Production run and I am authorized to start it." : "I verified the target environment and am authorized to start this run."}</label>
          <label className="workflow-form load-test-confirm-phrase">Type <code>{confirmationPhrase}</code> to continue<input autoComplete="off" value={productionPhrase} onChange={(event) => setProductionPhrase(event.target.value)} /></label>
          <div className="workflow-form-actions">
            <button className="button button--secondary" type="button" onClick={() => setProductionConfirmation(false)}>Do not run</button>
            <button className="button button--danger" type="button" disabled={!productionAcknowledged || productionPhrase !== confirmationPhrase || busy !== ""} onClick={() => void startRun()}>{busy === "start" ? "Starting…" : isProduction ? "Confirm Production run" : "Confirm run"}</button>
          </div>
        </section>
      </div>}
    </>
  );
}

function MetricCard({ label, value, suffix = "" }: { label: string; value?: number | null; suffix?: string }) {
  return <div className="load-test-metric-card"><span>{label}</span><strong>{formatMetric(value, suffix)}</strong></div>;
}
