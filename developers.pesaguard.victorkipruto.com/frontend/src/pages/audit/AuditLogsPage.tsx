import { getUserMessage } from "../../lib/errors";
import { useEffect, useState } from "react";
import { Download, RefreshCw } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiBlob, apiData } from "../../lib/api";

type AuditEvent = {
  id: string;
  sequenceNumber: number;
  actorUserId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  createdAt: string;
};

type AuditPage = { items: AuditEvent[]; totalElements: number; totalPages: number };

export function AuditLogsPage() {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [exporting, setExporting] = useState(false);

  async function loadEvents() {
    setLoading(true);
    setError("");
    try {
      const result = await apiData<AuditPage>(`/api/v1/audit-events?page=${page}&size=100`);
      setEvents(result.items);
      setTotal(result.totalElements);
      setTotalPages(result.totalPages);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not load audit events."));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void loadEvents(); }, [page]);

  async function exportEvents() {
    setExporting(true);
    setError("");
    try {
      const csv = await apiBlob("/api/v1/audit-events/export?limit=10000");
      const url = URL.createObjectURL(csv);
      const link = document.createElement("a");
      link.href = url;
      link.download = "pesaguard-audit.csv";
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Audit export could not be downloaded."));
    } finally {
      setExporting(false);
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="AUDIT"
        title="Audit logs"
        description="Review persisted account actions and permission changes returned by the audit API."
        action={<div className="workflow-form-actions"><button className="button button--secondary" type="button" disabled={exporting} onClick={() => void exportEvents()}><Download size={14} />{exporting ? "Preparing…" : "Export CSV"}</button><button className="button button--secondary" type="button" disabled={loading} onClick={() => void loadEvents()}><RefreshCw size={14} />{loading ? "Refreshing…" : "Refresh"}</button></div>}
      />
      {error && <p className="notification-alert" role="alert">{error}</p>}
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Audit trail</h2><p>{events.length} records on this page · {total} total</p></div><span className="table-tag">SYSTEM</span></div>
        {loading && events.length === 0
          ? <p className="security-empty-state">Loading audit events…</p>
          : events.length === 0
            ? <p className="security-empty-state">No audit events were returned.</p>
            : <div className="table-scroll"><table className="data-table"><thead><tr><th>SEQUENCE</th><th>ACTION</th><th>RESOURCE</th><th>ACTOR</th><th>TIME</th></tr></thead><tbody>
              {events.map((event) => <tr key={event.id}><td>{event.sequenceNumber}</td><td><strong>{event.action}</strong></td><td>{event.resourceType}<code>{event.resourceId}</code></td><td><code>{event.actorUserId}</code></td><td>{new Date(event.createdAt).toLocaleString()}</td></tr>)}
            </tbody></table></div>}
        <div className="workflow-form-actions"><button className="button button--secondary" type="button" disabled={loading || page === 0} onClick={() => setPage((current) => Math.max(0, current - 1))}>Previous</button><span className="workflow-hint">Page {page + 1} of {Math.max(1, totalPages)}</span><button className="button button--secondary" type="button" disabled={loading || page + 1 >= totalPages} onClick={() => setPage((current) => current + 1)}>Next</button></div>
      </section>
    </>
  );
}
