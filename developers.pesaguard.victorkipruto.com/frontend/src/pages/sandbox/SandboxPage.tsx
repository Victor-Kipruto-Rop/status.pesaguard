import { getUserMessage } from "../../lib/errors";
import { useEffect, useState } from "react";
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  Check,
  Clock3,
  Copy,
  Database,
  FlaskConical,
  Play,
  RotateCcw,
  ShieldCheck,
  Terminal,
  Webhook,
} from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";
import { copyTextToClipboard } from "../../lib/clipboard";

type SandboxStatus = "PROVISIONING" | "ACTIVE" | "SUSPENDED" | "EXPIRED" | "DELETED";
type SandboxTab = "Laboratory" | "Scenario simulator" | "Execution history";
type ScenarioId =
  | "SUCCESSFUL_PAYMENT"
  | "FAILED_PAYMENT"
  | "TIMEOUT"
  | "DUPLICATE_REQUEST"
  | "INVALID_CREDENTIAL"
  | "INSUFFICIENT_FUNDS"
  | "WEBHOOK_FAILURE"
  | "NETWORK_FAILURE"
  | "TRANSACTION_REVERSAL";

interface SandboxInstance {
  id: string;
  projectId: string;
  environmentId: string;
  environmentType: string;
  name: string;
  description?: string;
  status: SandboxStatus;
  expiresAt?: string;
  resetCount: number;
  createdAt?: string;
}

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
}

interface Execution {
  id: string;
  environmentType: string;
  kind: string;
  method?: string;
  path?: string;
  statusCode?: number;
  outcome: string;
  durationMs?: number;
  responseExcerpt?: string;
  createdAt?: string;
  scenario?: ScenarioId;
}

const scenarios: { id: ScenarioId; label: string; description: string; expected: string; group: string }[] = [
  { id: "SUCCESSFUL_PAYMENT", label: "Successful payment", description: "Create a test transaction and return an accepted payment response.", expected: "201 · payment accepted", group: "Payments" },
  { id: "FAILED_PAYMENT", label: "Failed payment", description: "Exercise a declined payment and error response handling.", expected: "402 · payment failed", group: "Payments" },
  { id: "TIMEOUT", label: "Timeout", description: "Return a simulated gateway timeout without delaying or hanging a request.", expected: "504 · gateway timeout", group: "Resilience" },
  { id: "DUPLICATE_REQUEST", label: "Duplicate request", description: "Model an idempotency conflict for an already processed request.", expected: "409 · duplicate request", group: "Resilience" },
  { id: "INVALID_CREDENTIAL", label: "Invalid credential", description: "Test authentication error handling with a deliberately invalid credential scenario.", expected: "401 · invalid credential", group: "Security" },
  { id: "INSUFFICIENT_FUNDS", label: "Insufficient funds", description: "Return the insufficient-funds payment failure.", expected: "402 · insufficient funds", group: "Payments" },
  { id: "WEBHOOK_FAILURE", label: "Webhook failure", description: "Model a failed delivery response without contacting a webhook destination.", expected: "502 · delivery failed", group: "Events" },
  { id: "NETWORK_FAILURE", label: "Network failure", description: "Model an upstream connectivity failure as a deterministic response.", expected: "503 · upstream unavailable", group: "Resilience" },
  { id: "TRANSACTION_REVERSAL", label: "Transaction reversal", description: "Create a simulated reversal result for a completed test transaction.", expected: "200 · reversed", group: "Payments" },
];

const scenarioById = new Map(scenarios.map((scenario) => [scenario.id, scenario]));

export function SandboxPage() {
  const { isAuthenticated } = useAuth();
  const [activeTab, setActiveTab] = useState<SandboxTab>("Laboratory");
  const [sandboxes, setSandboxes] = useState<SandboxInstance[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [selectedSandboxId, setSelectedSandboxId] = useState("");
  const [executions, setExecutions] = useState<Execution[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [projectId, setProjectId] = useState("");
  const [environmentId, setEnvironmentId] = useState("");
  const [sandboxName, setSandboxName] = useState("");
  const [scenarioId, setScenarioId] = useState<ScenarioId>("SUCCESSFUL_PAYMENT");
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState("");

  const selectedSandbox = sandboxes.find((sandbox) => sandbox.id === selectedSandboxId);
  const projectEnvironments = environments.filter((environment) => environment.projectId === projectId && environment.type === "SANDBOX" && environment.status === "ACTIVE");
  const successfulRuns = executions.filter((execution) => execution.outcome === "SUCCEEDED").length;

  async function loadSandboxWorkspace() {
    if (!isAuthenticated) {
      setSandboxes([]);
      setProjects([]);
      setEnvironments([]);
      return;
    }
    setLoading(true);
    setError("");
    try {
      const [sandboxPayload, projectPayload] = await Promise.all([
        apiData<SandboxInstance[]>("/api/v1/sandboxes"),
        apiData<{ items: Project[] }>("/api/v1/projects?page=0&size=100"),
      ]);
      const nextSandboxes = sandboxPayload;
      const nextProjects = projectPayload.items;
      const environmentPayloads = await Promise.all(nextProjects.map((project) =>
        apiData<Environment[]>(`/api/v1/projects/${encodeURIComponent(project.id)}/environments`)));
      const nextEnvironments = environmentPayloads.flat();
      setSandboxes(nextSandboxes);
      setProjects(nextProjects);
      setEnvironments(nextEnvironments);
      setSelectedSandboxId((current) => nextSandboxes.some((sandbox) => sandbox.id === current)
        ? current
        : nextSandboxes[0]?.id ?? "");
      const firstProject = nextProjects[0]?.id ?? "";
      setProjectId((current) => nextProjects.some((project) => project.id === current) ? current : firstProject);
      setError("");
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not load sandbox resources."));
    } finally {
      setLoading(false);
    }
  }

  async function loadExecutions(sandboxId: string) {
    if (!isAuthenticated || !sandboxId) {
      setExecutions([]);
      return;
    }
    try {
      const payload = await apiData<Execution[]>(`/api/v1/sandboxes/${encodeURIComponent(sandboxId)}/executions?limit=100`);
      setExecutions(payload);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not load sandbox execution history."));
    }
  }

  useEffect(() => {
    void loadSandboxWorkspace();
  }, [isAuthenticated]);

  useEffect(() => {
    if (selectedSandboxId) void loadExecutions(selectedSandboxId);
  }, [selectedSandboxId, isAuthenticated]);

  useEffect(() => {
    if (projectEnvironments.some((environment) => environment.id === environmentId)) return;
    setEnvironmentId(projectEnvironments[0]?.id ?? "");
  }, [projectId, environments]);

  async function createSandbox() {
    if (!projectId || !environmentId || !sandboxName.trim()) {
      setError("Choose a project and active sandbox environment, and enter a sandbox name.");
      return;
    }
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const created = await apiData<SandboxInstance>("/api/v1/sandboxes", {
        method: "POST",
        body: JSON.stringify({ environmentId, name: sandboxName.trim(), description: "PesaGuard developer scenario laboratory", ttl: "P7D" }),
      });
      const active = created.status === "PROVISIONING"
        ? await apiData<SandboxInstance>(`/api/v1/sandboxes/${encodeURIComponent(created.id)}/activate`, { method: "POST" })
        : created;
      setSandboxes((current) => [active, ...current.filter((sandbox) => sandbox.id !== active.id)]);
      setSelectedSandboxId(active.id);
      setNotice(`Sandbox “${active.name}” is ${active.status.toLowerCase()} and pinned to a SANDBOX environment.`);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not create the sandbox."));
    } finally {
      setSaving(false);
    }
  }

  async function lifecycleAction(action: "reset" | "suspend" | "resume" | "activate") {
    if (!selectedSandbox) return;
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const nextSandbox = await apiData<SandboxInstance>(
        `/api/v1/sandboxes/${encodeURIComponent(selectedSandbox.id)}/${action}`,
        { method: "POST" },
      );
      setSandboxes((current) => current.map((sandbox) => sandbox.id === nextSandbox.id ? nextSandbox : sandbox));
      setNotice(action === "reset"
        ? "Sandbox data reset. Lifecycle history and execution records are retained by the service."
        : `Sandbox ${action} request completed. Current state: ${nextSandbox.status}.`);
      if (action === "reset") await loadExecutions(nextSandbox.id);
    } catch (requestError) {
      setError(getUserMessage(requestError, `Sandbox ${action} failed.`));
    } finally {
      setSaving(false);
    }
  }

  async function runScenario() {
    const scenario = scenarioById.get(scenarioId);
    if (!scenario) return;
    if (!isAuthenticated) {
      setError("Sign in to run a scenario against a saved sandbox.");
      return;
    }
    if (!selectedSandbox) {
      setError("Select or create a sandbox instance before running a scenario.");
      return;
    }
    const amountValue = Number(amount);
    if (!Number.isInteger(amountValue) || amountValue < 1 || amountValue > 100_000_000) {
      setError("Amount must be an integer from 1 to 100,000,000 minor units.");
      return;
    }
    if (!/^[A-Z]{3}$/.test(currency)) {
      setError("Use a three-letter uppercase currency code, such as KES.");
      return;
    }
    setError("");
    setNotice("");
    if (selectedSandbox.status !== "ACTIVE") {
      setError("Activate an active sandbox before running a scenario.");
      return;
    }
    setRunning(true);
    try {
      const execution = await apiData<Execution>(
        `/api/v1/sandboxes/${encodeURIComponent(selectedSandbox.id)}/simulate`,
        { method: "POST", body: JSON.stringify({ scenario: scenario.id, amount: amountValue, currency }) },
      );
      setExecutions((current) => [execution, ...current.filter((item) => item.id !== execution.id)]);
      setNotice(`${scenario.label} executed in the isolated sandbox. Recorded HTTP status ${execution.statusCode ?? "—"}; execution outcome ${execution.outcome}.`);
      setActiveTab("Execution history");
    } catch (requestError) {
      setError(getUserMessage(requestError, "Scenario execution failed."));
    } finally {
      setRunning(false);
    }
  }

  async function copyExecution(execution: Execution) {
    try {
      await copyTextToClipboard(execution.responseExcerpt ?? "");
      setNotice("Execution response copied.");
    } catch {
      setError("Clipboard access was denied. Select and copy the response manually.");
    }
  }

  const tabs: SandboxTab[] = ["Laboratory", "Scenario simulator", "Execution history"];

  return (
    <>
      <PageHeader eyebrow="BUILD · SAFE TEST ENVIRONMENT" title="Sandbox laboratory" description="Run scenarios against saved sandbox instances and inspect persisted execution history." action={<button className="button button--secondary" onClick={() => void loadSandboxWorkspace()} disabled={loading}><RotateCcw size={14} />{loading ? "Refreshing…" : "Refresh sandbox"}</button>} />
      <div className="preview-notice sandbox-notice"><span className="notice-icon"><FlaskConical size={15} /></span><p><strong>Production isolation enforced</strong> — Every scenario requires an active, persisted sandbox instance pinned to a SANDBOX environment. Executions and history are saved by the system.</p><span className="preview-badge">Sandbox API</span></div>
      {error && <div className="sandbox-alert sandbox-alert--error" role="alert"><AlertTriangle size={15} />{error}</div>}
      {notice && <div className="sandbox-alert sandbox-alert--success" role="status"><Check size={15} />{notice}</div>}

      <section className="sandbox-summary-grid">
        <SummaryCard label="Sandbox instances" value={String(sandboxes.length)} caption="Tenant-scoped" icon={<Database size={15} />} />
        <SummaryCard label="Scenario runs" value={String(executions.length)} caption={`${successfulRuns} completed runs`} icon={<Play size={15} />} />
        <SummaryCard label="Environment guard" value="Sandbox only" caption="No configurable remote target" icon={<ShieldCheck size={15} />} />
      </section>

      <div className="sandbox-tabs" role="tablist" aria-label="Sandbox laboratory sections">
        {tabs.map((tab) => <button key={tab} role="tab" aria-selected={activeTab === tab} className={activeTab === tab ? "sandbox-tab--active" : ""} onClick={() => setActiveTab(tab)}>{tab === "Laboratory" ? <FlaskConical size={14} /> : tab === "Scenario simulator" ? <Play size={14} /> : <Activity size={14} />}{tab}</button>)}
      </div>

      {activeTab === "Laboratory" && <section className="sandbox-laboratory-layout">
        <div className="panel sandbox-instance-panel">
          <div className="panel-heading"><div><h2>Sandbox instances</h2><p>Instances are isolated to an active sandbox environment.</p></div><span className="table-tag">SYSTEM</span></div>
          {sandboxes.length > 0 && <label className="sandbox-field">Selected sandbox<select aria-label="Selected sandbox instance" value={selectedSandboxId} onChange={(event) => setSelectedSandboxId(event.target.value)}>{sandboxes.map((sandbox) => <option key={sandbox.id} value={sandbox.id}>{sandbox.name} · {sandbox.status}</option>)}</select></label>}
          {selectedSandbox ? <div className="sandbox-instance-detail">
            <div className="sandbox-instance-status"><span className={`sandbox-status-dot sandbox-status-dot--${selectedSandbox.status.toLowerCase()}`} /><strong>{selectedSandbox.status}</strong><span>·</span><code>{selectedSandbox.environmentType}</code></div>
            <h3>{selectedSandbox.name}</h3><p>{selectedSandbox.description || "No description provided."}</p>
            <dl><div><dt>Instance ID</dt><dd>{selectedSandbox.id}</dd></div><div><dt>Environment ID</dt><dd>{selectedSandbox.environmentId}</dd></div><div><dt>Expires</dt><dd>{selectedSandbox.expiresAt ? new Date(selectedSandbox.expiresAt).toLocaleString() : "No expiry provided"}</dd></div><div><dt>Reset count</dt><dd>{selectedSandbox.resetCount}</dd></div></dl>
            <div className="sandbox-lifecycle-actions">
              {selectedSandbox.status === "PROVISIONING" && <button className="button button--primary" disabled={saving} onClick={() => void lifecycleAction("activate")}><Play size={13} />Activate</button>}
              {selectedSandbox.status === "ACTIVE" && <button className="button button--secondary" disabled={saving} onClick={() => void lifecycleAction("suspend")}><Clock3 size={13} />Suspend</button>}
              {selectedSandbox.status === "SUSPENDED" && <button className="button button--primary" disabled={saving} onClick={() => void lifecycleAction("resume")}><Play size={13} />Resume</button>}
              {selectedSandbox.status === "EXPIRED" && <span className="sandbox-readonly-label">Expired instances cannot resume.</span>}
              {selectedSandbox.status !== "DELETED" && <button className="button button--secondary" disabled={saving} onClick={() => void lifecycleAction("reset")}><RotateCcw size={13} />Reset data</button>}
            </div>
          </div> : <div className="sandbox-empty-state"><Database size={20} /><strong>No sandbox instances yet</strong><p>Create an isolated instance from one of your active SANDBOX environments below.</p></div>}
        </div>
        <div className="panel sandbox-create-panel">
          <div className="panel-heading"><div><h2>Create a sandbox instance</h2><p>Creates a seven-day instance pinned to an existing sandbox environment.</p></div><span className="table-tag">SANDBOX ONLY</span></div>
          <label className="sandbox-field">Project<select aria-label="Sandbox project" value={projectId} onChange={(event) => setProjectId(event.target.value)} disabled={loading}><option value="">Select project</option>{projects.map((project) => <option key={project.id} value={project.id}>{project.name} · {project.status}</option>)}</select></label>
          <label className="sandbox-field">Active sandbox environment<select aria-label="Sandbox environment" value={environmentId} onChange={(event) => setEnvironmentId(event.target.value)} disabled={loading || projectEnvironments.length === 0}><option value="">Select sandbox environment</option>{projectEnvironments.map((environment) => <option key={environment.id} value={environment.id}>{environment.name}</option>)}</select></label>
          {isAuthenticated && projectId && projectEnvironments.length === 0 && <p className="sandbox-inline-note">This project has no active SANDBOX environment. Create or activate one before provisioning a sandbox.</p>}
          <label className="sandbox-field">Instance name<input aria-label="Sandbox instance name" placeholder="Enter a name for this sandbox" value={sandboxName} maxLength={120} onChange={(event) => setSandboxName(event.target.value)} /></label>
          <button className="button button--primary sandbox-create-button" disabled={!projectId || !environmentId || saving} onClick={() => void createSandbox()}><Database size={14} />{saving ? "Working…" : "Create isolated sandbox"}</button>
        </div>
      </section>}

      {activeTab === "Scenario simulator" && <section className="sandbox-simulator-layout">
        <div className="panel sandbox-scenario-picker">
          <div className="panel-heading"><div><h2>Choose a scenario</h2><p>Nine deterministic cases; no external network destinations.</p></div><span className="table-tag">FIXED SCENARIOS</span></div>
          <div className="sandbox-scenario-list">{scenarios.map((scenario) => <button className={`sandbox-scenario-card${scenario.id === scenarioId ? " sandbox-scenario-card--selected" : ""}`} key={scenario.id} onClick={() => setScenarioId(scenario.id)} aria-pressed={scenarioId === scenario.id}><span className="sandbox-scenario-icon">{scenario.group === "Payments" ? <Database size={14} /> : scenario.group === "Security" ? <ShieldCheck size={14} /> : scenario.group === "Events" ? <Webhook size={14} /> : <Activity size={14} />}</span><span><strong>{scenario.label}</strong><small>{scenario.description}</small></span><code>{scenario.expected}</code></button>)}</div>
        </div>
        <aside className="panel sandbox-run-panel">
          <div className="panel-heading"><div><h2>Configure test run</h2><p>Uses the selected isolated sandbox and stores an execution record.</p></div><Terminal size={15} /></div>
          <label className="sandbox-field">Sandbox instance<select aria-label="Scenario sandbox instance" value={selectedSandboxId} onChange={(event) => setSelectedSandboxId(event.target.value)} disabled={!sandboxes.length}><option value="">Select saved sandbox</option>{sandboxes.map((sandbox) => <option key={sandbox.id} value={sandbox.id}>{sandbox.name} · {sandbox.status}</option>)}</select></label>
          <label className="sandbox-field">Amount (minor units)<input type="number" min="1" max="100000000" placeholder="Enter an amount" value={amount} onChange={(event) => setAmount(event.target.value)} /></label>
          <label className="sandbox-field">Currency<input value={currency} maxLength={3} placeholder="KES" onChange={(event) => setCurrency(event.target.value.toUpperCase())} /></label>
          <div className="sandbox-safety-card"><ShieldCheck size={14} /><span><strong>{selectedSandbox?.status === "ACTIVE" ? "Isolated system execution" : "Active sandbox required"}</strong><small>Scenario runs use the authenticated sandbox API and are saved to execution history. Production and arbitrary hosts cannot be targeted.</small></span></div>
          <button className="button button--primary sandbox-run-button" disabled={running || !selectedSandbox || selectedSandbox.status !== "ACTIVE"} onClick={() => void runScenario()}><Play size={14} />{running ? "Running scenario…" : `Run ${scenarioById.get(scenarioId)?.label ?? "scenario"}`}<ArrowRight size={14} /></button>
          {selectedSandboxId && selectedSandbox?.status !== "ACTIVE" && <p className="sandbox-inline-note">The selected instance must be ACTIVE before a real scenario can run.</p>}
        </aside>
      </section>}

      {activeTab === "Execution history" && <section className="panel sandbox-history-panel">
        <div className="panel-heading"><div><h2>Execution history</h2><p>{selectedSandbox ? `Recorded runs for ${selectedSandbox.name}.` : "Select a sandbox instance to view its persisted execution history."}</p></div><span className="table-tag">SANDBOX API</span></div>
        {loading ? <div className="sandbox-empty-state"><Activity size={18} /><strong>Loading sandbox execution history…</strong></div> : executions.length ? <div className="table-scroll"><table className="data-table sandbox-history-table"><thead><tr><th>SCENARIO / KIND</th><th>RESULT</th><th>HTTP</th><th>DURATION</th><th>ENVIRONMENT</th><th>RESPONSE</th><th></th></tr></thead><tbody>{executions.map((execution) => <tr key={execution.id}><td><strong>{execution.scenario ? scenarioById.get(execution.scenario)?.label : execution.kind}</strong><code>{execution.method} {execution.path}</code><small>{execution.id}</small></td><td><span className={`sandbox-outcome sandbox-outcome--${execution.outcome.toLowerCase()}`}>{execution.outcome}</span></td><td>{execution.statusCode ?? "—"}</td><td>{execution.durationMs ?? "—"} ms</td><td>{execution.environmentType}</td><td><code className="sandbox-response-excerpt">{execution.responseExcerpt ?? "No response excerpt"}</code></td><td><button className="icon-button" aria-label={`Copy response for ${execution.id}`} onClick={() => void copyExecution(execution)}><Copy size={13} /></button></td></tr>)}</tbody></table></div> : <div className="sandbox-empty-state"><Activity size={20} /><strong>No scenario runs yet</strong><p>Choose a scenario and run it here. Executions are loaded from the selected sandbox's system history.</p><button className="button button--secondary" onClick={() => setActiveTab("Scenario simulator")}><Play size={13} />Open scenario simulator</button></div>}
      </section>}

      <p className="sandbox-footer"><ShieldCheck size={13} />All server-side scenarios are fixed, bounded simulations. They create sandbox execution history records only; they do not invoke payment rails, real customers, merchant accounts, or developer-controlled webhook destinations.</p>
    </>
  );
}

function SummaryCard({ label, value, caption, icon }: { label: string; value: string; caption: string; icon: React.ReactNode }) {
  return <article className="panel sandbox-summary-card"><span className="sandbox-summary-icon">{icon}</span><span><small>{label}</small><strong>{value}</strong><em>{caption}</em></span></article>;
}
