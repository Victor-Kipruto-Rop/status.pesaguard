import { getUserMessage } from "../../lib/errors";
import { useEffect, useState } from "react";
import { MonitorSmartphone, RefreshCw } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { listSessions, revokeOtherSessions, revokeSession } from "../../lib/authApi";
import type { SessionSummary } from "../../types/auth";

function displayTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export function SessionsPage() {
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [revokingId, setRevokingId] = useState("");
  const [revokingOthers, setRevokingOthers] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function loadSessions() {
    setLoading(true);
    setError("");
    try {
      setSessions(await listSessions());
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not load authenticated sessions."));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void loadSessions(); }, []);

  async function endSession(session: SessionSummary) {
    setRevokingId(session.id);
    setError("");
    setNotice("");
    try {
      await revokeSession(session.id);
      setSessions((current) => current.filter((item) => item.id !== session.id));
      setNotice(`Session ${session.id} was revoked.`);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not revoke the session."));
    } finally {
      setRevokingId("");
    }
  }

  async function endOtherSessions() {
    setRevokingOthers(true);
    setError("");
    setNotice("");
    try {
      const result = await revokeOtherSessions();
      setSessions((current) => current.filter((session) => session.current));
      setNotice(result.revokedCount
        ? `${result.revokedCount} other session${result.revokedCount === 1 ? "" : "s"} revoked.`
        : "There were no other active sessions to revoke.");
    } catch (requestError) {
      setError(getUserMessage(requestError, "Could not revoke other sessions."));
    } finally {
      setRevokingOthers(false);
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="ACCESS"
        title="Sessions"
        description="Authenticated sessions returned by the account session API. Locations and risk scores are not available from this endpoint."
        action={<div className="overview-header-actions">
          <button className="button button--secondary" type="button" disabled={loading} onClick={() => void loadSessions()}><RefreshCw size={14} />{loading ? "Refreshing…" : "Refresh"}</button>
          <button className="button button--secondary" type="button" disabled={loading || revokingOthers || Boolean(revokingId) || !sessions.some((session) => !session.current)} onClick={() => void endOtherSessions()}>{revokingOthers ? "Revoking…" : "Revoke other sessions"}</button>
        </div>}
      />
      {error && <p className="notification-alert" role="alert">{error}</p>}
      {notice && <p className="notification-success" role="status">{notice}</p>}
      <section className="stats-grid stats-grid--three">
        <article className="stat-card"><div className="stat-card-top"><span>Active sessions</span><MonitorSmartphone size={17} /></div><div className="stat-value">{loading && sessions.length === 0 ? "—" : sessions.length}</div><div className="stat-foot">{error || "Current sessions returned by the system"}</div></article>
      </section>
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Account sessions</h2><p>Revoke any session except the current one.</p></div><span className="table-tag">SYSTEM</span></div>
        {loading && sessions.length === 0
          ? <p className="security-empty-state">Loading account sessions…</p>
          : sessions.length === 0
            ? <p className="security-empty-state">No active sessions were returned.</p>
            : <div className="table-scroll"><table className="data-table"><thead><tr><th>CLIENT</th><th>IP ADDRESS</th><th>LAST ACTIVE</th><th>EXPIRES</th><th>STATUS</th><th></th></tr></thead><tbody>
              {sessions.map((session) => <tr key={session.id}>
                <td><strong>{session.deviceLabel}</strong><code>{session.id}</code></td>
                <td>{session.lastIp ?? "Not reported"}</td>
                <td>{displayTime(session.lastSeenAt)}</td>
                <td>{displayTime(session.expiresAt)}</td>
                <td>{session.current ? "Current" : "Active"}</td>
                <td>{!session.current && <button className="text-button" type="button" disabled={Boolean(revokingId)} onClick={() => void endSession(session)}>{revokingId === session.id ? "Revoking…" : "Revoke"}</button>}</td>
              </tr>)}
            </tbody></table></div>}
      </section>
    </>
  );
}
