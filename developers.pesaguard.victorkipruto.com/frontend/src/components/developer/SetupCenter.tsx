import { ArrowRight, Code2, FolderKanban, KeyRound, UserRound, CloudCog } from "lucide-react";
import type { PageId } from "../../app/routes";

interface SetupCenterProps {
  onNavigate: (page: PageId) => void;
}

const setupLinks: { title: string; detail: string; page: PageId; icon: typeof UserRound }[] = [
  { title: "Developer profile", detail: "Review your account identity and preferences.", page: "account-settings", icon: UserRound },
  { title: "Projects", detail: "Create and manage projects in your organization.", page: "projects", icon: FolderKanban },
  { title: "Environments", detail: "Open persisted sandbox, staging, and production environments.", page: "environments", icon: CloudCog },
  { title: "API credentials", detail: "Manage credentials available for your selected environment.", page: "api-keys", icon: KeyRound },
  { title: "API Explorer", detail: "Call supported endpoints using your current session.", page: "api-explorer", icon: Code2 },
];

export function SetupCenter({ onNavigate }: SetupCenterProps) {
  return (
    <section className="setup-center" aria-label="Developer setup links">
      <article className="panel onboarding-wizard">
        <div className="panel-heading">
          <div><span className="section-eyebrow">DEVELOPER WORKSPACE</span><h2>Continue setup</h2><p>Open a workspace area to review or update its persisted configuration.</p></div>
        </div>
        <div className="integration-milestones">
          {setupLinks.map(({ title, detail, page, icon: Icon }) => (
            <button className="milestone-toggle" key={page} type="button" onClick={() => onNavigate(page)}>
              <span className="wizard-step-icon"><Icon size={16} aria-hidden="true" /></span>
              <span className="milestone-copy"><strong>{title}</strong><small>{detail}</small></span>
              <ArrowRight size={14} aria-hidden="true" />
            </button>
          ))}
        </div>
      </article>
    </section>
  );
}
