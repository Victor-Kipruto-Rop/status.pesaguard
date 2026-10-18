import { ShieldAlert } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";

export function ForbiddenPage() {
  return (
    <>
      <PageHeader eyebrow="403" title="Forbidden" description="This account does not have access to the requested view." />
      <section className="panel center-panel">
        <div className="empty-state-box">
          <ShieldAlert size={48} />
          <h3>Permission required</h3>
          <p>Ask a workspace owner or administrator to grant additional access.</p>
        </div>
      </section>
    </>
  );
}
