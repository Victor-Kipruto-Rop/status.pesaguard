import { AppError, getUserMessage } from "../../lib/errors";
import { RefreshCw, Save } from "lucide-react";
import { useEffect, useState } from "react";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";

type ProjectSettings = {
  projectId: string;
  settings: Record<string, unknown>;
  updatedBy: string | null;
  updatedAt: string | null;
};

export function ProjectSettingsPage() {
  const { isAuthenticated, hasPermission } = useAuth();
  const canUpdate = hasPermission("project:update");
  const projectId = window.sessionStorage.getItem("pesaguard.project.id") ?? "";
  const [settings, setSettings] = useState<ProjectSettings | null>(null);
  const [settingsText, setSettingsText] = useState("{}");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    if (!isAuthenticated || !projectId) {
      setSettings(null);
      setLoading(false);
      setError(!isAuthenticated ? "Sign in to manage project settings." : "Select a project from the project list first.");
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    apiData<ProjectSettings>(`/api/v1/projects/${encodeURIComponent(projectId)}/settings`, { signal: controller.signal })
      .then((response) => {
        if (!controller.signal.aborted) {
          setSettings(response);
          setSettingsText(JSON.stringify(response.settings ?? {}, null, 2));
        }
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setError(getUserMessage(requestError, "Unable to load project settings."));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [isAuthenticated, projectId, reloadKey]);

  async function saveSettings() {
    if (!isAuthenticated || !projectId) return;
    let nextSettings: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(settingsText);
      if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
        throw new AppError("VALIDATION_ERROR", "Project settings must be a JSON object.");
      }
      nextSettings = Object.fromEntries(Object.entries(parsed));
    } catch (parseError) {
      setError(getUserMessage(parseError, "Enter valid project settings."));
      return;
    }

    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      const updated = await apiData<ProjectSettings>(`/api/v1/projects/${encodeURIComponent(projectId)}/settings`, {
        method: "PUT",
        body: JSON.stringify({ settings: nextSettings }),
      });
      setSettings(updated);
      setSettingsText(JSON.stringify(updated.settings, null, 2));
      setMessage("Project settings saved.");
    } catch (requestError) {
      setError(getUserMessage(requestError, "Project settings could not be saved."));
    } finally {
      setSaving(false);
    }
  }

  const updatedAt = settings?.updatedAt ? new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(settings.updatedAt)) : "Not yet updated";

  return (
    <div className="premium-page project-settings-page">
      <PageHeader
        eyebrow="PROJECT SETTINGS"
        title="Project settings"
        description="Read and update the settings map stored for the selected project."
        action={<button className="button button--secondary" type="button" disabled={loading} onClick={() => setReloadKey((current) => current + 1)}><RefreshCw size={14} />Refresh</button>}
      />
      {error && <p className="workflow-error" role="alert">{error}</p>}
      {message && <p className="workflow-success" role="status">{message}</p>}
      {loading && <p className="workflow-hint" role="status">Loading project settings…</p>}
      {settings && <section className="premium-context-banner"><span className="premium-icon-tile"><Save size={18} /></span><div><span className="section-eyebrow">CONFIGURATION CONTROL</span><strong>Project settings are saved to the selected project.</strong><small>Last changed {updatedAt}{settings.updatedBy ? ` by ${settings.updatedBy}` : ""}</small></div><span className="premium-live-mark"><i />PERSISTED</span></section>}
      {settings && (
        <section className="panel settings-group">
          <div className="settings-group-header">
            <div className="settings-group-title-wrap"><span className="settings-group-icon"><Save size={17} /></span><div><h2>Persisted settings</h2><p>Changes are sent to the project settings API.</p></div></div>
            <span className="table-tag">UPDATED {updatedAt}</span>
          </div>
          <label className="settings-field environment-configuration-field"><span>Project settings (JSON object)</span><textarea className="field-control" rows={16} spellCheck={false} value={settingsText} onChange={(event) => setSettingsText(event.target.value)} disabled={saving} /></label>
          {canUpdate && <div className="workflow-form-actions"><button className="button button--primary" type="button" disabled={saving} onClick={() => void saveSettings()}><Save size={14} />{saving ? "Saving…" : "Save settings"}</button></div>}
        </section>
      )}
    </div>
  );
}
