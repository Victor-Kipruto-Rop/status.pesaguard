import { getUserMessage } from "../../lib/errors";
import { useEffect, useMemo, useState } from "react";
import { Activity, ChevronLeft, ChevronRight, RefreshCw, Search } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";

type AuditEvent = {
  id: string;
  sequenceNumber: number;
  actorUserId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  requestId: string;
  createdAt: string;
};
type AuditPage = { items: AuditEvent[]; totalElements: number; totalPages: number };

export function ActivityPage() {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  async function loadActivity() {
    setLoading(true);
    setError("");
    try {
      const result = await apiData<AuditPage>(`/api/v1/audit-events?page=${page}&size=15`);
      setEvents(result.items);
      setTotal(result.totalElements);
      setTotalPages(result.totalPages);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not load organization activity."));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void loadActivity(); }, [page]);

  const visibleEvents = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return needle ? events.filter((event) => [
      event.action, event.actorUserId, event.resourceType, event.resourceId, event.requestId,
    ].some((value) => value?.toLowerCase().includes(needle))) : events;
  }, [events, query]);

  return (
    <>
      <PageHeader eyebrow="MONITOR" title="Activity" description="Review persisted organization audit events." action={<button className="button button--secondary" type="button" disabled={loading} onClick={() => void loadActivity()}><RefreshCw size={14} />Refresh</button>} />
      {error && <p className="notification-alert" role="alert">{error}</p>}
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Audit trail</h2><p>{total === 0 ? "No records" : `Showing ${page * 15 + 1}–${page * 15 + events.length} of ${total} records`}</p></div><span className="table-tag">SYSTEM</span></div>
        <label className="security-audit-search"><Search size={14} /><span className="visually-hidden">Search events on this page</span><input placeholder="Search this page by action, actor, resource, or request ID" value={query} onChange={(event) => setQuery(event.target.value)} /></label>
        <div className="activity-pagination" aria-label="Activity pages">
          <button className="button button--secondary" type="button" disabled={loading || page === 0} onClick={() => setPage((current) => Math.max(0, current - 1))}><ChevronLeft size={14} />Previous</button>
          <span aria-live="polite">Page {page + 1} of {Math.max(1, totalPages)}</span>
          <button className="button button--secondary" type="button" disabled={loading || page + 1 >= totalPages} onClick={() => setPage((current) => current + 1)}>Next<ChevronRight size={14} /></button>
        </div>
        {loading && events.length === 0
          ? <div className="organization-live-empty"><Activity size={18} />Loading audit events…</div>
          : visibleEvents.length === 0
            ? <div className="organization-live-empty"><Activity size={18} />{events.length ? "No loaded events match." : "No audit events returned."}</div>
            : <div className="table-scroll"><table className="data-table"><thead><tr><th>SEQUENCE</th><th>ACTION</th><th>RESOURCE</th><th>ACTOR</th><th>REQUEST ID</th><th>TIME</th></tr></thead><tbody>{visibleEvents.map((event) => <tr key={event.id}><td>{event.sequenceNumber}</td><td><strong>{event.action}</strong></td><td>{event.resourceType}<code>{event.resourceId}</code></td><td><code>{event.actorUserId}</code></td><td><code>{event.requestId}</code></td><td>{new Date(event.createdAt).toLocaleString()}</td></tr>)}</tbody></table></div>}
      </section>
    </>
  );
}
