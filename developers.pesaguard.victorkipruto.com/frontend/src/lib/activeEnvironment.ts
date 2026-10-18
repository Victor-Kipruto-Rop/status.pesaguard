export type ActiveProject = {
  id: string;
  name: string;
};

export type ActiveEnvironment = {
  id: string;
  projectId: string;
  name: string;
  type: string;
  status: string;
  baseUrl?: string;
};

export type ActiveEnvironmentContext = {
  projectId: string;
  projectName: string;
  environmentId: string;
  environmentName: string;
  environmentType: string;
};

const ACTIVE_PROJECT_ID = "pesaguard.active-project-id";
const ENVIRONMENT_PREFIX = "pesaguard.active-environment.";

export function readActiveProjectId() {
  return window.localStorage.getItem(ACTIVE_PROJECT_ID)
    || window.sessionStorage.getItem("pesaguard.project.id")
    || "";
}

export function readActiveEnvironmentId(projectId: string) {
  if (!projectId) return "";
  return window.localStorage.getItem(`${ENVIRONMENT_PREFIX}${projectId}`)
    || (window.sessionStorage.getItem("pesaguard.project.id") === projectId
      ? window.sessionStorage.getItem("pesaguard.environment.id")
      : null)
    || "";
}

export function readActiveEnvironmentContext(): ActiveEnvironmentContext {
  const projectId = readActiveProjectId();
  const sessionProjectIsActive = window.sessionStorage.getItem("pesaguard.project.id") === projectId;
  return {
    projectId,
    projectName: sessionProjectIsActive ? window.sessionStorage.getItem("pesaguard.project.name") || "" : "",
    environmentId: sessionProjectIsActive ? window.sessionStorage.getItem("pesaguard.environment.id") || "" : "",
    environmentName: sessionProjectIsActive ? window.sessionStorage.getItem("pesaguard.environment.name") || "" : "",
    environmentType: sessionProjectIsActive ? window.sessionStorage.getItem("pesaguard.environment.type") || "" : "",
  };
}

export function setActiveProject(project: ActiveProject) {
  window.localStorage.setItem(ACTIVE_PROJECT_ID, project.id);
  window.sessionStorage.setItem("pesaguard.project.id", project.id);
  window.sessionStorage.setItem("pesaguard.project.name", project.name);
  for (const key of [
    "pesaguard.environment.id",
    "pesaguard.environment.name",
    "pesaguard.environment.type",
    "pesaguard.environment.project",
    "pesaguard.environment.project-id",
  ]) {
    window.sessionStorage.removeItem(key);
  }
  window.dispatchEvent(new Event("pesaguard:project-selected"));
  window.dispatchEvent(new Event("pesaguard:active-context-changed"));
}

export function setActiveEnvironment(project: ActiveProject, environment: ActiveEnvironment) {
  window.localStorage.setItem(ACTIVE_PROJECT_ID, project.id);
  window.localStorage.setItem(`${ENVIRONMENT_PREFIX}${project.id}`, environment.id);
  window.sessionStorage.setItem("pesaguard.project.id", project.id);
  window.sessionStorage.setItem("pesaguard.project.name", project.name);
  window.sessionStorage.setItem("pesaguard.environment.id", environment.id);
  window.sessionStorage.setItem("pesaguard.environment.name", environment.name);
  window.sessionStorage.setItem("pesaguard.environment.type", environment.type);
  window.sessionStorage.setItem("pesaguard.environment.project", project.name);
  window.sessionStorage.setItem("pesaguard.environment.project-id", project.id);
  window.dispatchEvent(new Event("pesaguard:project-selected"));
  window.dispatchEvent(new Event("pesaguard:environment-selected"));
  window.dispatchEvent(new Event("pesaguard:active-context-changed"));
}
