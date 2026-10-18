import { FileQuestion } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";

export function NotFoundPage() {
  return (
    <>
      <PageHeader eyebrow="404" title="Page not found" description="The requested resource could not be located in this workspace." />
      <section className="panel center-panel">
        <div className="empty-state-box">
          <FileQuestion size={48} />
          <h3>Nothing to see here</h3>
          <p>Try returning to the dashboard or navigating to another workspace area.</p>
        </div>
      </section>
    </>
  );
}
