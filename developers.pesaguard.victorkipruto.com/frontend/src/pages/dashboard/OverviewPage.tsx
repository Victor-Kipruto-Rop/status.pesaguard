import { Activity, ArrowRight, FolderKanban, KeyRound } from "lucide-react";
import type { PageId } from "../../app/routes";
import { DashboardMonitor } from "../../components/developer/DashboardMonitor";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { OverviewPreview } from "./OverviewPreview";

interface OverviewPageProps {
  onNavigate: (page: PageId) => void;
}

export function OverviewPage({ onNavigate }: OverviewPageProps) {
  const { user, organization, isAuthenticated } = useAuth();

  function createProject() {
    window.sessionStorage.setItem("pesaguard.command.intent", "create-project");
    onNavigate("projects");
  }

  return (
    <>
      <PageHeader
        eyebrow="DEVELOPER WORKSPACE"
        title="Workspace"
        description="A clear view of your projects, API activity, and the systems supporting your integrations."
        action={<div className="overview-header-actions">
          <button className="button button--secondary" type="button" onClick={() => onNavigate("api-keys")}><KeyRound size={15} />API keys</button>
          <button className="button button--primary" type="button" onClick={createProject}><FolderKanban size={15} />Create project<ArrowRight size={14} /></button>
        </div>}
      />

      {isAuthenticated ? (
        <>
          <section className="workspace-welcome panel" aria-label="Current developer workspace">
            <div className="workspace-welcome-mark"><Activity size={20} aria-hidden="true" /></div>
            <div className="workspace-welcome-copy">
              <span className="section-eyebrow">CURRENT WORKSPACE</span>
              <h2>{organization?.name ?? "Your organization"}</h2>
              <p>Signed in as {user?.displayName || user?.email || "developer"}</p>
            </div>
            <span className="workspace-connected"><i />Connected to PesaGuard API</span>
          </section>
          <DashboardMonitor onNavigate={onNavigate} />
        </>
      ) : <OverviewPreview onNavigate={onNavigate} />}
    </>
  );
}
