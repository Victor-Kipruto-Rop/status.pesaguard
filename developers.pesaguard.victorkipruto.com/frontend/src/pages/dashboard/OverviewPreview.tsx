import { Activity, ArrowRight } from "lucide-react";
import type { PageId } from "../../app/routes";
import { useAuth } from "../../context/AuthContext";

export function OverviewPreview({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  const { isAuthenticated } = useAuth();

  return (
    <section className="panel backend-capability-state">
      <span className="backend-capability-icon"><Activity size={18} /></span>
      <h2>{isAuthenticated ? "Live workspace metrics are unavailable" : "Sign in to view workspace metrics"}</h2>
      <p>{isAuthenticated
        ? "No usage or request metrics are available from the system for this workspace yet. The dashboard does not substitute illustrative values."
        : "Workspace metrics are private account data. Sign in to load persisted usage and activity from the system."}</p>
      {isAuthenticated && <button className="button button--secondary" type="button" onClick={() => onNavigate("api-explorer")}>Inspect available APIs<ArrowRight size={14} /></button>}
    </section>
  );
}
