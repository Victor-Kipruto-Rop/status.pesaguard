import { getUserMessage } from "../../lib/errors";
import { useEffect, useMemo, useState } from "react";
import { RefreshCw, ShieldAlert } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";

type AuditEvent = {
  id: string;
  sequenceNumber: number;
  actorUserId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  createdAt: string;
};

type AuditPage = { items: AuditEvent[]; totalElements: number };
const securityTerms = ["auth", "mfa", "session", "security", "credential", "api_key", "api key", "production_access", "production access"];

export function SecurityEventsPage() {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  async function loadEvents() {
    setLoading(true);
    setError("");
    try {
      const page = await apiData<AuditPage>("/api/v1/audit-events?page=0&size=100");
      setEvents(page.items);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not load security-related audit events."));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void loadEvents(); }, []);

  const securityEvents = useMemo(() => events.filter((event) => {
    const text = `${event.action} ${event.resourceType}`.toLowerCase();
    return securityTerms.some((term) => text.includes(term));
  }), [events]);

  return (
    <>
      <PageHeader
        eyebrow="EVENTS"
        title="Security events"
        description="Security-related actions found in persisted audit records. The audit API does not provide event severity."
        action={<button className="button button--secondary" type="button" disabled={loading} onClick={() => void loadEvents()}><RefreshCw size={14} />{loading ? "Refreshing…" : "Refresh"}</button>}
      />
      {error && <p className="notification-alert" role="alert">{error}</p>}
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Security-related audit records</h2><p>{securityEvents.length} matching records in the latest {events.length} audit entries loaded</p></div><span className="table-tag">SYSTEM</span></div>
        {loading && events.length === 0
          ? <p className="security-empty-state">Loading audit records…</p>
          : securityEvents.length === 0
            ? <p className="security-empty-state"><ShieldAlert size={16} />No security-related events were found in the audit records returned.</p>
            : <div className="table-scroll"><table className="data-table"><thead><tr><th>SEQUENCE</th><th>ACTION</th><th>RESOURCE</th><th>ACTOR</th><th>TIME</th></tr></thead><tbody>
              {securityEvents.map((event) => <tr key={event.id}><td>{event.sequenceNumber}</td><td><strong>{event.action}</strong></td><td>{event.resourceType}<code>{event.resourceId}</code></td><td><code>{event.actorUserId}</code></td><td>{new Date(event.createdAt).toLocaleString()}</td></tr>)}
            </tbody></table></div>}
      </section>
    </>
  );
}
