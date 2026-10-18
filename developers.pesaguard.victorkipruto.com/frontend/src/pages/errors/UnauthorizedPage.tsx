import { LockKeyhole } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";

export function UnauthorizedPage() {
  return (
    <>
      <PageHeader eyebrow="401" title="Unauthorized" description="Authentication is required to access this workspace resource." />
      <section className="panel center-panel">
        <div className="empty-state-box">
          <LockKeyhole size={48} />
          <h3>Session required</h3>
          <p>Please sign in again to continue using this developer account.</p>
        </div>
      </section>
    </>
  );
}
