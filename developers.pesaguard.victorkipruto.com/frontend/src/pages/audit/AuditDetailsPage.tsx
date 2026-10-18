import { getUserMessage } from "../../lib/errors";
import { useEffect, useState } from "react";
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
  correlationId: string;
  ipAddress: string | null;
  userAgent: string | null;
  hashVersion: number;
  metadata: Record<string, unknown>;
  previousHash: string;
  eventHash: string;
  createdAt: string;
};
type AuditPage = { items: AuditEvent[]; totalElements: number };

export function AuditDetailsPage() {
  const [event, setEvent] = useState<AuditEvent | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    const eventId = new URLSearchParams(window.location.search).get("id");
    void apiData<AuditPage>("/api/v1/audit-events?page=0&size=100")
      .then((page) => {
        if (!active) return;
        const selected = eventId
          ? page.items.find((item) => item.id === eventId)
          : page.items[0];
        setEvent(selected ?? null);
        if (eventId && !selected) setError("That event was not found in the latest audit records returned for this organization.");
      })
      .catch((requestError: unknown) => {
        if (active) setError(getUserMessage(requestError, "Could not load the audit event."));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, []);

  return (
    <>
      <PageHeader eyebrow="AUDIT" title="Audit details" description="Inspect a persisted audit event and its integrity metadata." />
      {error && <p className="notification-alert" role="alert">{error}</p>}
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Event details</h2><p>{event ? `Sequence ${event.sequenceNumber}` : "Authenticated organization audit record"}</p></div>{event && <span className="table-tag">RECORDED</span>}</div>
        {loading ? <p className="security-empty-state">Loading audit event…</p>
          : event ? <div className="table-scroll"><table className="data-table"><thead><tr><th>FIELD</th><th>VALUE</th></tr></thead><tbody>
            <tr><td>Event ID</td><td>{event.id}</td></tr>
            <tr><td>Action</td><td>{event.action}</td></tr>
            <tr><td>Actor user ID</td><td>{event.actorUserId}</td></tr>
            <tr><td>Resource</td><td>{event.resourceType} {event.resourceId}</td></tr>
            <tr><td>Request ID</td><td>{event.requestId}</td></tr>
            <tr><td>Correlation ID</td><td>{event.correlationId}</td></tr>
            <tr><td>Timestamp</td><td>{new Date(event.createdAt).toLocaleString()}</td></tr>
            <tr><td>Hash version</td><td>{event.hashVersion}</td></tr>
            <tr><td>Previous hash</td><td><code>{event.previousHash}</code></td></tr>
            <tr><td>Event hash</td><td><code>{event.eventHash}</code></td></tr>
            <tr><td>Metadata</td><td><pre>{JSON.stringify(event.metadata, null, 2)}</pre></td></tr>
          </tbody></table></div>
          : !error && <p className="security-empty-state">No audit event records were returned.</p>}
      </section>
    </>
  );
}
