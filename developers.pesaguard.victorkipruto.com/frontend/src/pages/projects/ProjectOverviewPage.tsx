import { getUserMessage } from "../../lib/errors";
import { ArrowRight, FolderKanban, RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";

type Project = {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  status: string;
  ownerUserId: string;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
};

type Environment = {
  id: string;
  projectId: string;
  name: string;
  type: string;
  status: string;
  createdAt: string;
};

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unknown" : new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

export function ProjectOverviewPage({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  const { isAuthenticated, hasPermission } = useAuth();
  const canUpdate = hasPermission("project:update");
  const projectId = window.sessionStorage.getItem("pesaguard.project.id") ?? "";
  const [project, setProject] = useState<Project | null>(null);
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [environmentError, setEnvironmentError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    if (!isAuthenticated || !projectId) {
      setProject(null);
      setEnvironments([]);
      setLoading(false);
      setError(!isAuthenticated ? "Sign in to view project details." : "Select a project from the project list first.");
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setEnvironmentError(null);
    Promise.allSettled([
      apiData<Project>(`/api/v1/projects/${encodeURIComponent(projectId)}`, { signal: controller.signal }),
      apiData<Environment[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/environments`, { signal: controller.signal }),
    ]).then(([projectResult, environmentResult]) => {
      if (controller.signal.aborted) return;
      if (projectResult.status === "fulfilled") {
        setProject(projectResult.value);
        window.sessionStorage.setItem("pesaguard.project.name", projectResult.value.name);
      } else {
        setProject(null);
        setError(getUserMessage(projectResult.reason, "Unable to load this project."));
      }
      if (environmentResult.status === "fulfilled") {
        setEnvironments(environmentResult.value);
      } else {
        setEnvironments([]);
        setEnvironmentError(getUserMessage(environmentResult.reason, "Unable to load project environments."));
      }
      setLoading(false);
    });
    return () => controller.abort();
  }, [isAuthenticated, projectId, reloadKey]);

  async function updateStatus(action: "activate" | "deactivate" | "restore") {
    if (!project || !isAuthenticated) return;
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      const updated = await apiData<Project>(`/api/v1/projects/${encodeURIComponent(project.id)}/${action}`, { method: "POST" });
      setProject(updated);
      setMessage(`Project ${action === "deactivate" ? "deactivated" : action === "restore" ? "restored" : "activated"}.`);
      setReloadKey((current) => current + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Project status could not be changed."));
    } finally {
      setSaving(false);
    }
  }

  function openEnvironment(environment: Environment) {
    if (!project) return;
    window.sessionStorage.setItem("pesaguard.environment.id", environment.id);
    window.sessionStorage.setItem("pesaguard.environment.name", environment.name);
    window.sessionStorage.setItem("pesaguard.environment.type", environment.type);
    window.sessionStorage.setItem("pesaguard.environment.project", project.name);
    window.sessionStorage.setItem("pesaguard.environment.project-id", project.id);
    onNavigate("environment-detail");
  }

  const statusAction = project?.status === "ACTIVE"
    ? "deactivate"
    : project?.status === "ARCHIVED"
      ? "restore"
      : project?.status === "DEACTIVATED"
        ? "activate"
        : null;

  return (
    <>
      <PageHeader
        eyebrow="PROJECT"
        title={project?.name ?? "Project"}
        description={project ? `${project.slug} · ${project.status}` : "Project details and environments."}
        action={<div className="project-page-actions">
          <button className="button button--secondary" type="button" onClick={() => onNavigate("environments")}>All environments</button>
          {statusAction && canUpdate && <button className="button button--secondary" type="button" disabled={saving} onClick={() => void updateStatus(statusAction)}><RefreshCw size={14} />{saving ? "Updating…" : statusAction[0].toUpperCase() + statusAction.slice(1)}</button>}
        </div>}
      />
      {error && <div className="workflow-error" role="alert"><p>{loading ? "Loading project…" : error}</p><button className="text-button" type="button" onClick={() => setReloadKey((current) => current + 1)}>Retry</button></div>}
      {message && <p className="workflow-success" role="status">{message}</p>}
      {loading && <p className="workflow-hint" role="status">Loading project details…</p>}

      {project && (
        <>
          <section className="panel settings-group">
            <div className="settings-group-header">
              <div className="settings-group-title-wrap"><span className="settings-group-icon"><FolderKanban size={17} /></span><div><h2>Project details</h2><p>Organization-scoped metadata from the projects API.</p></div></div>
              <span className="table-tag">{project.status}</span>
            </div>
            <dl className="environment-details-grid">
              <div><dt>Project ID</dt><dd><code>{project.id}</code></dd></div>
              <div><dt>Slug</dt><dd><code>{project.slug}</code></dd></div>
              <div><dt>Owner user ID</dt><dd><code>{project.ownerUserId}</code></dd></div>
              <div><dt>Created</dt><dd>{formatDate(project.createdAt)}</dd></div>
              <div><dt>Last updated</dt><dd>{formatDate(project.updatedAt)}</dd></div>
              <div><dt>Description</dt><dd>{project.description || "No description"}</dd></div>
            </dl>
          </section>

          <section className="panel table-panel">
            <div className="panel-heading"><div><h2>Environments</h2><p>Project environments returned by the environment service.</p></div><span className="directory-count">{environments.length}</span></div>
            {environmentError && <p className="workflow-error" role="alert">Unable to load project environments: {environmentError}</p>}
            <div className="environment-directory-list">{environments.map((environment) => (
              <article className="environment-directory-row" key={environment.id}>
                <span className={`environment-directory-icon${environment.type === "PRODUCTION" ? " environment-directory-icon--production" : ""}`}><FolderKanban size={17} /></span>
                <div className="environment-directory-info"><strong>{environment.name}</strong><span>{environment.type} · Created {formatDate(environment.createdAt)}</span></div>
                <span className="status-label"><span className={`status-dot${environment.status !== "ACTIVE" ? " status-dot--muted" : ""}`} />{environment.status}</span>
                <button className="button button--secondary" type="button" onClick={() => openEnvironment(environment)}>Open<ArrowRight size={13} /></button>
              </article>
            ))}{!environmentError && environments.length === 0 && <div className="project-directory-empty"><FolderKanban size={22} /><strong>No environments yet</strong><span>Create one from the environments page.</span><button className="text-button" type="button" onClick={() => onNavigate("environments")}>Open environments</button></div>}</div>
          </section>
          <p className="usage-data-note">Project configuration and operational activity are shown only where supported by connected system services.</p>
        </>
      )}
    </>
  );
}
