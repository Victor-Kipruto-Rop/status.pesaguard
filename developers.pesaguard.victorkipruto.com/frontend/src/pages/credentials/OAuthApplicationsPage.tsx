import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useEffect, useState, type FormEvent } from "react";
import { KeyRound, Plus, RefreshCw, ShieldCheck } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";

type OAuthApplication = {
  id: string;
  name: string;
  description: string;
  clientId: string;
  clientSecretHint: string;
  clientSecretVersion: number;
  redirectUris: string[];
  allowedOrigins: string[];
  scopes: string[];
  status: string;
  createdAt: string;
};
type CreatedApplication = { application: OAuthApplication; clientSecret: string };

export function OAuthApplicationsPage() {
  const { isAuthenticated } = useAuth();
  const [applications, setApplications] = useState<OAuthApplication[]>([]);
  const [loading, setLoading] = useState(isAuthenticated);
  const [saving, setSaving] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [formOpen, setFormOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [redirectUri, setRedirectUri] = useState("");
  const [origin, setOrigin] = useState("");
  const [error, setError] = useState("");
  const [created, setCreated] = useState<CreatedApplication | null>(null);
  const activeApplications = applications.filter((application) => application.status === "ACTIVE").length;

  useEffect(() => {
    if (!isAuthenticated) {
      setApplications([]);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError("");
    void apiData<OAuthApplication[]>("/api/v1/oauth/applications", { signal: controller.signal })
      .then((items) => {
        if (!Array.isArray(items)) throw new Error("The OAuth applications API returned an invalid list.");
        setApplications(items);
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setError(getUserMessage(requestError, "Could not load OAuth applications."));
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [isAuthenticated, refreshKey]);

  async function createApplication(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError("");
    setCreated(null);
    try {
      const result = await apiData<CreatedApplication>("/api/v1/oauth/applications", {
        method: "POST",
        body: JSON.stringify({
          name: name.trim(),
          description: description.trim() || null,
          redirectUris: [redirectUri.trim()],
          allowedOrigins: origin.trim() ? [origin.trim()] : [],
          scopes: [],
        }),
      });
      if (!result?.application?.id || !result.clientSecret) throw new Error("The OAuth API did not return the newly created application and one-time secret.");
      setCreated(result);
      setName("");
      setDescription("");
      setRedirectUri("");
      setOrigin("");
      setFormOpen(false);
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "The OAuth application could not be created."));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="premium-page oauth-applications-page">
      <PageHeader eyebrow="BUILD" title="OAuth applications" description="Register OAuth clients using the authenticated applications API." action={<><button className="button button--secondary" type="button" disabled={loading} onClick={() => setRefreshKey((key) => key + 1)}><RefreshCw size={14} />Refresh</button><button className="button button--primary" type="button" disabled={!isAuthenticated} onClick={() => { setError(""); setFormOpen((open) => !open); }}><Plus size={16} />New app</button></>} />
      {!isAuthenticated && <p className="preview-notice"><strong>Sign in required</strong> — OAuth clients are loaded from your authenticated account.</p>}
      {error && <p className="workflow-error" role="alert">{error}</p>}
      <section className="premium-metric-grid" aria-label="OAuth application overview">
        <article className="panel premium-metric premium-metric--green"><span>Registered clients</span><strong>{applications.length}</strong><small>Scoped to this workspace</small></article>
        <article className="panel premium-metric"><span>Active clients</span><strong>{activeApplications}</strong><small>Eligible for OAuth flows</small></article>
        <article className="panel premium-metric premium-metric--amber"><span>Secret handling</span><strong>One time</strong><small>Secrets are shown only on creation</small></article>
      </section>
      {created && <section className="panel oauth-created-secret" aria-live="polite"><div className="panel-heading"><div><h2>Application created</h2><p>Copy this client secret now. The API does not return it again.</p></div><ShieldCheck size={18} /></div><p><strong>Client ID</strong><code>{created.application.clientId}</code></p><p><strong>Client secret</strong><code>{created.clientSecret}</code></p><p className="workflow-hint">Store this credential in a secret manager. Do not commit it or expose it in client-side code.</p><button className="text-button" type="button" onClick={() => setCreated(null)}>Dismiss secret</button></section>}
      {formOpen && <section className="panel premium-form-panel oauth-register-panel">
        <div className="panel-heading">
          <div className="resource-create-heading">
            <span className="resource-create-icon oauth-register-icon" aria-hidden="true"><KeyRound size={19} /></span>
            <div><span className="section-eyebrow">CLIENT REGISTRATION</span><h2>Register OAuth client</h2><p>Set exact callback and origin URLs before creating this workspace-scoped client.</p></div>
          </div>
          <span className="table-tag">SECRET SHOWN ONCE</span>
        </div>
        <ValidatedForm className="workflow-form oauth-register-form" onSubmit={(event) => void createApplication(event)}>
          <label>Application name<input required minLength={2} maxLength={120} value={name} onChange={(event) => setName(event.target.value)} /></label>
          <label>Description<input maxLength={500} value={description} onChange={(event) => setDescription(event.target.value)} /></label>
          <label>Redirect URI<input required type="url" maxLength={512} value={redirectUri} onChange={(event) => setRedirectUri(event.target.value)} placeholder="https://example.com/oauth/callback" /></label>
          <label>Allowed origin (optional)<input type="url" maxLength={255} value={origin} onChange={(event) => setOrigin(event.target.value)} placeholder="https://example.com" /></label>
          <div className="workflow-form-actions"><button className="button button--secondary" type="button" disabled={saving} onClick={() => setFormOpen(false)}>Cancel</button><button className="button button--primary" type="submit" disabled={saving}><Plus size={14} />{saving ? "Registering…" : "Register application"}</button></div>
        </ValidatedForm>
      </section>}
      <section className="panel table-panel"><div className="panel-heading"><div><h2>Registered applications</h2><p>Persisted OAuth clients and registered callbacks.</p></div><span className="table-tag">{applications.length} RECORDS</span></div>
        {loading ? <p className="workflow-hint" role="status">Loading applications…</p> : applications.length === 0 ? <div className="organization-live-empty"><KeyRound size={18} />No OAuth applications returned by the API.</div> :
          <div className="table-scroll"><table className="data-table"><thead><tr><th>APPLICATION</th><th>CLIENT ID</th><th>STATUS</th><th>SECRET VERSION</th><th>CREATED</th></tr></thead><tbody>{applications.map((application) => <tr key={application.id}><td><strong>{application.name}</strong><small>{application.redirectUris.join(", ")}</small></td><td><code>{application.clientId}</code></td><td><span className={`premium-status premium-status--${application.status.toLowerCase()}`}>{application.status}</span></td><td>{application.clientSecretVersion}</td><td>{new Date(application.createdAt).toLocaleDateString()}</td></tr>)}</tbody></table></div>}
      </section>
    </div>
  );
}
