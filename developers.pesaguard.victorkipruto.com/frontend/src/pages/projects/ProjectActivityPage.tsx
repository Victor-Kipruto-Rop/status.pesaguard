import { getUserMessage } from "../../lib/errors";
import { useEffect, useState } from "react";
import { Activity } from "lucide-react";
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
type AuditPage = { items: AuditEvent[]; totalElements: number };

export function ProjectActivityPage() {
  const projectId = window.sessionStorage.getItem("pesaguard.project.id") ?? "";
  const projectName = window.sessionStorage.getItem("pesaguard.project.name") ?? "";
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState(Boolean(projectId));
  const [error, setError] = useState("");

  useEffect(() => {
    if (!projectId) {
      setError("Select a project before viewing its audit activity.");
      return;
    }
    let active = true;
    void apiData<AuditPage>("/api/v1/audit-events?page=0&size=100")
      .then((result) => {
        if (active) setEvents(result.items.filter((event) => event.resourceId === projectId));
      })
      .catch((requestError: unknown) => {
        if (active) setError(getUserMessage(requestError, "Could not load project activity."));
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [projectId]);

  return (
    <>
      <PageHeader eyebrow="MONITOR" title="Project activity" description={projectName ? `Audit events for ${projectName}, filtered from the latest 100 organization events.` : "Audit events filtered from the latest 100 organization events for the selected project."} />
      {error && <p className="notification-alert" role="alert">{error}</p>}
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Project audit trail</h2><p>Filtered from persisted organization audit events.</p></div><span className="table-tag">{events.length} RECORDS</span></div>
        {loading && events.length === 0
          ? <div className="organization-live-empty"><Activity size={18} />Loading project activity…</div>
          : events.length === 0
            ? <div className="organization-live-empty"><Activity size={18} />No project audit events returned.</div>
            : <div className="table-scroll"><table className="data-table"><thead><tr><th>SEQUENCE</th><th>EVENT</th><th>ACTOR</th><th>REQUEST ID</th><th>TIME</th></tr></thead><tbody>{events.map((event) => <tr key={event.id}><td>{event.sequenceNumber}</td><td><strong>{event.action}</strong><small>{event.resourceType} · {event.resourceId}</small></td><td><code>{event.actorUserId}</code></td><td><code>{event.requestId}</code></td><td>{new Date(event.createdAt).toLocaleString()}</td></tr>)}</tbody></table></div>}
      </section>
    </>
  );
}
