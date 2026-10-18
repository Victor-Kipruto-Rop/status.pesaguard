import { getUserMessage } from "../../lib/errors";
import { useEffect, useState } from "react";
import { Activity } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";

type SandboxInstance = { id: string; name: string; status: string };
type Execution = {
  id: string;
  environmentType: string;
  kind: string;
  method?: string;
  path?: string;
  statusCode?: number;
  outcome: string;
  durationMs?: number;
  createdAt?: string;
};
type ActivityRow = Execution & { sandboxName: string };

export function SandboxActivityPage() {
  const { isAuthenticated } = useAuth();
  const [rows, setRows] = useState<ActivityRow[]>([]);
  const [loading, setLoading] = useState(isAuthenticated);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!isAuthenticated) {
      setRows([]);
      setLoading(false);
      return;
    }
    let active = true;
    setLoading(true);
    setError("");
    void apiData<SandboxInstance[]>("/api/v1/sandboxes")
      .then(async (sandboxes) => {
        if (!Array.isArray(sandboxes)) throw new Error("The sandbox API returned an invalid list.");
        const executions = await Promise.all(sandboxes.map(async (sandbox) => {
          const items = await apiData<Execution[]>(`/api/v1/sandboxes/${encodeURIComponent(sandbox.id)}/executions?limit=100`);
          if (!Array.isArray(items)) throw new Error(`The execution API returned an invalid list for ${sandbox.name}.`);
          return items.map((item) => ({ ...item, sandboxName: sandbox.name }));
        }));
        if (active) setRows(executions.flat().sort((left, right) => Date.parse(right.createdAt ?? "") - Date.parse(left.createdAt ?? "")));
      })
      .catch((requestError: unknown) => {
        if (active) setError(getUserMessage(requestError, "Could not load sandbox execution history."));
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [isAuthenticated]);

  return (
    <>
      <PageHeader eyebrow="SANDBOX" title="Sandbox activity" description="Execution records returned by your sandbox instances." />
      {!isAuthenticated && <p className="preview-notice"><strong>Sign in required</strong> — Sandbox activity is organization-scoped.</p>}
      {error && <p className="workflow-error" role="alert">{error}</p>}
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Execution history</h2><p>Up to 100 execution records per sandbox instance.</p></div><span className="table-tag">{rows.length} RECORDS</span></div>
        {loading ? <p className="workflow-hint" role="status">Loading sandbox history…</p> : rows.length === 0 ? <div className="organization-live-empty"><Activity size={18} />No sandbox executions returned.</div> :
          <div className="table-scroll"><table className="data-table"><thead><tr><th>TIME</th><th>SANDBOX</th><th>SCENARIO</th><th>REQUEST</th><th>RESULT</th><th>DURATION</th></tr></thead><tbody>{rows.map((row) => <tr key={row.id}><td>{row.createdAt ? new Date(row.createdAt).toLocaleString() : "—"}</td><td>{row.sandboxName}</td><td>{row.kind}</td><td>{row.method && row.path ? `${row.method} ${row.path}` : "—"}</td><td>{row.statusCode ?? "—"} · {row.outcome}</td><td>{row.durationMs == null ? "—" : `${row.durationMs} ms`}</td></tr>)}</tbody></table></div>}
      </section>
    </>
  );
}
