import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useEffect, useState, type FormEvent } from "react";
import { Check, Copy, KeyRound, LoaderCircle, Plus, RotateCw, Shield, UserRoundCog, X } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";
import { copyTextToClipboard } from "../../lib/clipboard";

interface ServiceAccount {
  id: string;
  name: string;
  description: string | null;
  clientId: string;
  clientSecretHint: string;
  scopes: string[];
  status: "ACTIVE" | "SUSPENDED" | "REVOKED";
  createdAt: string;
}

interface OneTimeSecret {
  serviceAccount: ServiceAccount;
  clientSecret: string;
}

const endpointExample = `POST /api/v1/service-accounts/token
{
  "grantType": "client_credentials",
  "clientId": "YOUR_CLIENT_ID",
  "clientSecret": "YOUR_CLIENT_SECRET"
}`;

export function ServiceAccountsPage() {
  const [accounts, setAccounts] = useState<ServiceAccount[]>([]);
  const [availableScopes, setAvailableScopes] = useState<string[]>([]);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [selectedScopes, setSelectedScopes] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [workingId, setWorkingId] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [oneTimeSecret, setOneTimeSecret] = useState<OneTimeSecret | null>(null);
  const [copied, setCopied] = useState(false);

  async function load() {
    setLoading(true);
    setError("");
    try {
      const [accountList, scopes] = await Promise.all([
        apiData<ServiceAccount[]>("/api/v1/service-accounts"),
        apiData<string[]>("/api/v1/rbac/permissions/effective"),
      ]);
      setAccounts(accountList);
      setAvailableScopes(scopes);
    } catch (cause) {
      setError(getUserMessage(cause, "Unable to load service accounts."));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const result = await apiData<OneTimeSecret>("/api/v1/service-accounts", {
        method: "POST",
        body: JSON.stringify({ name, description, scopes: selectedScopes }),
      });
      setOneTimeSecret(result);
      setName("");
      setDescription("");
      setSelectedScopes([]);
      await load();
    } catch (cause) {
      setError(getUserMessage(cause, "Unable to create service account."));
    } finally {
      setSaving(false);
    }
  }

  async function runAction(account: ServiceAccount, action: "suspend" | "resume" | "rotate-secret" | "revoke") {
    if (action === "revoke" && !window.confirm(`Permanently revoke "${account.name}" and its issued access tokens?`)) return;
    if (action === "rotate-secret" && !window.confirm(`Rotate the client secret for "${account.name}"? The previous secret and its access tokens will stop working.`)) return;
    setWorkingId(account.id);
    setError("");
    setNotice("");
    try {
      if (action === "revoke") {
        await apiData<void>(`/api/v1/service-accounts/${account.id}`, { method: "DELETE" });
      } else if (action === "rotate-secret") {
        const result = await apiData<OneTimeSecret>(
          `/api/v1/service-accounts/${account.id}/rotate-secret`,
          { method: "POST" },
        );
        setOneTimeSecret(result);
      } else {
        await apiData<ServiceAccount>(
          `/api/v1/service-accounts/${account.id}/${action}`,
          { method: "POST" },
        );
      }
      setNotice(action === "revoke" ? "Service account revoked." : `Service account ${action === "rotate-secret" ? "secret rotated" : action + "d"}.`);
      await load();
    } catch (cause) {
      setError(getUserMessage(cause, "The service-account action failed."));
    } finally {
      setWorkingId("");
    }
  }

  async function copySecret() {
    if (!oneTimeSecret) return;
    try {
      await copyTextToClipboard(oneTimeSecret.clientSecret);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch (cause) {
      console.warn("Unable to copy the one-time service-account secret.");
      setError("Clipboard access was denied. Select and copy the secret manually.");
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="BUILD · CREDENTIALS"
        title="Service accounts"
        description="Create machine identities with a restricted subset of your organization permissions. Secrets are shown only once."
        action={<button className="button button--secondary" type="button" disabled={loading} onClick={() => void load()}><RotateCw size={14} />Refresh</button>}
      />

      {error && <p className="form-error" role="alert">{error}</p>}
      {notice && <p className="form-success" role="status">{notice}</p>}

      {oneTimeSecret && (
        <section className="panel service-account-secret" aria-labelledby="service-account-secret-title">
          <div className="panel-heading">
            <div><h2 id="service-account-secret-title">Copy this client secret now</h2><p>It cannot be retrieved again. Store it in your secret manager, not in source control.</p></div>
            <button className="icon-button" type="button" aria-label="Dismiss one-time secret" onClick={() => setOneTimeSecret(null)}><X size={16} /></button>
          </div>
          <label className="settings-field">Client ID<input className="field-control" readOnly value={oneTimeSecret.serviceAccount.clientId} /></label>
          <label className="settings-field">Client secret<input className="field-control service-account-secret-value" readOnly value={oneTimeSecret.clientSecret} /></label>
          <button className="button button--primary" type="button" onClick={() => void copySecret()}>{copied ? <Check size={14} /> : <Copy size={14} />}{copied ? "Copied" : "Copy secret"}</button>
        </section>
      )}

      <div className="service-account-layout">
        <section className="panel">
          <div className="panel-heading"><div><h2>Create service account</h2><p>Granted scopes cannot exceed your current effective permissions.</p></div><UserRoundCog size={17} /></div>
          <ValidatedForm className="service-account-form" onSubmit={(event) => void create(event)}>
            <label className="settings-field">Name
              <input className="field-control" value={name} onChange={(event) => setName(event.target.value)} minLength={2} maxLength={120} required />
            </label>
            <label className="settings-field">Description
              <textarea className="field-control" value={description} onChange={(event) => setDescription(event.target.value)} maxLength={500} rows={3} />
            </label>
            <fieldset className="service-account-scopes">
              <legend>Permissions</legend>
              {availableScopes.length === 0 ? <p>No effective permission scopes are available to grant.</p> : availableScopes.map((scope) => (
                <label key={scope}>
                  <input type="checkbox" checked={selectedScopes.includes(scope)} onChange={(event) => {
                    setSelectedScopes((current) => event.target.checked
                      ? [...current, scope]
                      : current.filter((item) => item !== scope));
                  }} />
                  <code>{scope}</code>
                </label>
              ))}
            </fieldset>
            <button className="button button--primary" type="submit" disabled={saving || loading || selectedScopes.length === 0}>
              {saving ? <LoaderCircle size={14} className="spin" /> : <Plus size={14} />}
              {saving ? "Creating…" : "Create service account"}
            </button>
          </ValidatedForm>
        </section>

        <section className="panel">
          <div className="panel-heading"><div><h2>Organization service accounts</h2><p>{loading ? "Loading persisted accounts…" : `${accounts.length} account${accounts.length === 1 ? "" : "s"}`}</p></div><Shield size={17} /></div>
          {loading ? <p role="status">Loading service accounts…</p>
            : accounts.length === 0 ? <div className="api-live-empty">No service accounts have been created for this organization.</div>
              : <div className="service-account-list">{accounts.map((account) => (
                <article className="service-account-card" key={account.id}>
                  <div className="service-account-card-heading">
                    <div><h3>{account.name}</h3><p>{account.description || "No description provided."}</p></div>
                    <span className={`credential-status credential-status--${account.status === "ACTIVE" ? "active" : account.status === "REVOKED" ? "terminal" : "other"}`}>{account.status.toLowerCase()}</span>
                  </div>
                  <dl><div><dt>Client ID</dt><dd><code>{account.clientId}</code></dd></div><div><dt>Secret hint</dt><dd><code>{account.clientSecretHint}…</code></dd></div></dl>
                  <div className="service-account-scope-list">{account.scopes.map((scope) => <code key={scope}>{scope}</code>)}</div>
                  <p className="service-account-created">Created {new Date(account.createdAt).toLocaleString()}</p>
                  {account.status !== "REVOKED" && <div className="service-account-actions">
                    {account.status === "ACTIVE"
                      ? <button className="button button--secondary" type="button" disabled={workingId === account.id} onClick={() => void runAction(account, "suspend")}>Suspend</button>
                      : <button className="button button--secondary" type="button" disabled={workingId === account.id} onClick={() => void runAction(account, "resume")}>Resume</button>}
                    <button className="button button--secondary" type="button" disabled={workingId === account.id} onClick={() => void runAction(account, "rotate-secret")}><KeyRound size={14} />Rotate secret</button>
                    <button className="button button--danger" type="button" disabled={workingId === account.id} onClick={() => void runAction(account, "revoke")}>Revoke</button>
                  </div>}
                </article>
              ))}</div>}
        </section>
      </div>

      <section className="panel service-account-token-guide">
        <div className="panel-heading"><div><h2>Get a machine access token</h2><p>Use client credentials over HTTPS; the response is not stored by the browser.</p></div><KeyRound size={17} /></div>
        <pre className="api-live-output">{endpointExample}</pre>
        <p>Access tokens are short-lived. Requests with a service-account token are limited to the assigned permissions and supported project, environment, sandbox, usage, audit, and OAuth application APIs.</p>
      </section>
    </>
  );
}
