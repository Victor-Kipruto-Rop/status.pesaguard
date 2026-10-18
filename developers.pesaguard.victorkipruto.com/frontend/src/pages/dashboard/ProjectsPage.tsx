import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { Archive, FolderKanban, Plus, RotateCcw, Search, X } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";
import { useFormDraft } from "../../lib/useFormDraft";

type ProjectRow = {
  id: string;
  name: string;
  slug: string;
  status: string;
  createdAt: string;
};

type ProjectListResponse = {
  items: ProjectRow[];
  totalElements: number;
};

export function ProjectsPage({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  const { isAuthenticated, hasPermission } = useAuth();
  const canCreate = hasPermission("project:create");
  const canArchive = hasPermission("project:delete");
  const [projectRows, setProjectRows] = useState<ProjectRow[]>([]);
  const [loading, setLoading] = useState(isAuthenticated);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [createDialogOpen, setCreateDialogOpen] = useState(false);
  const [projectName, setProjectName] = useState("");
  const { restored: projectNameDraftRestored, clearDraft: clearProjectNameDraft } = useFormDraft(
    "pesaguard.draft.project-name.v1", projectName, setProjectName,
  );
  const [query, setQuery] = useState("");
  const [archiveTarget, setArchiveTarget] = useState<ProjectRow | null>(null);
  const [restoreTarget, setRestoreTarget] = useState<ProjectRow | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const filteredProjects = projectRows.filter((project) => project.name.toLowerCase().includes(query.trim().toLowerCase()));

  useEffect(() => {
    if (!isAuthenticated) {
      setProjectRows([]);
      setLoading(false);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setLoadError(null);
    apiData<ProjectListResponse>("/api/v1/projects?page=0&size=100", { signal: controller.signal })
      .then((response) => {
        if (!Array.isArray(response.items) || typeof response.totalElements !== "number") {
          throw new Error("The projects API returned an incomplete list.");
        }
        setProjectRows(response.items);
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) {
          setLoadError(getUserMessage(requestError, "Unable to load projects."));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [isAuthenticated, reloadKey]);

  useEffect(() => {
    function applyProjectIntent() {
      const intent = window.sessionStorage.getItem("pesaguard.command.intent");
      if (intent !== "create-project") return;
      window.sessionStorage.removeItem("pesaguard.command.intent");
      setCreateDialogOpen(true);
      setError(null);
      setSuccess(null);
    }

    applyProjectIntent();
    window.addEventListener("pesaguard:command-intent", applyProjectIntent);
    return () => window.removeEventListener("pesaguard:command-intent", applyProjectIntent);
  }, []);

  async function createProject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!createDialogOpen || !isAuthenticated) return;
    const name = projectName.trim();
    let slug = name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
    if (/^[0-9]/.test(slug)) slug = `p-${slug}`;
    slug = slug.slice(0, 80).replace(/-+$/g, "");
    if (!/^[a-z][a-z0-9-]{1,79}$/.test(slug)) {
      setError("Project name must produce a slug that starts with a letter and contains 2–80 lowercase letters, numbers, or hyphens.");
      return;
    }
    setSaving(true);
    setError(null);
    setSuccess(null);
    try {
      const created = await apiData<ProjectRow>("/api/v1/projects", {
        method: "POST",
        body: JSON.stringify({ name, slug }),
      });
      if (!created.id) throw new Error("The projects API did not return the created project.");
      setSuccess(`Project “${created.name}” was created.`);
      setReloadKey((current) => current + 1);
      setProjectName("");
      clearProjectNameDraft();
      setCreateDialogOpen(false);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Project creation failed."));
    } finally {
      setSaving(false);
    }
  }

  async function archiveProject() {
    if (!archiveTarget || !isAuthenticated) return;
    setSaving(true);
    setError(null);
    try {
      await apiData(`/api/v1/projects/${encodeURIComponent(archiveTarget.id)}/archive`, { method: "POST" });
      setSuccess(`Project “${archiveTarget.name}” was archived.`);
      setArchiveTarget(null);
      setReloadKey((current) => current + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Project could not be archived."));
    } finally {
      setSaving(false);
    }
  }

  async function restoreProject() {
    if (!restoreTarget || !isAuthenticated) return;
    setSaving(true);
    setError(null);
    try {
      await apiData(`/api/v1/projects/${encodeURIComponent(restoreTarget.id)}/restore`, { method: "POST" });
      setSuccess(`Project â€œ${restoreTarget.name}â€ was restored.`);
      setRestoreTarget(null);
      setReloadKey((current) => current + 1);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Project could not be restored."));
    } finally {
      setSaving(false);
    }
  }

  function openProject(project: ProjectRow) {
    if (window.sessionStorage.getItem("pesaguard.project.id") !== project.id) {
      window.sessionStorage.removeItem("pesaguard.environment.id");
      window.sessionStorage.removeItem("pesaguard.environment.name");
      window.sessionStorage.removeItem("pesaguard.environment.type");
      window.sessionStorage.removeItem("pesaguard.environment.project");
      window.sessionStorage.removeItem("pesaguard.environment.project-id");
      window.dispatchEvent(new Event("pesaguard:environment-selected"));
    }
    window.sessionStorage.setItem("pesaguard.project.id", project.id);
    window.sessionStorage.setItem("pesaguard.project.name", project.name);
    window.dispatchEvent(new Event("pesaguard:project-selected"));
    onNavigate("project-overview");
  }

  return (
    <>
      <PageHeader
        eyebrow="WORKSPACE"
        title="Projects"
        description="Your integrations, all in one place."
        action={canCreate ? <button className="button button--primary" type="button" disabled={!isAuthenticated} title={!isAuthenticated ? "Sign in to create a project." : undefined} onClick={() => { setCreateDialogOpen(true); setError(null); setSuccess(null); }}><Plus size={16} />Create project</button> : undefined}
      />

      {!isAuthenticated && <div className="preview-notice"><span className="notice-icon"><FolderKanban size={16} /></span><p><strong>Sign in required</strong> — Project data and actions are available only through an authenticated workspace session. No sample projects are shown.</p></div>}
      {success && <p className="workflow-success" role="status">{success}</p>}
      {error && !createDialogOpen && <p className="workflow-error" role="alert">{error}</p>}

      <section className="panel project-directory">
        <div className="project-directory-heading">
          <div><h2>Your projects</h2><p>Select a project to open its APIs and settings.</p></div>
          <span className="directory-count">{projectRows.length} {projectRows.length === 1 ? "project" : "projects"}</span>
        </div>
        <label className="project-directory-search">
          <Search size={15} />
          <span className="visually-hidden">Search projects</span>
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Find a project" />
        </label>
        {loading && <p className="workflow-hint" role="status">Loading projects…</p>}
        {loadError && <div className="workflow-error" role="alert"><p>Unable to load projects: {loadError}</p><button className="text-button" type="button" onClick={() => setReloadKey((current) => current + 1)}>Retry</button></div>}
        <div className="project-directory-list">
          {filteredProjects.map((project) => (
            <article className="project-directory-row" key={project.id}>
              <span className="project-directory-icon resource-icon--project"><FolderKanban size={17} /></span>
              <div className="project-directory-info">
                <strong>{project.name}</strong>
                <span>{project.slug} · Created {new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(new Date(project.createdAt))}</span>
              </div>
              <span className="status-label"><span className={`status-dot${project.status !== "ACTIVE" ? " status-dot--muted" : ""}`} />{project.status}</span>
              <div className="project-directory-actions">
                {isAuthenticated && canArchive && project.status === "ACTIVE" && <button className="icon-button" type="button" aria-label={`Archive ${project.name}`} onClick={() => { setArchiveTarget(project); setError(null); }}><Archive size={14} /></button>}
                {isAuthenticated && canArchive && project.status === "ARCHIVED" && <button className="icon-button" type="button" aria-label={`Restore ${project.name}`} onClick={() => { setRestoreTarget(project); setError(null); }}><RotateCcw size={14} /></button>}
                <button className="button button--secondary" type="button" onClick={() => openProject(project)}>Open project</button>
              </div>
            </article>
          ))}
          {!loading && !loadError && filteredProjects.length === 0 && <div className="project-directory-empty"><FolderKanban size={22} /><strong>{query ? "No projects found" : "No projects yet"}</strong><span>{query ? "Try another project name." : "Create a project to start organizing your integration."}</span>{query && <button className="text-button" type="button" onClick={() => setQuery("")}>Clear search</button>}</div>}
        </div>
      </section>
      {createDialogOpen && canCreate && (
        <div className="dialog-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget && !saving) setCreateDialogOpen(false); }}>
          <section className="workflow-dialog" role="dialog" aria-modal="true" aria-labelledby="project-dialog-title">
            <div className="workflow-dialog-heading">
              <div className="resource-create-heading">
                <span className="resource-create-icon resource-icon--project" aria-hidden="true"><FolderKanban size={19} /></span>
                <div><span className="section-eyebrow">NEW PROJECT</span><h2 id="project-dialog-title">Create a project</h2><p>Give your integration a name. You can configure it after creation.</p></div>
              </div>
              <button className="icon-button" type="button" aria-label="Close dialog" disabled={saving} onClick={() => setCreateDialogOpen(false)}><X size={17} /></button>
            </div>
            <ValidatedForm className="workflow-form" onSubmit={createProject}>
              <label>Project name<input required minLength={2} maxLength={120} value={projectName} onChange={(event) => setProjectName(event.target.value)} placeholder="Payments integration" /></label>
              {projectNameDraftRestored && <p className="workflow-hint" role="status">Recovered your unfinished project name from this browser.</p>}
              <p className="workflow-hint">The project is created in the authenticated organization. External services are not provisioned.</p>
              {error && <p className="workflow-error" role="alert">{error}</p>}
              <div className="workflow-form-actions"><button className="button button--secondary" type="button" disabled={saving} onClick={() => setCreateDialogOpen(false)}>Cancel</button><button className="button button--primary" type="submit" disabled={saving || projectName.trim().length < 2}><Plus size={15} />{saving ? "Creating…" : "Create project"}</button></div>
            </ValidatedForm>
          </section>
        </div>
      )}
      {archiveTarget && canArchive && (
        <div className="dialog-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setArchiveTarget(null); }}>
          <section className="workflow-dialog" role="dialog" aria-modal="true" aria-labelledby="archive-project-title">
            <div className="workflow-dialog-heading">
              <div><span className="section-eyebrow">PROJECT MANAGEMENT</span><h2 id="archive-project-title">Archive “{archiveTarget.name}”?</h2><p>The project will be archived in the system and will no longer be available for active development.</p></div>
              <button className="icon-button" type="button" aria-label="Close dialog" onClick={() => setArchiveTarget(null)}><X size={17} /></button>
            </div>
            {error && <p className="workflow-error" role="alert">{error}</p>}
            <div className="workflow-form-actions"><button className="button button--secondary" type="button" disabled={saving} onClick={() => setArchiveTarget(null)}>Cancel</button><button className="button button--primary" type="button" disabled={saving} onClick={() => void archiveProject()}><Archive size={14} />{saving ? "Archiving…" : "Archive project"}</button></div>
          </section>
        </div>
      )}
      {restoreTarget && canArchive && (
        <div className="dialog-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setRestoreTarget(null); }}>
          <section className="workflow-dialog" role="dialog" aria-modal="true" aria-labelledby="restore-project-title">
            <div className="workflow-dialog-heading">
              <div><span className="section-eyebrow">PROJECT MANAGEMENT</span><h2 id="restore-project-title">Restore â€œ{restoreTarget.name}â€?</h2><p>The project will become active again. Its existing historical data remains unchanged.</p></div>
              <button className="icon-button" type="button" aria-label="Close dialog" onClick={() => setRestoreTarget(null)}><X size={17} /></button>
            </div>
            {error && <p className="workflow-error" role="alert">{error}</p>}
            <div className="workflow-form-actions"><button className="button button--secondary" type="button" disabled={saving} onClick={() => setRestoreTarget(null)}>Cancel</button><button className="button button--primary" type="button" disabled={saving} onClick={() => void restoreProject()}><RotateCcw size={14} />{saving ? "Restoringâ€¦" : "Restore project"}</button></div>
          </section>
        </div>
      )}
    </>
  );
}
