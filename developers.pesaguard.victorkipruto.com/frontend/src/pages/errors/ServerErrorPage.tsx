import { ServerCrash } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";

export function ServerErrorPage() {
  return (
    <>
      <PageHeader eyebrow="500" title="Server error" description="The workspace encountered an unexpected issue while processing the request." />
      <section className="panel center-panel">
        <div className="empty-state-box">
          <ServerCrash size={48} />
          <h3>Something broke</h3>
          <p>Please retry after a moment or contact support if the issue persists.</p>
        </div>
      </section>
    </>
  );
}
