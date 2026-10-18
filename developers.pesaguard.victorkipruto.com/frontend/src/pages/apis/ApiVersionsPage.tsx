import { useEffect, useState } from "react";
import { RefreshCw } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { getUserMessage } from "../../lib/errors";
import { apiData } from "../../lib/api";

type VersionedScope = { name: string; version: number; deprecated: boolean; replacedBy: string | null };
type VersionedEvent = { name: string; version: number; lifecycle: string };

export function ApiVersionsPage() {
  const [scopes, setScopes] = useState<VersionedScope[]>([]);
  const [events, setEvents] = useState<VersionedEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [scopeError, setScopeError] = useState("");
  const [eventError, setEventError] = useState("");

  async function loadVersions() {
    setLoading(true);
    const [scopeResult, eventResult] = await Promise.allSettled([
      apiData<VersionedScope[]>("/api/v1/scopes?includeDeprecated=true"),
      apiData<VersionedEvent[]>("/api/v1/events/catalog"),
    ]);
    if (scopeResult.status === "fulfilled") {
      setScopes(scopeResult.value);
      setScopeError("");
    } else {
      setScopes([]);
      setScopeError(getUserMessage(scopeResult.reason, "Could not load scope versions."));
    }
    if (eventResult.status === "fulfilled") {
      setEvents(eventResult.value);
      setEventError("");
    } else {
      setEvents([]);
      setEventError(getUserMessage(eventResult.reason, "Could not load event contract versions."));
    }
    setLoading(false);
  }

  useEffect(() => { void loadVersions(); }, []);

  return (
    <>
      <PageHeader
        eyebrow="VERSIONING"
        title="API versions"
        description="Versions reported by the system scope and event registries. Product API release versions are not exposed by these APIs."
        action={<button className="button button--secondary" type="button" disabled={loading} onClick={() => void loadVersions()}><RefreshCw size={14} />{loading ? "Refreshing…" : "Refresh"}</button>}
      />
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Scope versions</h2><p>Scope version and deprecation state from the system registry.</p></div><span className="table-tag">SYSTEM</span></div>
        {scopeError && <p className="notification-alert" role="alert">{scopeError}</p>}
        {loading && scopes.length === 0 ? <p className="security-empty-state">Loading scope versions…</p>
          : scopes.length === 0 ? <p className="security-empty-state">{scopeError ? "Scope version data is unavailable." : "No scope versions were returned."}</p>
            : <div className="table-scroll"><table className="data-table"><thead><tr><th>SCOPE</th><th>VERSION</th><th>STATE</th><th>REPLACED BY</th></tr></thead><tbody>
              {scopes.map((scope) => <tr key={scope.name}><td>{scope.name}</td><td>{scope.version}</td><td>{scope.deprecated ? "Deprecated" : "Current"}</td><td>{scope.replacedBy ?? "—"}</td></tr>)}
            </tbody></table></div>}
      </section>
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Event contract versions</h2><p>Version and lifecycle returned by the event catalog.</p></div><span className="table-tag">SYSTEM</span></div>
        {eventError && <p className="notification-alert" role="alert">{eventError}</p>}
        {loading && events.length === 0 ? <p className="security-empty-state">Loading event contract versions…</p>
          : events.length === 0 ? <p className="security-empty-state">{eventError ? "Event version data is unavailable." : "No event contract versions were returned."}</p>
            : <div className="table-scroll"><table className="data-table"><thead><tr><th>EVENT</th><th>VERSION</th><th>LIFECYCLE</th></tr></thead><tbody>
              {events.map((event) => <tr key={event.name}><td>{event.name}</td><td>{event.version}</td><td>{event.lifecycle}</td></tr>)}
            </tbody></table></div>}
      </section>
    </>
  );
}
