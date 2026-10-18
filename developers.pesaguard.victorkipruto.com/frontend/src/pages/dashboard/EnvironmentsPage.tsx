import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { ArrowRight, CloudCog, Plus, X } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";
import { setActiveEnvironment } from "../../lib/activeEnvironment";

const environmentTemplates = [
  { type: "DEVELOPMENT", name: "Development", description: "Build and test changes in an isolated development environment.", readiness: "Development" },
  { type: "SANDBOX", name: "Sandbox", description: "Test your integration with non-production data.", readiness: "Safe for development" },
  { type: "STAGING", name: "Staging", description: "Validate your integration before production.", readiness: "Pre-production testing" },
  { type: "PRODUCTION", name: "Production", description: "Run your integration with live traffic.", readiness: "Live environment" },
] as const;

type EnvironmentType = (typeof environmentTemplates)[number]["type"];
type Project = { id: string; name: string };
type ProjectListResponse = { items: Project[] };
type ProjectEnvironment = { id: string; projectId: string; name: string; type: EnvironmentType; status: string; createdAt: string };
type EnvironmentRow = ProjectEnvironment & { project: string };

export function EnvironmentsPage({ onNavigate }: { onNavigate: (page: "environment-detail") => void }) {
  const { isAuthenticated, hasPermission } = useAuth();
  const canCreate = hasPermission("environment:create");
  const [projects, setProjects] = useState<{ id: string; name: string }[]>([]);
  const [loading, setLoading] = useState(isAuthenticated);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [environmentRows, setEnvironmentRows] = useState<EnvironmentRow[]>([]);
  const [reloadKey, setReloadKey] = useState(0);
  const [selectedTemplate, setSelectedTemplate] = useState<(typeof environmentTemplates)[number] | null>(null);
  const [projectId, setProjectId] = useState("");
  const [environmentName, setEnvironmentName] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  useEffect(() => {
    if (!isAuthenticated) {
      setProjects([]);
      setEnvironmentRows([]);
      setLoading(false);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setLoadError(null);
    apiData<ProjectListResponse>("/api/v1/projects?page=0&size=100", { signal: controller.signal })
      .then((response) => {
        if (!Array.isArray(response.items)) throw new Error("The project list response did not contain an items array.");
        setProjects(response.items);
        setProjectId((current) => response.items.some((project) => project.id === current)
          ? current
          : response.items[0]?.id ?? "");
        return Promise.all(response.items.map(async (project) => {
          const environments = await apiData<ProjectEnvironment[]>(
            `/api/v1/projects/${encodeURIComponent(project.id)}/environments`,
            { signal: controller.signal },
          );
          if (!Array.isArray(environments)) throw new Error(`The environments API returned an invalid list for ${project.name}.`);
          return environments.map((environment) => ({ ...environment, project: project.name }));
        }));
      })
      .then((rows) => {
        if (!controller.signal.aborted) {
          setEnvironmentRows(rows.flat().sort((left, right) => right.createdAt.localeCompare(left.createdAt)));
        }
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setLoadError(getUserMessage(requestError, "Unable to load environments."));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [isAuthenticated, reloadKey]);

  function chooseTemplate(template: (typeof environmentTemplates)[number]) {
    setSelectedTemplate(template);
    setEnvironmentName(template.name);
    setError(null);
    setSuccess(null);
  }

  function projectHasEnvironmentType(type: EnvironmentType, forProjectId = projectId) {
    return environmentRows.some((environment) =>
      environment.projectId === forProjectId && environment.type === type);
  }

  function firstAvailableTemplate(forProjectId = projectId) {
    return environmentTemplates.find((template) =>
      !projectHasEnvironmentType(template.type, forProjectId)) ?? environmentTemplates[0];
  }

  function changeProject(nextProjectId: string) {
    setProjectId(nextProjectId);
    if (selectedTemplate && projectHasEnvironmentType(selectedTemplate.type, nextProjectId)) {
      const nextTemplate = environmentTemplates.find((template) =>
        !projectHasEnvironmentType(template.type, nextProjectId));
      if (nextTemplate) chooseTemplate(nextTemplate);
      else setError(null);
    } else {
      setError(null);
    }
  }

  async function createEnvironment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedTemplate || !projectId || !isAuthenticated) return;
    setSaving(true);
    setError(null);
    setSuccess(null);
    try {
      const createdEnvironment = await apiData<ProjectEnvironment>(
        `/api/v1/projects/${encodeURIComponent(projectId)}/environments`,
        {
          method: "POST",
          body: JSON.stringify({ name: environmentName.trim(), type: selectedTemplate.type satisfies EnvironmentType }),
        },
      );
      if (!createdEnvironment?.id) throw new Error("The server did not return the created environment.");
      setSuccess(`Environment “${createdEnvironment.name}” was created.`);
      setReloadKey((current) => current + 1);
      setSelectedTemplate(null);
    } catch (requestError) {
      setError(getUserMessage(requestError, "Environment creation failed."));
    } finally {
      setSaving(false);
    }
  }

  function openEnvironment(environment: EnvironmentRow) {
    setActiveEnvironment(
      { id: environment.projectId, name: environment.project },
      environment,
    );
    onNavigate("environment-detail");
  }

  return (
    <>
      <PageHeader
        eyebrow="WORKSPACE"
        title="Environments"
        description="Keep testing and live integrations in separate environments."
        action={canCreate ? <button className="button button--primary" type="button" disabled={!isAuthenticated || loading} title={!isAuthenticated ? "Sign in to create an environment." : undefined} onClick={() => chooseTemplate(firstAvailableTemplate())}><Plus size={16} />Add environment</button> : undefined}
      />

      {!isAuthenticated && <div className="preview-notice"><span className="notice-icon"><CloudCog size={16} /></span><p><strong>Sign in required</strong> — Environments are loaded from your authenticated projects. No sample environments are shown.</p></div>}
      {success && <p className="workflow-success" role="status">{success}</p>}
      {loadError && <div className="workflow-error" role="alert"><p>Unable to load environments: {loadError}</p><button className="text-button" type="button" onClick={() => setReloadKey((current) => current + 1)}>Retry</button></div>}

      <section className="panel environment-directory">
        <div className="environment-directory-heading">
          <div><h2>Project environments</h2><p>Choose an environment to view its tools and activity.</p></div>
          <span className="directory-count">{environmentRows.length} {environmentRows.length === 1 ? "environment" : "environments"}</span>
        </div>
        <div className="environment-directory-list">
          {loading && <p className="workflow-hint" role="status">Loading environments…</p>}
          {environmentRows.map((environment) => (
            <article className="environment-directory-row" key={environment.id}>
              <span className={`environment-directory-icon resource-icon--${environment.type.toLowerCase()}`}><CloudCog size={17} /></span>
              <div className="environment-directory-info">
                <strong>{environment.name}</strong>
                <span>{environment.project} · {environment.type}</span>
              </div>
              <span className="status-label"><span className={`status-dot${environment.status !== "ACTIVE" ? " status-dot--muted" : ""}`} />{environment.status}</span>
              <button className="button button--secondary" type="button" onClick={() => openEnvironment(environment)}>Open<ArrowRight size={13} /></button>
            </article>
          ))}
          {!loading && !loadError && environmentRows.length === 0 && <div className="project-directory-empty"><CloudCog size={22} /><strong>No environments yet</strong><span>Create an environment for one of your projects to begin.</span></div>}
        </div>
      </section>

      {selectedTemplate && canCreate && (
        <div className="dialog-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget && !saving) setSelectedTemplate(null); }}>
          <section className="workflow-dialog" role="dialog" aria-modal="true" aria-labelledby="environment-dialog-title">
            <div className="workflow-dialog-heading">
              <div className="resource-create-heading">
                <span className={`resource-create-icon resource-icon--${selectedTemplate.type.toLowerCase()}`} aria-hidden="true"><CloudCog size={19} /></span>
                <div><span className="section-eyebrow">NEW ENVIRONMENT</span><h2 id="environment-dialog-title">Create {selectedTemplate.name}</h2><p>{selectedTemplate.description}</p></div>
              </div>
              <button className="icon-button" type="button" aria-label="Close dialog" disabled={saving} onClick={() => setSelectedTemplate(null)}><X size={17} /></button>
            </div>
            <ValidatedForm className="workflow-form" onSubmit={createEnvironment}>
              <label>Project<select required value={projectId} onChange={(event) => changeProject(event.target.value)} disabled={loading || projects.length === 0}><option value="">{loading ? "Loading projects…" : "Select a project"}</option>{projects.map((project) => <option value={project.id} key={project.id}>{project.name}</option>)}</select></label>
              <label>Environment tier<select required value={selectedTemplate.type} onChange={(event) => {
                const template = environmentTemplates.find((candidate) => candidate.type === event.target.value);
                if (template) chooseTemplate(template);
              }}>
                {environmentTemplates.map((template) => {
                  const exists = projectHasEnvironmentType(template.type);
                  return <option value={template.type} key={template.type} disabled={exists}>{template.name}{exists ? " (already exists)" : ""}</option>;
                })}
              </select></label>
              <label>Environment name<input required minLength={2} maxLength={80} value={environmentName} onChange={(event) => setEnvironmentName(event.target.value)} /></label>
              {!loading && !loadError && projects.length === 0 && <p className="workflow-hint">Create a project first. Only projects returned by the authenticated API are shown.</p>}
              <p className="workflow-hint">Each project can have one environment per tier. Choose any tier that does not already exist for this project.</p>
              {environmentTemplates.every((template) => projectHasEnvironmentType(template.type)) && <p className="workflow-hint">This project already has all four environment tiers.</p>}
              <p className="workflow-hint">{selectedTemplate.type === "PRODUCTION" ? "Production uses live traffic. Confirm your project, scopes, and key restrictions before integration." : "This environment will be created for the selected project."}</p>
              {error && <p className="workflow-error" role="alert">{error}</p>}
              <div className="workflow-form-actions"><button className="button button--secondary" type="button" disabled={saving} onClick={() => setSelectedTemplate(null)}>Cancel</button><button className="button button--primary" type="submit" disabled={saving || !projectId || environmentName.trim().length < 2 || projectHasEnvironmentType(selectedTemplate.type)}><Plus size={15} />{saving ? "Creating…" : "Create environment"}</button></div>
            </ValidatedForm>
          </section>
        </div>
      )}
    </>
  );
}
